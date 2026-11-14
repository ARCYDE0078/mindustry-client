package mindustrytool.features.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import arc.Core;
import arc.Events;
import arc.scene.event.Touchable;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import arc.util.Http;
import arc.util.Log;
import arc.util.Timer;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import mindustrytool.Config;
import mindustrytool.Utils;
import mindustrytool.features.auth.dto.LoginEvent;
import mindustrytool.features.auth.dto.LogoutEvent;
import mindustrytool.features.auth.dto.UserSession;
import mindustrytool.features.auth.dto.SessionLoadEvent;
import mindustrytool.ui.NetworkImage;
import arc.util.Http.HttpStatusException;

public class AuthService {
    private static AuthService instance;

    public static final String KEY_ACCESS_TOKEN = "mindustrytool.auth.access-token";
    public static final String KEY_REFRESH_TOKEN = "mindustrytool.auth.refresh-token";
    public static final String KEY_LOGIN_ID = "mindustrytool.auth.login-id";
    public static final String KEY_LOGIN_EXPIRY = "mindustrytool.auth.login-expiry";

    private UserSession currentSession;

    private CompletableFuture<Boolean> refreshFuture;
    private CompletableFuture<Void> loginFuture;
    /* Отмена входа кнопкой диалога: без флага опрос login-token продолжал бы жить до 5 минут и, получив токены,
     * молча залогинил бы пользователя, который нажал "Отмена" */
    private volatile boolean loginCancelled = false;
    /* id входа, чей диалог сейчас на экране: завершение СТАРОГО опроса не должно закрывать диалог нового входа */
    private volatile String activeLoginId;
    private AuthLoginDialog loginDialog;
    private Table authWindow;

    public static AuthService getInstance() {
        if (instance == null) {
            instance = new AuthService();
        }
        return instance;
    }

    private AuthService() {
    }

    public void init() {
        fetchSession();

        Timer.schedule(() -> {
            if (isLoggedIn()) {
                fetchSession();
            }
        }, 60 * 5, 60 * 5);

        String logindId = Core.settings.getString(KEY_LOGIN_ID);

        if (logindId != null) {
            Instant expiry = Instant.ofEpochMilli(Core.settings.getLong(KEY_LOGIN_EXPIRY, 0));

            if (expiry.isBefore(Instant.now())) {
                clearPendingLoginKeys();
            } else {
                pollLoginToken(logindId).exceptionally(e -> {
                    Log.err("Background login polling failed", e);
                    return null;
                });
            }
        }

        var wholeViewport = new Table();
        wholeViewport.name = "authWindow";
        wholeViewport.setFillParent(true);
        wholeViewport.top().right();

        authWindow = wholeViewport.table().get();

        authWindow.top().right();
        authWindow.touchable = Touchable.childrenOnly;

        Core.app.post(() -> Vars.ui.menuGroup.addChild(wholeViewport));

        Table content = new Table();
        content.setBackground(Styles.black6);

        authWindow.add(content).top().right().margin(8f);
        authWindow.toFront();

        arc.Events.on(SessionLoadEvent.class, e -> {
            var user = e.user;
            var error = e.error;
            var isLoading = e.isLoading;

            if (isLoading) {
                content.clear();
                content.add("@loading").wrapLabel(false).labelAlign(Align.left).padLeft(8);
            } else if (error != null) {
                content.clear();
                content.add("@error").labelAlign(Align.left).padLeft(8);
                content.add(error.getLocalizedMessage()).labelAlign(Align.left).padLeft(8).row();
                content.button("@retry", Icon.refresh, this::startLoginUI);

                Log.err("Failed to login", error);
            } else if (user == null) {
                content.clear();
                content.button("@login", this::startLoginUI).wrapLabel(false);
            } else if (user != null) {
                content.clear();

                if (user.getImageUrl() != null) {
                    content.add(new NetworkImage(user.getImageUrl())).size(64);
                }

                if (!Vars.mobile) {
                    content.add(user.getName()).labelAlign(Align.left).padLeft(8);
                }

                content.touchable = Touchable.enabled;
                content.clicked(() -> {
                    Vars.ui.showConfirm("Logout", "Logged in as " + user.getName() + "\nDo you want to logout?",
                            this::logout);
                });
            }

            content.pack();
        });
    }

    private void startLoginUI() {
        login()
                .thenRun(() -> Core.app.post(() -> Vars.ui.showInfo("Login successful!")))
                .exceptionally(e -> {
                    Core.app.post(() -> Vars.ui.showException("Login failed or timed out.", e));
                    return null;
                });
    }

