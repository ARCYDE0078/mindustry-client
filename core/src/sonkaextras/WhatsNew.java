package sonkaextras;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.core.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import static mindustry.Vars.*;

/**
 * «Что нового» после обновления: при первом запуске сборки {@code custom-bN}, если в настройках
 * запомнена сборка {@code custom-bM} с M &lt; N, подтягивает с GitHub релизы нашего канала
 * (M, N] и показывает их заметки одним диалогом. Заметки релиза пишет CI
 * ({@code .github/workflows/release-custom.yml}) из коммитов между тегами - т.е. «что нового»
 * это буквально сообщения коммитов ветки, без ручного ведения changelog'а.
 * <p>
 * Почему не локальный файл {@code changelog}: его надо править руками к каждому релизу, а релизы
 * у нас вешаются на каждый пуш тега (и авто-синк апстрима тоже тегает) - файл отставал бы сразу.
 * Ручной changelog остаётся в {@link mindustry.client.ui.ChangelogDialog} как сводка фич, а
 * отсюда в него ведёт кнопка истории релизов.
 * <p>
 * Правила показа: dev-сборка (clientVersion не {@code custom-b*}) - ничего; первый запуск вообще
 * (ключ {@link #seenKey} пуст) - только запомнить текущую сборку, не спамить после установки;
 * сеть недоступна - тихо, ключ НЕ обновляется, попробуем при следующем запуске; подходящих
 * релизов в ответе нет (например, релиз удалён) - запомнить и замолчать. Ключ обновляется на
 * текущую сборку в момент показа диалога. Авто-показ отключается настройкой {@link #autoKey}.
 */
public class WhatsNew{
    /** Номер последней сборки, чьи заметки пользователь уже видел (или с которой начал). */
    public static final String seenKey = "monolith-seen-build";
    /** Показывать ли диалог автоматически после обновления. */
    public static final String autoKey = "monolith-whatsnew-auto";
    /** Префикс имён релизов нашего канала (см. BeControl.checkUpdate). */
    static final String channel = "custom-b";
    /** Репозиторий по умолчанию, когда ни настройка updateurl, ни запечённый Version.updateUrl не заданы (dev-сборка). */
    static final String fallbackRepo = "ARCYDE0078/mindustry-client";
    /** Сколько релизов запрашивать за раз: с запасом на пропущенные номера, но одной страницей. */
    private static final int perPage = 30;

    private WhatsNew(){
    }

    /** Вызывается из Main.kt до ClientLoadEvent - только вешает слушатель. */
    public static void init(){
        Events.on(ClientLoadEvent.class, e -> {
            //на секунду позже: главное меню уже построено, а диалог апдейтера (BeControl) успевает
            //показаться первым, если есть ещё более новая сборка - тогда наш поверх него
            Time.runTask(60f, WhatsNew::checkOnStartup);
        });
    }

    /**
     * Порядковый номер сборки из имени релиза. Две схемы тегов: старая {@code custom-b12} → 12 и текущая датовая
     * {@code custom-b2026.09.25} / {@code custom-b2026.09.25.2} (повтор в тот же день) → 2026092500 + n
     * (год*10^6 + месяц*10^4 + день*10^2 + n). Датовые номера всегда больше старых порядковых, так что
     * сравнение "новее/старее" между схемами работает. Всё остальное → -1.
     * Раньше датовые теги давали -1, и диалог "что нового" молча не показывался вообще.
     */
    public static int buildNumber(String name){
        if(name == null || !name.startsWith(channel)) return -1;
        String rest = name.substring(channel.length()).trim();
        if(rest.indexOf('.') == -1) return Strings.parseInt(rest, -1);

        String[] parts = rest.split("\\.");
        if(parts.length < 3 || parts.length > 4) return -1;
        int y = Strings.parseInt(parts[0], -1), m = Strings.parseInt(parts[1], -1), d = Strings.parseInt(parts[2], -1);
        int n = parts.length == 4 ? Strings.parseInt(parts[3], -1) : 0;
        if(y < 2000 || y > 2100 || m < 1 || m > 12 || d < 1 || d > 31 || n < 0 || n > 99) return -1;
        return ((y * 100 + m) * 100 + d) * 100 + n;
    }

    public static String repo(){
        String r = Core.settings.getString("updateurl", "");
        if(r == null || r.isEmpty()) r = Version.updateUrl;
        if(r == null || r.isEmpty()) r = fallbackRepo;
        return r;
    }

    static void checkOnStartup(){
        int cur = buildNumber(Version.clientVersion);
        if(cur < 0) return;
        int seen = Core.settings.getInt(seenKey, -1);
        if(seen < 0){
            Core.settings.put(seenKey, cur);
            return;
        }
        if(cur <= seen) return;
        if(!Core.settings.getBool(autoKey, true)){
            Core.settings.put(seenKey, cur);
            return;
        }
        fetch(releases -> {
            Seq<Release> fresh = releases.select(r -> r.build > seen && r.build <= cur);
            if(fresh.isEmpty()){
                Core.settings.put(seenKey, cur);
                return;
            }
            Core.settings.put(seenKey, cur);
            new ReleasesDialog(fresh, true).show();
        }, e -> Log.warn("[whatsnew] failed to fetch releases: @", e.getMessage()));
    }

