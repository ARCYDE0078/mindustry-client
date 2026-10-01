package mindustry.net;

import arc.*;
import arc.files.*;
import arc.func.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.client.utils.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.io.*;
import mindustry.net.Administration.*;
import mindustry.net.Packets.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import java.io.*;
import java.net.*;

import static mindustry.Vars.*;

/** Handles control of bleeding edge builds. */
public class BeControl{
    private static final int updateInterval = 120; // Poll every 120s (30/hr), this leaves us with 30 requests per hour to spare.

    /** Whether or not to automatically display an update prompt on client load and every couple of minutes. */
    public boolean checkUpdates;
    //volatile: пишутся из HTTP-потока (checkUpdate), читаются из потока рендера и из потока загрузки
    private volatile boolean updateAvailable;
    private volatile String updateUrl;
    private volatile String updateBuild;
    /** Имя ассета с контрольными суммами в релизе (формат `sha256sum`: «<hex>  <имя файла>»), кладётся release-custom.yml. */
    static final String CHECKSUM_ASSET = "checksums.sha256";
    /** Откуда брать ожидаемый SHA-256 скачиваемого jar: ссылка на {@link #CHECKSUM_ASSET} релиза (null - нет такого ассета). */
    private volatile String updateHashUrl;
    /** Ожидаемый SHA-256 из поля `digest` ассета в ответе GitHub API (null - API не отдал; у атом-фолбэка его нет). */
    private volatile String updateSha256;
    /**
     * true - без опубликованной контрольной суммы обновление НЕ ставится (канал custom-b*: апдейтер автоматический,
     * поэтому подмена релиза = выполнение чужого кода у всех клиентов). false - осознанные ручные кнопки sonka
     * (Switch to v7 и т.п.) на чужие релизы: суммы там может не быть, поэтому ставим с предупреждением в лог, а если
     * сумма есть - она проверяется всегда.
     */
    private volatile boolean updateStrict = true;
    //sonka: "апдейтер ничего не видит" - checkUpdate(false) значило и "ты и так актуален", и
    //"запрос к GitHub упал" (рейтлимит/сеть/битый JSON) одинаково - разница уходила только в
    //Log.err, которого пользователь не видит. Явные ручные проверки (кнопки) теперь могут
    //показать РЕАЛЬНУЮ причину вместо вводящего в заблуждение "нет обновлений"
    private String lastError;

    /** @return whether this is a bleeding edge build. */
    public boolean active(){
        return Version.type.equals("bleeding-edge") && !steam;
    }

    public BeControl(){
    
    }