    public UserSession getSession() {
        return currentSession;
    }

    public CompletableFuture<UserSession> fetchSession() {
        Core.app.post(() -> Events.fire(new SessionLoadEvent(currentSession, null, true)));
        CompletableFuture<String> future = new CompletableFuture<>();

        AuthHttp.get(Config.API_v4_URL + "auth/session", res -> future.complete(res.getResultAsString()),
                err -> future.completeExceptionally(err));

        return future.handle((json, err) -> {
            if (err != null) {
                Core.app.post(() -> Events.fire(new SessionLoadEvent(currentSession, err, false)));
                throw new RuntimeException(err);
            }

            UserSession session = json.isEmpty() ? null : Utils.fromJson(UserSession.class, json);
            this.currentSession = session;
            Core.app.post(() -> Events.fire(new SessionLoadEvent(session, null, false)));
            if (session != null) {
                Events.fire(session);
            }
            return session;
        });
    }

    public boolean isLoggedIn() {
        return currentSession != null && Core.settings.has(KEY_ACCESS_TOKEN)
                && Core.settings.has(KEY_REFRESH_TOKEN);
    }

    public synchronized CompletableFuture<Void> login() {
        if (loginFuture != null && !loginFuture.isDone()) {
            return loginFuture;
        }

        loginCancelled = false;
        loginFuture = new CompletableFuture<>();
        CompletableFuture<Void> thisLogin = loginFuture;

        if (loginDialog == null) {
            loginDialog = new AuthLoginDialog(this);
        }

        Core.app.post(() -> {
            loginDialog.showLoading();
            loginDialog.show();
        });

        Http.get(Config.API_v4_URL + "auth/app/login-uri")
                .timeout(10000)
                .error(err -> {
                    Core.app.post(() -> {
                        loginDialog.hide();
                        thisLogin.completeExceptionally(new RuntimeException("Failed to get login URI", err));
                    });
                })
                .submit(res -> {
                    try {
                        Jval json = Jval.read(res.getResultAsString());

                        String loginUrl = json.getString("loginUrl");
                        String loginId = json.getString("loginId");

                        //пока шёл запрос ссылки, пользователь мог нажать "Отмена" - тогда вход начинать не надо
                        if (loginCancelled || thisLogin.isDone()) {
                            return;
                        }

                        Core.app.post(() -> {
                            loginDialog.showLoginUrl(loginUrl);
                        });

                        Core.settings.put(KEY_LOGIN_ID, loginId);
                        Core.settings.put(KEY_LOGIN_EXPIRY, Instant.now().plus(Duration.ofMinutes(5)).toEpochMilli());
                        activeLoginId = loginId;

                        // Start polling for token
                        pollWithRetry(loginId, true).whenComplete((v, e) -> {
                            boolean current = loginId.equals(activeLoginId);
                            if (current) {
                                activeLoginId = null;
                            }

                            if (e != null) {
                                thisLogin.completeExceptionally(e);
                            } else {
                                thisLogin.complete(null);
                            }

                            if (current) {
                                Core.app.post(() -> loginDialog.hide());
                            }
                        });

                        openLoginUrl(loginUrl);

                    } catch (Exception e) {
                        activeLoginId = null;
                        thisLogin.completeExceptionally(new RuntimeException("Failed to start login flow", e));
                        Core.app.post(() -> loginDialog.hide());
                    }
                });

        return loginFuture;
    }

    /** Открывает ссылку входа в браузере; если не вышло (Android без браузера, sandbox) - копирует в буфер. */
    private void openLoginUrl(String loginUrl) {
        try {
            if (Core.app.openURI(loginUrl)) {
                return;
            }
        } catch (Throwable t) {
            Log.warn("Failed to open login URI via browser", t);
        }

        try {
            Core.app.setClipboardText(loginUrl);
            Core.app.post(() -> Vars.ui.showInfoFade(Core.bundle.get("auth.login.browser-fallback")));
        } catch (Throwable t) {
            Log.err("Failed to copy login URI to clipboard", t);
        }
    }

    synchronized void cancelLogin() {
        if (loginFuture != null && !loginFuture.isDone()) {
            loginCancelled = true;
            activeLoginId = null;
            //id входа в настройках пережил бы перезапуск и init() снова стал бы опрашивать отменённый вход
            clearPendingLoginKeys();
            loginFuture.completeExceptionally(new RuntimeException("Login cancelled"));
        }
    }

