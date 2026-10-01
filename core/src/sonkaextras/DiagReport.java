package sonkaextras;

import arc.*;
import arc.files.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.core.*;
import mindustry.gen.*;
import mindustry.mod.Mods.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import java.text.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * Отчёт для багрепорта одной кнопкой: версия Monolith + движка, ОС/Java/GPU, внешние моды, настройки
 * (без секретов), последние краши и хвост {@code last_log.txt}. Раньше sonka руками искал
 * {@code last_log.txt} в папке данных и перекладывал его в {@code MindustryMods/logs.txt}; теперь
 * «Скопировать в буфер» / «Сохранить в файл» из главного меню или из секции Sonka Extras.
 * <p>
 * Ванильная кнопка «Экспорт логов» в «Данные игры» (SettingsMenuDialog.getLogs) отдаёт только
 * краши + лог без контекста - какая сборка, какие моды стояли, какие настройки нестандартные -
 * ровно то, что приходилось переспрашивать при каждом разборе бага. Она не тронута, этот отчёт
 * дополняет её.
 * <p>
 * Настройки: дампятся все ключи, КРОМЕ похожих на секреты/идентификаторы ({@link #SENSITIVE}):
 * usid/uuid - это серверные идентификаторы игрока (по ним можно выдавать себя за него), ключи TLS
 * чата Foo's, токены, адреса серверов. Ошибиться в сторону «не включили» дешевле, чем утечь
 * usid в публичный багрепорт. Значения длиннее {@link #MAX_VALUE} обрезаются (в settings лежат и
 * бинарные блобы вроде раскладки таблицы схем - в отчёте от них только тип и длина).
 */
public class DiagReport{
    /** Имя файла копии отчёта в папке данных (перезаписывается при каждом построении). */
    public static final String FILE_NAME = "monolith-report.txt";
    /** Сколько символов хвоста last_log.txt кладём в буфер обмена - полный лог бывает на мегабайты. */
    private static final int CLIPBOARD_LOG_TAIL = 200_000;
    /** Обрезка одного значения настройки. */
    private static final int MAX_VALUE = 200;
    /** Сколько последних файлов из crashes/ включать. */
    private static final int MAX_CRASHES = 2;
    /** Подстроки ключей настроек, которые НЕ попадают в отчёт. */
    private static final String[] SENSITIVE = {
        "usid", "uuid", "token", "password", "passwd", "secret", "cert", "tls", "private", "auth",
        "login", "lastserver", "servers", "claj", "discord", "ip-", "-ip", "host",
        //API-ключи переводчиков (mindustrytool.chat-translation.deepl/gemini.api-key), id чата (персональный UUID),
        //почта/куки и пути к папкам (в них имя пользователя ОС)
        "apikey", "api-key", "api_key", "deepl", "gemini", "chat-id", "chatid", "email", "cookie", "bearer",
        "directory", "lastdir", "path"
    };
    private static final java.util.regex.Pattern IPV4 = java.util.regex.Pattern.compile(
        "\\b(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}\\b");
    private static final java.util.regex.Pattern BEARER = java.util.regex.Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");

    private DiagReport(){
    }

    /** Полный отчёт (для файла): лог целиком. */
    public static String build(boolean includeSettings){
        return build(includeSettings, Integer.MAX_VALUE);
    }

    /**
     * @param includeSettings добавлять ли дамп настроек
     * @param logTail         сколько последних символов last_log.txt включать
     */
    public static String build(boolean includeSettings, int logTail){
        StringBuilder out = new StringBuilder(64 * 1024);
        out.append("=== Monolith diagnostic report ===\n");
        out.append("date: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date())).append('\n');
        out.append("client: ").append(Version.clientVersion).append('\n');
        out.append("engine: ").append(Version.combined()).append('\n');
        out.append("os: ").append(OS.osName).append(' ').append(OS.osVersion).append(' ').append(OS.osArch).append('\n');
        out.append("java: ").append(OS.javaVersion).append(" (").append(System.getProperty("java.vendor", "?")).append(")\n");
        out.append("memory: ").append(Core.app.getJavaHeap() / (1024 * 1024)).append(" MB heap used, max ")
            .append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append(" MB\n");
        try{
            out.append("gl: ").append(Core.graphics.getGLVersion()).append('\n');
        }catch(Throwable ignored){
        }
        out.append("display: ").append(Core.graphics.getWidth()).append('x').append(Core.graphics.getHeight())
            .append(" uiscale=").append(Core.settings.getInt("uiscale", 100)).append("%\n");
        out.append("language: ").append(Core.bundle.getLocale()).append('\n');
        out.append("state: ").append(state.getState()).append(net.active() ? (net.client() ? " (client)" : " (host)") : " (local)");
        if(state.isGame()){
            out.append(" map=").append(state.map == null ? "?" : state.map.name())
                .append(" wave=").append(state.wave)
                .append(state.isCampaign() && state.rules.sector != null ? " sector=" + state.rules.sector.id : "");
        }
        out.append('\n');

        out.append("\n--- external mods (").append(mods.list().size).append(") ---\n");
        for(LoadedMod mod : mods.list()){
            out.append(mod.enabled() ? "[on]  " : "[off] ").append(mod.name).append(' ').append(mod.meta.version)
                .append(mod.meta.java ? " java" : " js").append(" state=").append(mod.state).append('\n');
        }

        if(includeSettings){
            Seq<String> keys = new Seq<>();
            for(String key : Core.settings.keys()) keys.add(key);
            keys.sort();
            out.append("\n--- settings (").append(keys.size).append(" keys, sensitive ones omitted) ---\n");
            for(String key : keys){
                if(isSensitive(key)) continue;
                Object value = Core.settings.get(key, null);
                out.append(key).append(" = ").append(describe(value)).append('\n');
            }
        }

        Fi data = Core.settings.getDataDirectory();
        Fi[] crashes = data.child("crashes").exists() ? data.child("crashes").list() : new Fi[0];
        Arrays.sort(crashes, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        out.append("\n--- crashes (").append(crashes.length).append(" total, newest ").append(Math.min(MAX_CRASHES, crashes.length)).append(" included) ---\n");
        for(int i = 0; i < Math.min(MAX_CRASHES, crashes.length); i++){
            out.append("## ").append(crashes[i].name()).append('\n');
            try{
                out.append(scrub(crashes[i].readString())).append('\n');
            }catch(Throwable e){
                out.append("(unreadable: ").append(e).append(")\n");
            }
        }

        Fi log = data.child("last_log.txt");
        out.append("\n--- last_log.txt");
        if(log.exists()){
            String text;
            try{
                text = log.readString();
            }catch(Throwable e){
                text = "(unreadable: " + e + ")";
            }
            if(text.length() > logTail){
                out.append(" (last ").append(logTail).append(" of ").append(text.length()).append(" chars)");
                text = text.substring(text.length() - logTail);
            }
            out.append(" ---\n").append(scrub(text));
        }else{
            out.append(" --- (missing)\n");
        }
        return out.toString();
    }

    /**
     * Чистит свободный текст (краши, хвост лога) от того, что в багрепорте не нужно: IPv4-адреса (серверы, в которых
     * играли - ключи настроек с адресами уже отфильтрованы, а в логе они остаются), Bearer-токены и домашняя папка ОС
     * (в пути имя пользователя). Версии вида 1.2.3 не задеваются - нужны ровно четыре октета.
     */
    static String scrub(String text){
        if(text == null || text.isEmpty()) return text;
        String out = IPV4.matcher(text).replaceAll("<ip>");
        out = BEARER.matcher(out).replaceAll("Bearer <redacted>");
        String home = System.getProperty("user.home");
        if(home != null && home.length() > 3){
            out = out.replace(home, "~").replace(home.replace('\\', '/'), "~");
        }
        return out;
    }

    static boolean isSensitive(String key){
        String k = key.toLowerCase(Locale.ROOT);
        for(String s : SENSITIVE){
            if(k.contains(s)) return true;
        }
        return false;
    }

    private static String describe(Object value){
        if(value == null) return "null";
        if(value instanceof byte[] b) return "byte[" + b.length + "]";
        String type = value.getClass().getSimpleName();
        String s = String.valueOf(value).replace('\n', ' ');
        if(s.length() > MAX_VALUE) s = s.substring(0, MAX_VALUE) + "…(" + s.length() + ")";
        return s + "  (" + type + ")";
    }

    /** Пишет полный отчёт в {@code <данные>/monolith-report.txt}; возвращает файл или null при ошибке. */
    public static @Nullable Fi writeToDataDir(boolean includeSettings){
        try{
            Fi f = Core.settings.getDataDirectory().child(FILE_NAME);
            f.writeString(build(includeSettings), false);
            return f;
        }catch(Throwable e){
            Log.err("[diag] failed to write report", e);
            return null;
        }
    }

    /** Диалог с кратким резюме и тремя действиями: буфер обмена / сохранить как / открыть папку. */
    public static class ReportDialog extends BaseDialog{
        private boolean includeSettings = true;

        public ReportDialog(){
            super("@client.sonka.report.title");
            addCloseButton();
            shown(this::setup);
        }

        private void setup(){
            cont.clear();
            Fi data = Core.settings.getDataDirectory();
            Fi log = data.child("last_log.txt");
            int crashes = data.child("crashes").exists() ? data.child("crashes").list().length : 0;

            cont.labelWrap(Core.bundle.get("client.sonka.report.hint")).width(460f).padBottom(8f).row();
            cont.table(t -> {
                t.left().defaults().left();
                t.add(Core.bundle.format("client.sonka.report.summary",
                    Version.clientVersion, Version.combined(), mods.list().size, crashes,
                    log.exists() ? Strings.autoFixed(log.length() / 1024f, 1) + " KB" : "-")).row();
            }).growX().padBottom(8f).row();

            cont.check("@client.sonka.report.settings", includeSettings, v -> includeSettings = v).left().padBottom(8f).row();

            cont.table(b -> {
                b.defaults().size(230f, 54f).pad(4f);
                b.button("@client.sonka.report.copy", Icon.copy, () -> {
                    Core.app.setClipboardText(build(includeSettings, CLIPBOARD_LOG_TAIL));
                    writeToDataDir(includeSettings);
                    ui.showInfoFade("@client.sonka.report.copied");
                });
                b.button("@client.sonka.report.save", Icon.save, () -> {
                    String text = build(includeSettings);
                    FileChooser.export("monolith-report", "txt", f -> f.writeString(text, false));
                });
                b.row();
                b.button("@client.sonka.report.write", Icon.file, () -> {
                    Fi f = writeToDataDir(includeSettings);
                    if(f != null) ui.showInfoFade(Core.bundle.format("client.sonka.report.written", f.absolutePath()));
                    else ui.showErrorMessage("@client.sonka.report.failed");
                });
                b.button("@data.openfolder", Icon.folder, () -> Core.app.openFolder(data.absolutePath()));
            }).row();
        }
    }
}