    public void init(){
        Events.on(EventType.ClientLoadEvent.class, event -> {
            checkUpdates = Core.settings.getBool("autoupdate");
            Timer.schedule(() -> {
                    if(checkUpdates && !mobile){ // Don't auto update on manually cloned copies of the repo
                        checkUpdate(result -> {
                            if (result) showUpdateDialog();
                        });
                    }
                }, 1, updateInterval
            );

            if(OS.hasProp("becopy")){
                try{
                    Fi dest = Fi.get(OS.prop("becopy"));
                    Fi self = Fi.get(BeControl.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());

                    for(Fi file : self.parent().findAll(f -> !f.equals(self))) file.delete();

                    //sonka: раньше self.copyTo(dest) truncate'ил dest прямо на месте и стримил в него -
                    //если этот процесс-установщик убьют посреди записи, живой файл клиента остаётся
                    //пустым/битым, что выглядит как самоудаление клиента. Копируем во временный файл
                    //рядом с dest и подменяем одним move - оригинал цел, пока копия не завершится
                    Fi tmp = dest.sibling(dest.name() + ".update-tmp");
                    self.copyTo(tmp);
                    try{
                        java.nio.file.Files.move(tmp.file().toPath(), dest.file().toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    }catch(java.nio.file.AtomicMoveNotSupportedException atomicUnsupported){
                        java.nio.file.Files.move(tmp.file().toPath(), dest.file().toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }catch(Throwable e){
                    e.printStackTrace();
                }
            }
        });
    }


    public void checkUpdate(Boolc done) {
        String repo = Core.settings.getString("updateurl");
        //sonka: канал обновлений пуст, пока не залит на GitHub (см. апдейтер-предохранители) -
        //запрос ".../repos//releases/latest" на пустой repo молча 404-ил каждый запуск, засоряя лог
        if(repo == null || repo.isEmpty()){
            done.get(false);
            return;
        }
        checkUpdate(done, repo);
    }

    /** asynchronously checks for updates. Equivalent to checkUpdate(done, repo, true). */
    public void checkUpdate(Boolc done, String repo){
        checkUpdate(done, repo, true);
    }

    /**
     * asynchronously checks for updates.
     * sonka: requireCustomChannel гейтит второй предохранитель после инцидента 2026-08-20 (апдейтер
     * подменил кастомную сборку стоковым Foo) - по умолчанию true, т.к. большинство вызовов идут от
     * мутируемой настройки updateurl (или удалённой команды CommandTransmission.UPDATE от чужого
     * сертификата) и не должны иметь возможность увести клиент со своего канала custom-b*. Кнопки,
     * которые sonka жмёт САМ намеренно (Uninstall foo's, Switch to v7), передают false - это его
     * осознанный выбор уйти с кастомной сборки, а не тихая подмена.
     */
    public void checkUpdate(Boolc done, String repo, boolean requireCustomChannel){
        Http.get("https://api.github.com/repos/" + repo + "/releases/latest")
            .error(e -> {
                //sonka: анонимный api.github.com даёт 60 запросов/час на IP - за VPN/CGNAT/общим wifi лимит
                //выбирают чужие, и проверка падает с 403/429. releases.atom лежит на github.com, под лимит
                //API не попадает, поэтому на rate limit падаем на него вместо голой ошибки.
                if(e instanceof Http.HttpStatusException he && (he.status == Http.HttpStatus.FORBIDDEN || he.status.code == 429)){
                    Log.warn("[updater] GitHub API rate limit (@), falling back to releases.atom", he.status);
                    checkUpdateAtom(done, repo, requireCustomChannel);
                    return;
                }
                Core.app.post(() -> {
                    lastError = e.getMessage() != null ? e.getMessage() : e.toString();
                    done.get(false);
                    Log.err("Failed to check for updates", e);
                });
            })
            .submit(res -> {
                Jval val = Jval.read(res.getResultAsString());
                String newBuild = val.getString("name", "");
                if(requireCustomChannel && !newBuild.startsWith("custom-")){
                    Log.warn("[updater] release '@' is not from the custom channel (custom-b*), ignoring", newBuild);
                    Core.app.post(() -> {
                        lastError = null;
                        done.get(false);
                    });
                    return;
                }
                if(!newBuild.trim().isEmpty() && !Version.clientVersion.equals(newBuild)){
                    Jval asset = val.get("assets").asArray().find(v -> v.getString("name", "").toLowerCase().contains("desktop"));
                    if (asset == null) asset = val.get("assets").asArray().find(v -> v.getString("name", "").toLowerCase().contains("mindustry"));
                    if (asset == null) {
                        Core.app.post(() -> {
                            lastError = "release '" + newBuild + "' has no desktop/mindustry asset";
                            done.get(false);
                        });
                        return;
                    }
                    String assetUrl = asset.getString("browser_download_url", "");
                    if(!isReleaseDownloadUrl(assetUrl)){ // иначе подменённый ответ API мог бы увести загрузку на произвольный хост
                        Core.app.post(() -> {
                            lastError = "release '" + newBuild + "': unexpected download url " + assetUrl;
                            done.get(false);
                        });
                        return;
                    }
                    Jval sums = val.get("assets").asArray().find(v -> CHECKSUM_ASSET.equals(v.getString("name", "")));
                    String sumsUrl = sums == null ? "" : sums.getString("browser_download_url", "");
                    String digest = asset.getString("digest", ""); // "sha256:<hex>" - GitHub считает сам при загрузке ассета
                    updateUrl = assetUrl;
                    updateHashUrl = isReleaseDownloadUrl(sumsUrl) ? sumsUrl : null;
                    updateSha256 = digest.startsWith("sha256:") && isHex64(digest.substring(7)) ? digest.substring(7).toLowerCase() : null;
                    updateStrict = requireCustomChannel;
                    updateAvailable = true;
                    updateBuild = newBuild;
                    Core.app.post(() -> {
                        lastError = null;
                        done.get(true);
                    });
                }else{
                    Core.app.post(() -> {
                        lastError = null;
                        done.get(false);
                    });
                }
            });
    }

    /** Фолбэк {@link #checkUpdate}: берёт последний тег из releases.atom (без лимита API), ассет качается по стабильной ссылке /releases/download/. */
    private void checkUpdateAtom(Boolc done, String repo, boolean requireCustomChannel){
        Http.get("https://github.com/" + repo + "/releases.atom")
            .error(e -> Core.app.post(() -> {
                lastError = "GitHub API rate limit (403), fallback failed: " + (e.getMessage() != null ? e.getMessage() : e.toString());
                done.get(false);
                Log.err("Failed to check for updates (atom fallback)", e);
            }))
            .submit(res -> {
                String body = res.getResultAsString();
                int entry = body.indexOf("<entry>");
                int a = entry < 0 ? -1 : body.indexOf("<title>", entry);
                int b = a < 0 ? -1 : body.indexOf("</title>", a);
                String newBuild = b < 0 ? "" : body.substring(a + 7, b).trim();
                if(newBuild.isEmpty()){
                    Core.app.post(() -> {
                        lastError = "releases.atom: no releases found";
                        done.get(false);
                    });
                    return;
                }
                if(requireCustomChannel && !newBuild.startsWith("custom-")){
                    Log.warn("[updater] release '@' is not from the custom channel (custom-b*), ignoring", newBuild);
                    Core.app.post(() -> {
                        lastError = null;
                        done.get(false);
                    });
                    return;
                }
                boolean update = !Version.clientVersion.equals(newBuild);
                if(update){
                    if(!newBuild.matches("[A-Za-z0-9._-]+")){ // тег уходит в путь URL: никаких «/», пробелов и прочего
                        Core.app.post(() -> {
                            lastError = "releases.atom: unexpected release name '" + newBuild + "'";
                            done.get(false);
                        });
                        return;
                    }
                    String base = "https://github.com/" + repo + "/releases/download/" + newBuild + "/";
                    updateUrl = base + "Mindustry-custom-desktop.jar";
                    updateHashUrl = base + CHECKSUM_ASSET; // API (а с ним и digest) недоступен - остаётся только ассет с суммами
                    updateSha256 = null;
                    updateStrict = requireCustomChannel;
                    updateAvailable = true;
                    updateBuild = newBuild;
                }
                Core.app.post(() -> {
                    lastError = null;
                    done.get(update);
                });
            });
    }

    /** @return the reason the last {@link #checkUpdate} call failed to complete (network/rate limit/parse error), or null if it completed normally (whether or not an update was found). */
    public String lastError(){
        return lastError;
    }

    /** @return whether a new update is available */
    public boolean isUpdateAvailable(){
        return updateAvailable;
    }

    /** Sets updateAvailable to the specified value */
    public void setUpdateAvailable(boolean available){
        updateAvailable = available;
    }

    /** shows the dialog for updating the game on desktop, or a prompt for doing so on the server */
    public void showUpdateDialog(){
        if(!updateAvailable) return;

        if(!headless){
            checkUpdates = false;
            ui.showCustomConfirm(
                Core.bundle.format("be.update", "") + " Current: " + Version.clientVersion + " New: " + updateBuild, "@be.update.confirm", "@ok", "@be.ignore",
                this::actuallyDownload, () -> checkUpdates = false);
        }else{
            Log.info("&lcCurrent: " + Version.clientVersion + " A new update is available: &lyBleeding Edge build @", updateBuild);
            if(Config.autoUpdate.bool()){
                Log.info("&lcAuto-downloading next version...");

                try{
                    //download new file from github
                    Fi source = Fi.get(BeControl.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());
                    Fi dest = source.sibling("server-be-" + updateBuild + ".jar");

                    download(updateUrl, dest,
                    len -> Core.app.post(() -> Log.info("&ly| Size: @ MB.", Strings.fixed((float)len / 1024 / 1024, 2))),
                    progress -> {},
                    () -> false,
                    () -> {
                        try{
                            verifyDownload(dest);
                        }catch(Throwable e){
                            dest.delete();
                            Log.err("[updater] integrity check failed, update NOT installed", e);
                            return;
                        }
                        Core.app.post(() -> {
                            Log.info("&lcSaving...");
                            SaveIO.save(saveDirectory.child("autosavebe." + saveExtension));
                            Log.info("&lcAutosaved.");

                            netServer.kickAll(KickReason.serverRestarting);
                            Threads.sleep(500);

                            Log.info("&lcVersion downloaded, exiting. Note that if you are not using a auto-restart script, the server will not restart automatically.");
                            //replace old file with new
                            dest.copyTo(source);
                            dest.delete();
                            System.exit(2); //this will cause a restart if using the script
                        });
                    },
                    Throwable::printStackTrace);
                }catch(Exception e){
                    e.printStackTrace();
                }
            }
            checkUpdates = false;
        }
    }

    /** Convenience method to download a jar so that this doesn't need to get copied multiple times */
    public void downloadJar(String url, Fi dest, Runnable done, Cons<Throwable> error){
        download(url, dest, l -> {}, p -> {}, () -> false, done, error);
    }

    private void download(String furl, Fi dest, Intc length, Floatc progressor, Boolp canceled, Runnable done, Cons<Throwable> error){
        mainExecutor.submit(() -> {
            boolean finished = false;
            try{
                HttpURLConnection con = (HttpURLConnection)new URL(furl).openConnection();
                //без таймаутов зависший сокет вешал поток загрузки навсегда, а не-200 ответ (страница ошибки) писался в jar
                con.setConnectTimeout(15000);
                con.setReadTimeout(30000);
                int code = con.getResponseCode();
                if(code != 200) throw new IOException("HTTP " + code + " for " + furl);

                try(BufferedInputStream in = new BufferedInputStream(con.getInputStream()); OutputStream out = dest.write(false, 4096)){
                    byte[] data = new byte[4096];
                    long size = con.getContentLength();
                    long counter = 0;
                    length.get((int)size);
                    int x;
                    while((x = in.read(data, 0, data.length)) >= 0 && !canceled.get()){
                        counter += x;
                        progressor.get((float)counter / (float)size);
                        out.write(data, 0, x);
                    }
                }
                if(canceled.get()){
                    dest.delete(); // недокачанный файл не должен остаться лежать как будто это готовая сборка
                    return;
                }
                finished = true;
                done.run();
            }catch(Throwable e){
                if(!finished){
                    try{ dest.delete(); }catch(Throwable ignored){}
                }
                error.get(e);
            }
        });
    }

    // ---- проверка целостности скачанного обновления ----

    /** Ссылка на ассет релиза GitHub: https://github.com/&lt;owner&gt;/&lt;repo&gt;/releases/download/... (репозиторий не сверяем - переименованные репо отдают канонические имена). */
    static boolean isReleaseDownloadUrl(String url){
        return url != null && url.startsWith("https://github.com/") && url.contains("/releases/download/") && !url.contains("..");
    }

    static boolean isHex64(String s){
        if(s == null || s.length() != 64) return false;
        for(int i = 0; i < 64; i++){
            if(Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    /**
     * Достаёт SHA-256 файла {@code assetName} из текста в формате `sha256sum` («&lt;hex&gt;  [*]&lt;имя&gt;» на строку).
     * Если в тексте голая сумма без имени - берёт её. @return строчный hex или null, если подходящей строки нет.
     */
    static String parseChecksum(String text, String assetName){
        String bare = null;
        for(String line : text.split("\\r?\\n")){
            line = line.trim();
            if(line.length() < 64 || !isHex64(line.substring(0, 64))) continue;
            String name = line.substring(64).trim();
            if(name.startsWith("*")) name = name.substring(1);
            if(name.isEmpty()){
                bare = line.substring(0, 64).toLowerCase();
            }else if(name.equals(assetName)){
                return line.substring(0, 64).toLowerCase();
            }
        }
        return bare;
    }

    private static String fetchText(String url) throws IOException{
        HttpURLConnection con = (HttpURLConnection)new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(15000);
        int code = con.getResponseCode();
        if(code != 200) throw new IOException("HTTP " + code + " for " + url);
        try(InputStream in = new BufferedInputStream(con.getInputStream())){
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while((n = in.read(buf)) >= 0){
                out.write(buf, 0, n);
                if(out.size() > 64 * 1024) throw new IOException("checksum file is too large: " + url); // файл с суммами - пара строк
            }
            return out.toString("UTF-8");
        }
    }

    /**
     * Ожидаемый SHA-256 скачанного jar: ассет {@link #CHECKSUM_ASSET} релиза и/или `digest` из GitHub API.
     * Если есть оба и они расходятся - это уже сигнал подмены, а не отсутствие суммы, поэтому исключение.
     * @return строчный hex, либо null, если сумма не опубликована и обновление не strict.
     */
    private String expectedSha256() throws IOException{
        String url = updateUrl;
        String assetName = url.substring(url.lastIndexOf('/') + 1);
        String fromAsset = null;
        String hashUrl = updateHashUrl;
        if(hashUrl != null){
            try{
                fromAsset = parseChecksum(fetchText(hashUrl), assetName);
            }catch(IOException e){
                Log.warn("[updater] could not read @: @", hashUrl, e.toString()); // нет ассета (404) - не фатально, если есть digest
            }
        }
        String fromApi = updateSha256;
        if(fromAsset != null && fromApi != null && !fromAsset.equals(fromApi)){
            throw new IOException("SHA-256 from " + CHECKSUM_ASSET + " (" + fromAsset + ") differs from the digest GitHub reports (" + fromApi + ")");
        }
        String expected = fromAsset != null ? fromAsset : fromApi;
        if(expected == null && updateStrict){
            throw new IOException("the release publishes no SHA-256 (" + CHECKSUM_ASSET + " asset), refusing to install an unverified build");
        }
        return expected;
    }

    /**
     * Проверяет скачанный jar: это zip (PK\3\4), а его SHA-256 совпадает с опубликованным.
     * Вызывается из потока загрузки (блокирует сетью ради файла с суммами), бросает исключение при любом несоответствии.
     */
    private void verifyDownload(Fi file) throws Exception{
        String expected = expectedSha256();

        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] head = new byte[4];
        long total = 0;
        try(InputStream in = new BufferedInputStream(file.read(), 1 << 16)){
            byte[] buf = new byte[1 << 16];
            int n;
            while((n = in.read(buf)) >= 0){
                for(int i = 0; i < n && total + i < 4; i++) head[(int)(total + i)] = buf[i];
                total += n;
                md.update(buf, 0, n);
            }
        }
        if(total < 4 || head[0] != 'P' || head[1] != 'K' || head[2] != 3 || head[3] != 4){
            throw new IOException("the downloaded file is not a jar/zip (" + total + " bytes)");
        }
        StringBuilder hex = new StringBuilder();
        for(byte b : md.digest()) hex.append(String.format("%02x", b));
        String actual = hex.toString();

        if(expected == null){
            Log.warn("[updater] no published SHA-256 for @, installing unverified (@)", updateBuild, actual);
        }else if(!expected.equals(actual)){
            throw new IOException("SHA-256 mismatch: expected " + expected + ", got " + actual);
        }else{
            Log.info("[updater] SHA-256 verified: @", actual);
        }
    }

    public void actuallyDownload() {
        actuallyDownload(null);
    }

    public void actuallyDownload(@Nullable String sender) {
        if(!updateAvailable) return;
        if(OS.isAndroid || OS.isIos){ // нет запущенного jar, который можно подменить (getCodeSource == null) и нет java для перезапуска
            if(!headless) ui.showInfo("@client.update.mobile");
            return;
        }
        try{
            boolean[] cancel = {false};
            float[] progress = {0};
            int[] length = {0};
            Fi file = bebuildDirectory.child("client-be-" + updateBuild + ".jar");
            Fi fileDest = OS.hasProp("becopy") ?
                Fi.get(OS.prop("becopy")) :
                Fi.get(BeControl.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());

            BaseDialog dialog = new BaseDialog("@be.updating");
            download(updateUrl, file, i -> length[0] = i, v -> progress[0] = v, () -> cancel[0], () -> {
                Log.info(file.absolutePath());
                //jar запускается как отдельный процесс с правами игрока - не доверяем скачанному, пока не сверили SHA-256
                try{
                    verifyDownload(file);
                }catch(Throwable e){
                    file.delete();
                    Log.err("[updater] integrity check failed, update NOT installed", e);
                    Core.app.post(() -> {
                        dialog.hide();
                        ui.showErrorMessage(Core.bundle.format("client.update.verifyfailed", Strings.neatError(e)));
                    });
                    return;
                }
                ClientUtils.openJar("-Dberestart", "-Dbecopy=" + fileDest.absolutePath(), "-jar", file.absolutePath());
            }, e -> {
                dialog.hide();
                ui.showException(e);
            });

            dialog.cont.add(new Bar(() -> length[0] == 0 ? Core.bundle.get("be.updating") : (int)(progress[0] * length[0])/1024/1024 + "/" + length[0]/1024/1024 + " MB", () -> Pal.accent, () -> progress[0])).width(400f).height(70f);
            if (sender == null) {
                dialog.buttons.button("@cancel", Icon.cancel, () -> {
                    cancel[0] = true;
                    dialog.hide();
                }).size(210f, 64f);
            } else {
                dialog.cont.row();
                dialog.cont.add("By royal decree of emperor [accent]" + sender + "[white] your client is being updated.");
            }
            dialog.buttons.button("@close", Icon.menu, dialog::hide).size(210f, 64f);
            dialog.setFillParent(false);
            dialog.show();
        }catch(Exception e){
            ui.showException(e);
        }
    }
}