    private void clearPendingLoginKeys() {
        Core.settings.remove(KEY_LOGIN_ID);
        Core.settings.remove(KEY_LOGIN_EXPIRY);
    }

    private CompletableFuture<Void> pollLoginToken(String loginId) {
        return pollWithRetry(loginId, false);
    }

    private CompletableFuture<Void> pollWithRetry(String loginId, boolean cancellable) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        long expiry = Core.settings.getLong(KEY_LOGIN_EXPIRY, 0);
        long expiryMillis = expiry > 0 ? expiry : Instant.now().plus(Duration.ofMinutes(5)).toEpochMilli();

        pollAttempt(loginId, expiryMillis, future, cancellable);
        return future;
    }

    /**
     * Одна попытка опроса login-token. Раньше любой сбой (таймаут long-poll, 5xx, обрыв сети, пока пользователь
     * ещё входит в браузере) убивал весь вход; теперь повторяем каждые 2 с до истечения срока входа и сдаёмся
     * сразу только на окончательных 4xx.
     */
    private void pollAttempt(String loginId, long expiryMillis, CompletableFuture<Void> future, boolean cancellable) {
        if (future.isDone()) {
            return;
        }

        if (cancellable && loginCancelled) {
            future.completeExceptionally(new RuntimeException("Login cancelled"));
            return;
        }

        String stored = Core.settings.getString(KEY_LOGIN_ID, null);
        if (stored == null || !stored.equals(loginId)) {
            //id заменили новым входом или вышли - этот опрос уже никому не нужен
            future.completeExceptionally(new RuntimeException(Core.bundle.get("auth.login.failed")));
            return;
        }

        if (Instant.now().toEpochMilli() >= expiryMillis) {
            clearPendingLoginKeys();
            future.completeExceptionally(new RuntimeException(Core.bundle.get("auth.login.timeout")));
            return;
        }

        Http.get(Config.API_v4_URL + "auth/app/login-token?loginId=" + loginId)
                .timeout(60 * 1000)
                .error(e -> {
                    if (future.isDone()) {
                        return;
                    }

                    if (isTerminalLoginError(e)) {
                        clearPendingLoginKeys();
                        future.completeExceptionally(new RuntimeException("Failed to get login token", e));
                    } else if (Instant.now().toEpochMilli() >= expiryMillis) {
                        clearPendingLoginKeys();
                        future.completeExceptionally(new RuntimeException(Core.bundle.get("auth.login.timeout"), e));
                    } else {
                        schedulePollRetry(loginId, expiryMillis, future, cancellable);
                    }
                })
                .submit(res -> {
                    if (future.isDone()) {
                        return;
                    }

                    try {
                        Jval json = Jval.read(res.getResultAsString());

                        if (json.has("accessToken") && json.has("refreshToken")) {
                            //пользователь нажал "Отмена", пока ответ был в пути - токены не сохраняем
                            if (cancellable && loginCancelled) {
                                future.completeExceptionally(new RuntimeException("Login cancelled"));
                                return;
                            }

                            saveTokens(json.getString("accessToken"), json.getString("refreshToken"));
                            clearPendingLoginKeys();

                            fetchSession().whenComplete((v, e) -> {
                                if (e != null) {
                                    future.completeExceptionally(e);
                                } else {
                                    Core.app.post(() -> Events.fire(new LoginEvent()));
                                    future.complete(null);
                                }
                            });
                        } else if (Instant.now().toEpochMilli() >= expiryMillis) {
                            clearPendingLoginKeys();
                            future.completeExceptionally(new RuntimeException(Core.bundle.get("auth.login.timeout")));
                        } else {
                            //ответ без токенов = пользователь ещё не подтвердил вход в браузере
                            schedulePollRetry(loginId, expiryMillis, future, cancellable);
                        }
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });
    }

    private void schedulePollRetry(String loginId, long expiryMillis, CompletableFuture<Void> future,
            boolean cancellable) {
        Timer.schedule(() -> pollAttempt(loginId, expiryMillis, future, cancellable), 2);
    }

    /** 4xx (кроме 408 "запрос слишком долгий") - сервер сказал "нет" окончательно, повтор ничего не изменит. */
    private boolean isTerminalLoginError(Throwable err) {
        if (err instanceof HttpStatusException httpError && httpError.status != null) {
            int code = httpError.status.code;
            return code >= 400 && code < 500 && code != 408;
        }

        return false;
    }

    public void saveTokens(String accessToken, String refreshToken) {
        Core.settings.put(KEY_ACCESS_TOKEN, accessToken);
        Core.settings.put(KEY_REFRESH_TOKEN, refreshToken);
        Core.settings.forceSave();
    }

    public void logout() {
        String accessToken = Core.settings.getString(KEY_ACCESS_TOKEN, "");
        String refreshToken = Core.settings.getString(KEY_REFRESH_TOKEN, "");

        if (!accessToken.isEmpty() && !refreshToken.isEmpty()) {
            Jval json = Jval.newObject();

            json.put("accessToken", accessToken);
            json.put("refreshToken", refreshToken);

            Http.post(Config.API_v4_URL + "auth/app/logout", json.toString())
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + accessToken)
                    .error(err -> {
                        Core.app.post(() -> Vars.ui.showInfo("Logout failed: " + err.getMessage()));
                    })
                    .submit(res -> {
                        Core.app.post(() -> Vars.ui.showInfoFade("Logout successful!"));
                    });
        }

        Core.settings.remove(KEY_ACCESS_TOKEN);
        Core.settings.remove(KEY_REFRESH_TOKEN);
        Core.settings.remove(KEY_LOGIN_ID);

        fetchSession();

        Events.fire(new LogoutEvent());

        Log.info("Logged out");
    }

    public String getAccessToken() {
        return Core.settings.getString(KEY_ACCESS_TOKEN, null);
    }

    public String getRefreshToken() {
        return Core.settings.getString(KEY_REFRESH_TOKEN, null);
    }

    public boolean isTokenNearExpiry(String token) {
        if (token == null) {
            return true;
        }

        try {
            String[] parts = token.split("\\.");

            if (parts.length < 2)
                return true;

            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            Jval json = Jval.read(payload);
            long exp = json.getLong("exp", 0);
            long now = System.currentTimeMillis() / 1000;

            // "near expire (1 min)" -> 60 seconds
            return (exp - now) < 60;
        } catch (Exception e) {
            Log.err("Failed to parse token expiry", e);
            return true;
        }
    }

    public synchronized CompletableFuture<Boolean> refreshTokenIfNeeded() {
        if (refreshFuture != null && !refreshFuture.isDone()) {
            return refreshFuture;
        }

        refreshFuture = new CompletableFuture<>();

        String accessToken = getAccessToken();
        String refreshToken = getRefreshToken();

        if (refreshToken == null) {
            refreshFuture.complete(false);
            return refreshFuture;
        }

        if (accessToken != null && !isTokenNearExpiry(accessToken)) {
            refreshFuture.complete(false);
            return refreshFuture;
        }

        if (isTokenNearExpiry(refreshToken)) {
            Log.info("Refresh token near expiry, removed it");
            Core.settings.remove(KEY_REFRESH_TOKEN);
            refreshFuture.complete(false);
            return refreshFuture;
        }

        Jval json = Jval.newObject();

        json.put("refreshToken", refreshToken);

        Http.post(Config.API_v4_URL + "auth/app/refresh", json.toString())
                .header("Content-Type", "application/json")
                .timeout(10000)
                .error(err -> {
                    Log.err("Failed to refresh token", err);

                    // If refresh failed (e.g. 401), logout

                    if (err instanceof HttpStatusException httpError) {
                        if (httpError.status.code == 401) {
                            Core.settings.remove(KEY_ACCESS_TOKEN);
                            Core.settings.remove(KEY_REFRESH_TOKEN);
                            Log.info("Remove tokens");
                        }

                        if (httpError.response != null) {
                            Log.err(httpError.response.getResultAsString());
                        }
                    }
                    refreshFuture.completeExceptionally(err);
                })
                .submit(res -> {
                    try {
                        String str = res.getResultAsString();
                        Jval resJson = Jval.read(str);
                        if (resJson.has("accessToken") && resJson.has("refreshToken")) {
                            saveTokens(resJson.getString("accessToken"), resJson.getString("refreshToken"));

                            Log.info("Token refreshed successfully");
                            refreshFuture.complete(true);
                        } else {
                            refreshFuture.completeExceptionally(
                                    new RuntimeException("Invalid refresh response: " + resJson));
                        }
                    } catch (Exception e) {
                        if (e instanceof HttpStatusException httpError) {
                            if (httpError.status.code == 401) {
                                logout();
                            }
                        }
                        refreshFuture
                                .completeExceptionally(new RuntimeException("Failed to refresh token: exception", e));
                    }
                });

        return refreshFuture;
    }
}
