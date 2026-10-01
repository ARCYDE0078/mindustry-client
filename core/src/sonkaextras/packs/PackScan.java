package sonkaextras.packs;

import arc.*;
import arc.files.*;
import arc.func.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.mod.*;
import mindustry.mod.Mods.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Классификация мода как ресурс-пака (текстуры / музыка / звуки) по его файлам. Работает над
 * абстрактным списком относительных путей, поэтому одинаково применима к установленному моду
 * (обход {@code mod.root} - папка или zip) и к дереву GitHub-репозитория из браузера модов.
 * <p>
 * Что считается, ровно по правилам движка ({@code Mods.packSprites} / {@code Mods.loadSync}):
 * <ul>
 *   <li>{@code sprites-override/*.png} - ПОДМЕНА ванильного спрайта по имени файла (движок
 *   пишет warn, если такого региона нет; мы считаем такие «мёртвыми»);</li>
 *   <li>{@code sprites/*.png} - НОВЫЕ спрайты с префиксом {@code modname-}: в v7+ они ничего не
 *   подменяют. Мод только из {@code sprites/} без контента - старый текстурпак времён v6,
 *   в этой версии он не работает, об этом честно сообщаем;</li>
 *   <li>любой другой файл вне спец-папок попадает в {@code Vars.tree} и подменяет внутренний
 *   ресурс с тем же путём: {@code music/*.ogg}, {@code sounds/**}, шейдеры, шрифты и т.п.
 *   Музыка/звуки считаются отдельно, остальное - «прочее»;</li>
 *   <li>{@code content/}, {@code scripts/}, Java (meta.java/main), {@code maps/} - признаки
 *   контент-мода: такой мод текстурпаком НЕ считается, даже если у него есть свои спрайты.</li>
 * </ul>
 * «Ванильный ли файл» для музыки/звуков/прочего проверяется через {@code Core.files.internal};
 * для спрайтов - через {@code Core.atlas.has}, как делает сам движок.
 */
public class PackScan{
    /** Папки, которые не идут в Vars.tree (см. Mods.specialFolders) + служебные. */
    private static final ObjectSet<String> special = ObjectSet.with("bundles", "sprites", "sprites-override", ".git", ".github", "content", "scripts", "maps");
    private static final ObjectSet<String> audioExt = ObjectSet.with("ogg", "mp3", "wav");
    private static final ObjectSet<String> skipFiles = ObjectSet.with("mod.hjson", "mod.json", "icon.png", "preview.png", "readme.md", "license", "license.md", "license.txt", ".gitignore", "classes.dex");

    /** Верхние папки, пути из которых стоит хранить в кэше проверки репозитория. */
    private static final ObjectSet<String> storeTop = ObjectSet.with("sprites", "sprites-override", "music", "sounds", "content", "scripts", "maps", "bundles", "shaders", "fonts", "ui", "cursors", "icons", "src");
    private static final ObjectMap<String, PackInfo> cache = new ObjectMap<>();

    private PackScan(){
    }

    public static class PackInfo{
        /** sprites-override/: подмены ванильных спрайтов (существующие регионы) и «мёртвые» (региона нет). */
        public final Seq<String> overrides = new Seq<>(), deadOverrides = new Seq<>();
        /** sprites/: новые спрайты (не подмены). */
        public int newSprites;
        /** music/ и sounds/: подмены ванильных файлов и новые файлы. */
        public final Seq<String> music = new Seq<>(), sounds = new Seq<>();
        public int newMusic, newSounds;
        /** Прочие подмены внутренних ресурсов (шейдеры, шрифты, ui...). */
        public final Seq<String> other = new Seq<>();
        /**
         * content/-файлы с именами ВАНИЛЬНОГО контента: ContentParser патчит существующий блок/юнит,
         * а не создаёт новый (так текстурпаки привязывают свои спрайты через drawer'ы, см.
         * Chocolate TexturePack). Контентом/кодом не считаются.
         */
        public final Seq<String> contentPatches = new Seq<>();
        /** Число языков в bundles/. */
        public int bundles;
        public boolean hasContent, hasScripts, hasJava, hasMaps;
        /** Путь/дерево прочитаны неполно (например, дерево GitHub усечено). */
        public boolean truncated;

        public boolean textures(){
            return overrides.size > 0 || deadOverrides.size > 0 || (newSprites > 0 && !code());
        }

        public boolean audio(){
            return music.size > 0 || sounds.size > 0 || newMusic > 0 || newSounds > 0;
        }

        public boolean code(){
            return hasContent || hasScripts || hasJava || hasMaps;
        }

        /** Ресурс-пак: что-то подменяет/добавляет из текстур или аудио и не является контент-модом. */
        public boolean isPack(){
            return !code() && (textures() || audio() || other.size > 0);
        }

        /** Только sprites/ без sprites-override/ - в v7+ ничего не подменяет. */
        public boolean legacySpritesOnly(){
            return newSprites > 0 && overrides.size == 0 && deadOverrides.size == 0 && !audio() && other.size == 0 && !code();
        }
    }

    /** Установленный мод; результат кэшируется по пути и времени изменения файла. */
    public static PackInfo of(LoadedMod mod){
        String key = mod.file.absolutePath() + "@" + mod.file.lastModified();
        PackInfo cached = cache.get(key);
        if(cached != null) return cached;

        Seq<String> paths = new Seq<>();
        try{
            String rootPath = mod.root.path();
            mod.root.walk(f -> {
                if(f.isDirectory()) return;
                String p = f.path();
                if(p.startsWith(rootPath)) p = p.substring(rootPath.length());
                while(p.startsWith("/")) p = p.substring(1);
                paths.add(p);
            });
        }catch(Throwable e){
            Log.err("[packs] failed to walk mod " + mod.name, e);
        }
        PackInfo info = scan(paths, mod.meta.java || mod.main != null || mod.meta.main != null);
        cache.put(key, info);
        return info;
    }

    public static void clearCache(){
        cache.clear();
    }

    /**
     * @param paths    относительные пути файлов (с '/'), без ведущего слеша
     * @param javaMeta мод объявлен как Java (meta.java / main) - для дерева репозитория передаём
     *                 наличие {@code build.gradle}/{@code src/}
     */
    public static PackInfo scan(Seq<String> paths, boolean javaMeta){
        PackInfo info = new PackInfo();
        info.hasJava = javaMeta;
        ObjectSet<String> langs = new ObjectSet<>();

        for(String raw : paths){
            String p = raw.replace('\\', '/');
            if(p.isEmpty()) continue;
            int slash = p.indexOf('/');
            String top = slash == -1 ? "" : p.substring(0, slash);
            String name = p.substring(p.lastIndexOf('/') + 1);
            String lower = name.toLowerCase(Locale.ROOT);
            String ext = lower.lastIndexOf('.') == -1 ? "" : lower.substring(lower.lastIndexOf('.') + 1);

            if(top.isEmpty()){
                //файлы в корне: мета, иконка, gradle/dex - признаки java
                if(lower.equals("build.gradle") || lower.equals("build.gradle.kts") || lower.equals("classes.dex") || ext.equals("jar")) info.hasJava = true;
                continue;
            }

            switch(top){
                case "sprites-override" -> {
                    if(!ext.equals("png")) continue;
                    String region = name.substring(0, name.length() - 4);
                    if(Core.atlas != null && Core.atlas.has(region)) info.overrides.add(region);
                    else info.deadOverrides.add(region);
                }
                case "sprites" -> {
                    if(ext.equals("png")) info.newSprites++;
                }
                case "content" -> {
                    if(!ext.equals("hjson") && !ext.equals("json")) continue;
                    String cname = name.substring(0, name.lastIndexOf('.'));
                    if(isVanillaContent(p, cname)) info.contentPatches.add(cname);
                    else info.hasContent = true;
                }
                case "scripts" -> info.hasScripts = true;
                case "maps" -> info.hasMaps = true;
                case "bundles" -> {
                    if(ext.equals("properties")) langs.add(lower);
                }
                case "src", "java", "kotlin" -> {
                    //исходники java-мода (дерево репозитория)
                    if(ext.equals("java") || ext.equals("kt")) info.hasJava = true;
                }
                case "music" -> {
                    if(!audioExt.contains(ext)) continue;
                    if(internalExists(p)) info.music.add(p.substring("music/".length()));
                    else info.newMusic++;
                }
                case "sounds" -> {
                    if(!audioExt.contains(ext)) continue;
                    if(internalExists(p)) info.sounds.add(p.substring("sounds/".length()));
                    else info.newSounds++;
                }
                default -> {
                    if(special.contains(top) || top.startsWith(".") || top.equals("META-INF")) continue;
                    if(skipFiles.contains(lower)) continue;
                    //классы java-мода: у вшитых пакетов те же классы есть и в нашем classpath,
                    //internalExists дал бы ложную «подмену»
                    if(ext.equals("class")){
                        info.hasJava = true;
                        continue;
                    }
                    if(ext.equals("java") || ext.equals("kt")) info.hasJava = true;
                    else if(internalExists(p)) info.other.add(p);
                }
            }
        }
        info.bundles = langs.size;
        info.overrides.sort();
        info.deadOverrides.sort();
        info.music.sort();
        info.sounds.sort();
        info.other.sort();
        info.contentPatches.sort();
        return info;
    }

    /**
     * content/&lt;тип&gt;s/&lt;имя&gt;.hjson - патч ванильного контента, если контент с таким именем есть
     * и он ванильный (Mods.loadContent берёт тип из имени папки, ContentParser.parse - по имени
     * файла ищет существующий через Vars.content.getByName).
     */
    private static boolean isVanillaContent(String path, String cname){
        if(mindustry.Vars.content == null) return false;
        String[] parts = path.split("/");
        if(parts.length < 3) return false;
        String folder = parts[1].toLowerCase(Locale.ROOT);
        for(mindustry.ctype.ContentType type : mindustry.ctype.ContentType.all){
            String lower = type.name().toLowerCase(Locale.ROOT);
            if(!folder.equals(lower + (lower.endsWith("s") ? "" : "s"))) continue;
            try{
                mindustry.ctype.Content c = mindustry.Vars.content.getByName(type, cname);
                return c != null && c.isVanilla();
            }catch(Throwable t){
                return false;
            }
        }
        return false;
    }

    private static boolean internalExists(String path){
        try{
            return Core.files != null && Core.files.internal(path).exists();
        }catch(Throwable t){
            return false;
        }
    }

    /**
     * Эвристика для листинга браузера (файлов не видно): не java/не scripts + ключевые слова в
     * имени/описании. Возвращает битовую маску {@link #TEXTURES}/{@link #MUSIC}/{@link #SOUNDS},
     * 0 = не похож на пак.
     */
    public static final int TEXTURES = 1, MUSIC = 2, SOUNDS = 4;

    private static final String[] textureWords = {"texture", "textur", "sprite", "resprite", "reskin", "retexture", "visual", "skin pack", "skins", "текстур", "спрайт", "рескин", "визуал"};
    private static final String[] musicWords = {"music", "ost", "soundtrack", "song", "track", "музык", "саундтрек", "трек"};
    private static final String[] soundWords = {"sound", "sfx", "audio", "звук"};

    public static int guess(ModListing mod){
        if(mod.hasJava || mod.hasScripts) return 0;
        String text = ((mod.name == null ? "" : mod.name) + " " + (mod.internalName == null ? "" : mod.internalName) + " " + (mod.description == null ? "" : mod.description)).toLowerCase(Locale.ROOT);
        int mask = 0;
        if(containsWord(text, textureWords)) mask |= TEXTURES;
        if(containsWord(text, musicWords)) mask |= MUSIC;
        if(containsWord(text, soundWords)) mask |= SOUNDS;
        return mask;
    }

    private static boolean containsWord(String text, String[] words){
        for(String w : words){
            int i = text.indexOf(w);
            while(i != -1){
                //«ost» внутри «most»/«cost»/«post» - не музыка: для коротких слов требуем границу слева
                boolean leftOk = w.length() > 4 || i == 0 || !Character.isLetter(text.charAt(i - 1));
                if(leftOk) return true;
                i = text.indexOf(w, i + 1);
            }
        }
        return false;
    }

    /** Бюджет значения-кэша в настройках: с запасом под лимит writeUTF (65535 байт) и служебные строки. */
    private static final int STORE_BUDGET_BYTES = 60_000;

    /** Результат проверки состава репозитория из браузера: кэш в настройках, чтобы не жечь лимит GitHub API. */
    public static @Nullable PackInfo cachedRemote(ModListing mod){
        String raw = Core.settings.getString(remoteKey(mod), null);
        if(raw == null) return null;
        try{
            Seq<String> paths = Seq.with(raw.split("\n")).select(s -> !s.isEmpty());
            boolean java = paths.remove("<java>");
            boolean truncated = paths.remove("<truncated>");
            PackInfo info = scan(paths, java);
            info.truncated = truncated;
            return info;
        }catch(Throwable t){
            return null;
        }
    }

    static String remoteKey(ModListing mod){
        return "sonka-packcheck-" + mod.repo.toLowerCase(Locale.ROOT);
    }

    /**
     * Запрашивает дерево репозитория (2 запроса: default_branch + git/trees recursive) и
     * классифицирует; результат кэшируется. Лимит GitHub без токена - 60 запросов/час, поэтому
     * только по кнопке, не массово.
     */
    public static void checkRemote(ModListing mod, Cons<PackInfo> done, Cons<String> error){
        String repo = mod.repo;
        Http.get("https://api.github.com/repos/" + repo)
            .error(e -> Core.app.post(() -> error.get(describeError(e))))
            .submit(res -> {
                String branch;
                try{
                    branch = Jval.read(res.getResultAsString()).getString("default_branch", "master");
                }catch(Throwable t){
                    Core.app.post(() -> error.get(t.getMessage()));
                    return;
                }
                Http.get("https://api.github.com/repos/" + repo + "/git/trees/" + branch + "?recursive=1")
                    .error(e -> Core.app.post(() -> error.get(describeError(e))))
                    .submit(res2 -> {
                        try{
                            Jval root = Jval.read(res2.getResultAsString());
                            Seq<String> paths = new Seq<>();
                            for(Jval v : root.get("tree").asArray()){
                                if("blob".equals(v.getString("type", ""))) paths.add(v.getString("path", ""));
                            }
                            boolean truncated = root.getBool("truncated", false);
                            boolean java = paths.contains(p -> p.endsWith(".java") || p.endsWith(".kt") || p.equals("build.gradle") || p.equals("build.gradle.kts"));
                            //в settings кладём только значимые пути (большие репозитории - тысячи файлов)
                            //ВАЖНО: значение настройки не может быть длиннее 65535 байт (arc Settings пишет через writeUTF) -
                            //при превышении сохранение settings.bin падает, файл удаляется и теряются ВСЕ настройки сессии.
                            //Раньше резали по числу путей (6000), а это сотни КБ; теперь режем по байтам.
                            StringBuilder body = new StringBuilder();
                            int stored = 0, bytes = 0;
                            boolean cut = false;
                            for(String p : paths){
                                int slash = p.indexOf('/');
                                String top = slash == -1 ? "" : p.substring(0, slash);
                                if(top.isEmpty() || storeTop.contains(top)){
                                    int len = p.getBytes(StandardCharsets.UTF_8).length + 1; // не java.nio...: локальная boolean java затеняет пакет
                                    if(stored++ > 6000 || bytes + len > STORE_BUDGET_BYTES){
                                        cut = true;
                                        break;
                                    }
                                    bytes += len;
                                    body.append(p).append('\n');
                                }
                            }
                            String store = (java ? "<java>\n" : "") + (truncated || cut ? "<truncated>\n" : "") + body;
                            Core.app.post(() -> {
                                Core.settings.put(remoteKey(mod), store);
                                PackInfo info = scan(paths, java);
                                info.truncated = truncated;
                                done.get(info);
                            });
                        }catch(Throwable t){
                            Core.app.post(() -> error.get(t.getMessage()));
                        }
                    });
            });
    }

    private static String describeError(Throwable e){
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        if(m.contains("403") || m.contains("429")) return Core.bundle.get("client.sonka.packs.ratelimit");
        return m;
    }
}