    /** Вся история релизов канала (кнопка в диалоге changelog'а). */
    public static void showAll(){
        ui.loadfrag.show("@loading");
        fetch(releases -> {
            ui.loadfrag.hide();
            if(releases.isEmpty()){
                ui.showInfo("@client.sonka.whatsnew.none");
                return;
            }
            new ReleasesDialog(releases, false).show();
        }, e -> {
            ui.loadfrag.hide();
            ui.showErrorMessage(Core.bundle.format("client.sonka.whatsnew.error", e.getMessage()));
        });
    }

    /** Асинхронно: релизы канала custom-b*, новые первыми; колбэки на главном потоке. */
    static void fetch(Cons<Seq<Release>> done, Cons<Throwable> error){
        Http.get("https://api.github.com/repos/" + repo() + "/releases?per_page=" + perPage)
            .error(e -> Core.app.post(() -> error.get(e)))
            .submit(res -> {
                Seq<Release> out = new Seq<>();
                try{
                    for(Jval v : Jval.read(res.getResultAsString()).asArray()){
                        Release r = new Release();
                        r.name = v.getString("name", v.getString("tag_name", ""));
                        r.build = buildNumber(r.name);
                        if(r.build < 0) continue;
                        r.date = v.getString("published_at", "");
                        if(r.date.length() >= 10) r.date = r.date.substring(0, 10);
                        r.url = v.getString("html_url", "");
                        r.body = v.getString("body", "");
                        out.add(r);
                    }
                }catch(Throwable t){
                    Core.app.post(() -> error.get(t));
                    return;
                }
                out.sort(r -> -r.build);
                Core.app.post(() -> done.get(out));
            });
    }

    public static class Release{
        public String name = "", date = "", url = "", body = "";
        public int build = -1;
    }

    /** Список релизов с заметками; {@code fresh} = показ после обновления (другой заголовок). */
    public static class ReleasesDialog extends BaseDialog{
        public ReleasesDialog(Seq<Release> releases, boolean fresh){
            super(fresh ? "@client.sonka.whatsnew.title" : "@client.sonka.whatsnew.history");
            addCloseButton();
            if(releases.any() && !releases.first().url.isEmpty()){
                String url = releases.first().url;
                buttons.button("@client.sonka.whatsnew.github", Icon.github, () -> {
                    if(!Core.app.openURI(url)){
                        ui.showErrorMessage("@linkfail");
                        Core.app.setClipboardText(url);
                    }
                }).size(210f, 64f);
            }

            int cur = buildNumber(Version.clientVersion);
            cont.pane(p -> {
                p.top().left();
                if(fresh){
                    p.labelWrap(Core.bundle.format("client.sonka.whatsnew.hint", Version.clientVersion)).width(560f).padBottom(10f).row();
                }
                for(Release r : releases){
                    p.table(head -> {
                        head.left();
                        head.add(r.name).color(Pal.accent).left();
                        if(r.build == cur) head.add(" " + Core.bundle.get("client.sonka.whatsnew.current")).color(Color.lightGray).left();
                        head.add().growX();
                        head.add(r.date).color(Color.gray).right();
                    }).growX().padTop(6f).row();
                    p.image().color(Pal.accent).height(3f).growX().padBottom(4f).row();
                    p.add(renderBody(r.body)).growX().padBottom(10f).row();
                }
            }).grow().get().setScrollingDisabled(true, false);
        }

        /**
         * Упрощённый рендер заметок: строки «- »/«* » - пункты, с отступом - подпункты, «## » -
         * подзаголовок, прочее - как есть. Ровно тот формат, что пишет CI; сторонний markdown не
         * обещаем.
         */
        static Table renderBody(String body){
            Table t = new Table();
            t.left().defaults().left().growX();
            float w = 560f;
            if(body == null || body.trim().isEmpty()){
                t.labelWrap("@client.sonka.whatsnew.nobody").color(Color.gray).width(w).row();
                return t;
            }
            for(String raw : body.replace("\r", "").split("\n")){
                if(raw.trim().isEmpty()) continue;
                String line = raw.replace("\t", "    ");
                int indent = 0;
                while(indent < line.length() && line.charAt(indent) == ' ') indent++;
                String s = line.trim();
                if(s.startsWith("## ") || s.startsWith("# ")){
                    t.add(s.substring(s.indexOf(' ') + 1)).color(Pal.accent).padTop(4f).row();
                }else if(s.startsWith("- ") || s.startsWith("* ")){
                    String text = s.substring(2).trim();
                    if(indent >= 2){
                        t.labelWrap("      ◦ " + text).color(Color.lightGray).width(w).row();
                    }else{
                        t.labelWrap("  • " + text).width(w).row();
                    }
                }else{
                    t.labelWrap(s).width(w).row();
                }
            }
            return t;
        }
    }
}
