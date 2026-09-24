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
    private boolean updateAvailable;
    private String updateUrl;
    private String updateBuild;
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
                    updateUrl = asset.getString("browser_download_url", "");
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
                    updateUrl = "https://github.com/" + repo + "/releases/download/" + newBuild + "/Mindustry-custom-desktop.jar";
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
                    () -> Core.app.post(() -> {
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
                    }),
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
            try{
                HttpURLConnection con = (HttpURLConnection)new URL(furl).openConnection();
                BufferedInputStream in = new BufferedInputStream(con.getInputStream());
                OutputStream out = dest.write(false, 4096);

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
                out.close();
                in.close();
                if(!canceled.get()) done.run();
            }catch(Throwable e){
                error.get(e);
            }
        });
    }

    public void actuallyDownload() {
        actuallyDownload(null);
    }

    public void actuallyDownload(@Nullable String sender) {
        if(!updateAvailable) return;
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
