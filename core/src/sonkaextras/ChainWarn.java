package sonkaextras;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.event.*;
import arc.struct.*;
import arc.util.*;
import mindustry.client.Spectate;
import mindustry.client.ui.*;
import mindustry.entities.units.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.storage.*;

import static mindustry.Vars.*;

/**
 * Уведомление о "висящем" начале цепочки труб/дактов/конвейеров: игрок протянул линию, а в её
 * первый сегмент (тот, В который должен входить груз) ничто не выдаёт - ни здание-источник, ни
 * другой транспорт, ни хотя бы план в очереди постройки.
 * <p>
 * Почему перехват прямо в {@code DesktopInput}, а не {@code LineConfirmEvent}: ваниль очищает
 * {@code linePlans} ДО {@code Events.fire(new LineConfirmEvent())}, событию содержимое линии уже
 * недоступно (bridge-to-core из-за этого держит целый identity-set {@code knownPlans} с ресинком
 * каждый тик - здесь код вшит в движок, так что снимок в одну строку до flushPlans дешевле и точнее).
 * Порядок планов в {@code linePlans} = порядок протяжки, а поток груза идёт по направлению протяжки,
 * поэтому вход цепочки - всегда ПЕРВЫЙ транспортный план линии, выход - последний.
 * <p>
 * Сам чек откладывается до реальной постройки первого сегмента ({@link BlockBuildEndEvent}): в
 * момент протяжки зданий ещё нет, а цепочку часто строят "от потребителя к источнику" - поэтому
 * источником считается и ещё не построенный план из очереди игрока. Уведомление информационное
 * (тост кликабелен - камера к месту, плюс мигающая рамка на пару секунд), одно на цепочку: pending
 * снимается с учёта при первом же срабатывании, протухает через минуту (линию отменили) и
 * сбрасывается при смене карты.
 * <p>
 * Сознательные упрощения (задокументированы, а не баги):
 * <ul>
 * <li>Проверка эвристическая в сторону "меньше ложных тревог": ненаправленный блок с
 *     items/liquids вплотную (роутер, сортер, насос, крафтер...) считается источником без запроса
 *     acceptItem - точная проверка требовала бы конкретного предмета и построенных соседей.
 *     Хранилища ({@link StorageBlock}: ядро, контейнеры) источником НЕ считаются - сами они ничего
 *     не выталкивают, конвейер от ядра без анлоадера реально не запитан.</li>
 * <li>Джанкшены (обычный/дактовый/жидкостный) - транзит: источник ищется по прямой за ними (с
 *     лимитом шагов), как груз реально и едет.</li>
 * <li>Если первый сегмент линии уже стоял (протяжка продолжила существующую цепочку), события
 *     постройки по нему не будет и уведомления нет - расширение готовой цепочки шумом не считаем.</li>
 * <li>Замороженные (freeze-queueing) линии пропускаются - они не строятся сейчас.</li>
 * <li>Только desktop: на мобиле линия уходит в selectPlans до отдельного подтверждения, там
 *     перехват не делался.</li>
 * </ul>
 * Отдельная опция {@link #settingEndKey} (по умолчанию выкл - шумно при стройке "навстречу")
 * аналогично проверяет ВЫХОД последнего сегмента: выдаёт ли он хоть куда-то.
 */
public final class ChainWarn{
    public static final String settingKey = "sonka-chain-warn";
    public static final String settingEndKey = "sonka-chain-warn-end";
    /**
     * Фильтр блоков для проверки ВЫХОДА (по просьбе sonka): предупреждение "выход в никуда"
     * срабатывает только для цепочек из выбранных здесь типов. Ключ отсутствует = выбраны ВСЕ
     * кандидаты (поведение до фичи). Проверку ВХОДА фильтр сознательно не трогает - просили
     * именно про выводы. Хранение/пикер - по образцу {@link LineRotate}.
     */
    public static final String endFilterKey = "sonka-chain-warn-end-blocks";

    /** Кэш выбранных для end-проверки имён блоков; null = ещё не разобран из настройки. */
    static ObjectSet<String> endSelected;

    /** Больше висящих проверок держать нет смысла - старейшие вытесняются. */
    static final int MAX_PENDING = 16;
    /** Планы могли отменить - через минуту проверка протухает без события постройки. */
    static final long PENDING_TTL_MS = 60_000;
    static final long HIGHLIGHT_MS = 3_000;
    /** Лимит прохода "за джанкшены" по прямой (защита от вырожденных стен джанкшенов). */
    static final int JUNCTION_WALK_LIMIT = 16;

    static class Pending{
        int pos;        //Point2.pack тайла сегмента
        Block block;    //что там должно построиться (построили другое - линию переиграли, молчим)
        boolean end;    //true = проверка выхода конца, false = проверка входа начала
        boolean liquid; //цепочка жидкостная (кондуиты), а не предметная
        long expires;
    }

    static class Highlight{
        float x, y;
        long until;
    }

    static final Seq<Pending> pending = new Seq<>();
    static final Seq<Highlight> highlights = new Seq<>();

    private ChainWarn(){
    }

    /** Вызывается из Main.kt до ClientLoadEvent - только вешает слушатели (см. комментарий там). */
    public static void init(){
        Events.on(ClientLoadEvent.class, e -> ModsSettings.section("modsec-sonkaextras", t -> {
            t.checkPref(settingKey, true);
            t.checkPref(settingEndKey, false);
            t.pref(new qol.core.ButtonSetting("sonka-chain-warn-end-configure", () -> new EndFilterDialog().show()));
            //пикер LineRotate живёт в ЭТОМ билдере: повторный вызов ModsSettings.section с тем же
            //именем добавил бы второй заголовок «Sonka Extras» (category() не дедупит), поэтому
            //секция строится в одном месте, а соседние фичи sonkaextras добавляются сюда
            t.pref(new qol.core.ButtonSetting("sonka-linerotate-configure", () -> new LineRotate.PickerDialog().show()));
            //на Android нет ни главного меню, ни паузы с кнопками «Features»/«Changelog» (они есть только в десктопных
            //MenuFragment/PausedDialog), а из FeaturesDialog открываются диалоги ВСЕХ вшитых модов - даём вход отсюда
            t.pref(new qol.core.ButtonSetting("sonka-features-open", () -> mindustry.client.ui.FeaturesDialog.INSTANCE.show()));
            t.pref(new qol.core.ButtonSetting("sonka-changelog-open", () -> mindustry.client.ui.ChangelogDialog.INSTANCE.show()));
            //автосейв на старте волны для кнопки «Повторить волну» (CampaignRetry)
            t.checkPref(CampaignRetry.autosaveKey, true);
            //метка над юнитом с ником последнего управлявшего (LastController): тогл + время показа
            t.checkPref(LastController.settingKey, true);
            t.checkPref(LastController.selfKey, true);
            t.sliderPref(LastController.timeoutKey, 0, 0, 600, 30, v -> v == 0 ? Core.bundle.get("client.sonka.lastcontroller.forever") : v + " s");
            //пер-панельный масштаб нативных HUD-панелей (PanelScale/ChatFragment.draw). Секция
            //Sonka Extras, а не вкладка Graphics: все sonka-фичи движка живут одной секцией, а поиск
            //настроек всё равно находит их с любой вкладки. Всё применяется вживую, без рестарта
            //changed-колбэк сбрасывает кэш PanelScale.scl - живое применение слайдера сохранено,
            //а act()-циклы обёрток не лазят в settings каждый кадр (перф)
            t.sliderPref(PanelScale.CHAT_KEY, 100, PanelScale.MIN, PanelScale.MAX, 5, v -> v + "%", v -> PanelScale.invalidate(PanelScale.CHAT_KEY));
            t.sliderPref(PanelScale.MINIMAP_KEY, 100, PanelScale.MIN, PanelScale.MAX, 5, v -> v + "%", v -> PanelScale.invalidate(PanelScale.MINIMAP_KEY));
            t.sliderPref(PanelScale.WAVES_KEY, 100, PanelScale.MIN, PanelScale.MAX, 5, v -> v + "%", v -> PanelScale.invalidate(PanelScale.WAVES_KEY));
            t.sliderPref(PanelScale.COREITEMS_KEY, 100, PanelScale.MIN, PanelScale.MAX, 5, v -> v + "%", v -> PanelScale.invalidate(PanelScale.COREITEMS_KEY));
            t.sliderPref(PanelScale.PALETTE_KEY, 100, PanelScale.MIN, PanelScale.MAX, 5, v -> v + "%", v -> PanelScale.invalidate(PanelScale.PALETTE_KEY));
            //кастомизация курсоров мыши (sonkaextras.cursors): масштаб применяется вживую, а
            //замена текстур/тинт/паки/редактор - в отдельном диалоге. Пересоздание курсоров идёт
            //только из changed-колбэка/диалога - на кадровый цикл нагрузки нет
            t.sliderPref(sonkaextras.cursors.CursorCustomizer.scaleKey, 100,
                sonkaextras.cursors.CursorCustomizer.MIN_PERCENT, sonkaextras.cursors.CursorCustomizer.MAX_PERCENT, 10,
                v -> v + "%", v -> sonkaextras.cursors.CursorCustomizer.rebuild());
            t.pref(new qol.core.ButtonSetting("sonka-cursor-configure", () -> new sonkaextras.cursors.CursorsDialog().show()));
            //выбор админ-панели HUD: Scheme Size (top-left) / Testing Utilities (bottom-left) / обе
            //(см. AdminPanel) - гейтит только видимость панелей, диалоги остаются в FeaturesDialog
            t.sliderPref(AdminPanel.KEY, AdminPanel.BOTH, 0, 2, 1, AdminPanel::label, v -> AdminPanel.invalidate());
            //гранулярный экспорт/импорт данных (sonkaextras.dataio): схемы/карты/моды/сейвы/кампания/
            //настройки по отдельности, мерж без затирания, бэкапы; ванильные кнопки в «Game Data» не трогаем
            t.pref(new qol.core.ButtonSetting("sonka-dataio-export", sonkaextras.dataio.DataIODialog::showExport));
            t.pref(new qol.core.ButtonSetting("sonka-dataio-import", sonkaextras.dataio.DataIODialog::showImport));
            //профили кампании (sonkaextras.campaign): несколько независимых кампаний с переключением
            t.pref(new qol.core.ButtonSetting("sonka-campaign-profiles", () -> new sonkaextras.campaign.CampaignProfilesDialog().show()));
            //теги карт (sonkaextras.maptags.MapTags): дублирующая точка входа в тот же диалог
            //управления тегами, что открывается карандашом у фильтра в списке карт - для тех, кто
            //прибирается в тегах не из контекста выбора карты (см. javadoc MapTagsDialog)
            t.pref(new qol.core.ButtonSetting("sonka-maptags-manage", () -> new sonkaextras.maptags.MapTagsDialog().show()));
            //единый список хоткеев всех пакетов (sonkaextras.hotkeys) и отчёт для багрепорта (DiagReport):
            //те же диалоги, что и в подменю Monolith главного меню - дублирующие точки входа
            t.pref(new qol.core.ButtonSetting("sonka-hotkeys-open", sonkaextras.hotkeys.HotkeysDialog::open));
            t.pref(new qol.core.ButtonSetting("sonka-diag-report", () -> new DiagReport.ReportDialog().show()));
            //авто-показ «что нового» после обновления (WhatsNew) + ручной вход в историю релизов
            t.checkPref(WhatsNew.autoKey, true);
            t.pref(new qol.core.ButtonSetting("sonka-whatsnew-history", WhatsNew::showAll));
        }));

        Events.on(WorldLoadEvent.class, e -> {
            pending.clear();
            highlights.clear();
        });

        Events.on(BlockBuildEndEvent.class, ChainWarn::onBuildEnd);
        Events.run(Trigger.drawOver, ChainWarn::draw);
    }

    /**
     * Снимок только что протянутой линии; зовётся из {@code DesktopInput} строго ДО
     * {@code flushPlans(linePlans, ...)} (после планы очищены). Ничего не строит и не мутирует.
     */
    public static void onLinePlaced(Seq<BuildPlan> plans){
        if(headless || player == null || !Core.settings.getBool(settingKey, true)) return;

        //первый/последний ТРАНСПОРТНЫЙ план: авто-вставленные над обрывами мосты (conveyorPlacement
        //у них false) началом/концом цепочки не считаются
        BuildPlan first = null, last = null;
        int segments = 0;
        for(BuildPlan plan : plans){
            Block b = plan.block;
            if(plan.breaking || b == null || !b.rotate || !b.conveyorPlacement || !(b.hasItems || b.hasLiquids)) continue;
            if(first == null) first = plan;
            last = plan;
            segments++;
        }
        //одиночный блок - не "цепочка", не шумим
        if(first == null || segments < 2) return;

        boolean liquid = first.block.hasLiquids && !first.block.hasItems;
        addPending(first, liquid, false);
        //end-проверка дополнительно гейтится пикером блоков (линия протяжки всегда из одного блока)
        if(Core.settings.getBool(settingEndKey, false) && endAllows(first.block)) addPending(last, liquid, true);
    }

    static void addPending(BuildPlan plan, boolean liquid, boolean end){
        Pending p = new Pending();
        p.pos = Point2.pack(plan.x, plan.y);
        p.block = plan.block;
        p.liquid = liquid;
        p.end = end;
        p.expires = Time.millis() + PENDING_TTL_MS;
        pending.add(p);
        if(pending.size > MAX_PENDING) pending.remove(0);
    }

    static void onBuildEnd(BlockBuildEndEvent e){
        if(pending.isEmpty() || e.breaking || e.tile == null || player == null || e.team != player.team()) return;

        int pos = e.tile.pos();
        long now = Time.millis();
        for(int i = 0; i < pending.size; i++){
            Pending p = pending.get(i);
            if(now > p.expires){
                pending.remove(i--);
                continue;
            }
            if(p.pos != pos) continue;
            //одно уведомление на цепочку: проверка снимается с учёта независимо от исхода
            pending.remove(i--);
            if(e.tile.block() != p.block || !Core.settings.getBool(settingKey, true)) continue;
            Building s = e.tile.build;
            if(s == null) continue;
            if(p.end ? !hasOutput(s, p.liquid) : !isFed(s, p.liquid)){
                warn(e.tile, p.end);
            }
        }
    }

    /** Выдаёт ли хоть что-то в построенное начало цепочки (здание, транзит за джанкшенами или план). */
    static boolean isFed(Building s, boolean liquid){
        for(int dir = 0; dir < 4; dir++){
            if(dir == s.rotation) continue; //спереди не кормят - там наш собственный выход
            if(fedFrom(s, dir, liquid)) return true;
        }
        return planFeeds(s, liquid);
    }

    /** Источник со стороны dir, с проходом по прямой сквозь джанкшены (груз едет так же). */
    static boolean fedFrom(Building s, int dir, boolean liquid){
        Building target = s;
        int cx = s.tile.x + Geometry.d4x(dir), cy = s.tile.y + Geometry.d4y(dir);
        for(int step = 0; step < JUNCTION_WALK_LIMIT; step++){
            Building n = world.build(cx, cy);
            if(n == null || n.team != s.team) return false;
            if(isJunction(n.block, liquid)){
                target = n;
                cx += Geometry.d4x(dir);
                cy += Geometry.d4y(dir);
                continue;
            }
            return feeds(n, target, liquid);
        }
        return false;
    }

    static boolean isJunction(Block b, boolean liquid){
        return liquid ? b instanceof LiquidJunction : (b instanceof Junction || b instanceof DuctJunction);
    }

    /** Выдаёт ли построенный сосед f в target (эвристика, см. javadoc класса). */
    static boolean feeds(Building f, Building target, boolean liquid){
        Block b = f.block;
        if(liquid ? !b.hasLiquids : !b.hasItems) return false;
        if(b instanceof StorageBlock) return false; //ядро/контейнер сами ничего не выталкивают
        if(b.rotate){
            if(f.front() == target) return true;
            //направленные router-подобные (дакт-роутер и родня) раздают не только вперёд, но и
            //вбок - не источник для нас только их зад
            return (b instanceof DuctRouter || b instanceof OverflowDuct || b instanceof StackRouter) && f.back() != target;
        }
        return true; //ненаправленный блок с содержимым вплотную (роутер, насос, крафтер...) - источник
    }

    /** Источник ещё не построен, но уже запланирован (цепочку строят от потребителя к источнику). */
    static boolean planFeeds(Building s, boolean liquid){
        if(player.unit() == null) return false;
        int tx = s.tile.x, ty = s.tile.y;
        for(BuildPlan plan : player.unit().plans()){
            Block b = plan.block;
            if(plan.breaking || b == null) continue;
            if(liquid ? !b.hasLiquids : !b.hasItems) continue;
            if(b instanceof StorageBlock) continue;
            if(b.rotate){
                //фронт-тайл плана (с учётом size, как BuildingComp.front()) должен попасть в начало цепочки
                int off = b.size / 2 + 1;
                if(plan.x + Geometry.d4x(plan.rotation) * off == tx && plan.y + Geometry.d4y(plan.rotation) * off == ty) return true;
            }else if(adjacentTo(plan, tx, ty)){
                return true;
            }
        }
        return false;
    }

    /** Примыкает ли план стороной (не углом) к тайлу (tx, ty). */
    static boolean adjacentTo(BuildPlan plan, int tx, int ty){
        Block b = plan.block;
        int x1 = plan.x + b.sizeOffset, y1 = plan.y + b.sizeOffset;
        int x2 = x1 + b.size - 1, y2 = y1 + b.size - 1;
        boolean inX = tx >= x1 && tx <= x2, inY = ty >= y1 && ty <= y2;
        return (inX && (ty == y1 - 1 || ty == y2 + 1)) || (inY && (tx == x1 - 1 || tx == x2 + 1));
    }

    /** Опция "проверять и конец": выдаёт ли построенный хвост цепочки хоть куда-то (здание или план). */
    static boolean hasOutput(Building s, boolean liquid){
        Building f = s.front();
        if(f != null && f.team == s.team && (liquid ? f.block.hasLiquids : f.block.hasItems)) return true;

        //потребителя ещё не построили, но он уже в очереди
        int fx = s.tile.x + Geometry.d4x(s.rotation), fy = s.tile.y + Geometry.d4y(s.rotation);
        if(player.unit() != null){
            for(BuildPlan plan : player.unit().plans()){
                Block b = plan.block;
                if(plan.breaking || b == null) continue;
                if(liquid ? !b.hasLiquids : !b.hasItems) continue;
                int x1 = plan.x + b.sizeOffset, y1 = plan.y + b.sizeOffset;
                if(fx >= x1 && fx < x1 + b.size && fy >= y1 && fy < y1 + b.size) return true;
            }
        }
        return false;
    }

    static void warn(Tile tile, boolean end){
        String msg = Core.bundle.format(end ? "client.sonka.chainwarn.end" : "client.sonka.chainwarn.start", tile.x, tile.y);
        //клиентский Toast (сам добавляется в сцену), кликабелен - камера к проблемному месту, тот же
        //паттерн, что у warn-тоста ConstructBlock
        Toast toast = new Toast(5f);
        toast.add(msg);
        toast.touchable = Touchable.enabled;
        float wx = tile.worldx(), wy = tile.worldy();
        toast.clicked(() -> Spectate.INSTANCE.spectate(new Vec2(wx, wy)));

        Highlight h = new Highlight();
        h.x = wx;
        h.y = wy;
        h.until = Time.millis() + HIGHLIGHT_MS;
        highlights.add(h);
    }

    /**
     * Мигающая рамка на проблемном тайле. Trigger.drawOver, а не draw: draw идёт внутри
     * world-render pass и рамка 1-тайлового блока оказалась бы под его же спрайтом (тот же вывод
     * задокументирован у qol DisconnectedPowerHighlighter, чей стиль рамки здесь и переиспользован).
     */
    static void draw(){
        if(highlights.isEmpty()) return;
        long now = Time.millis();
        highlights.removeAll(h -> now > h.until);
        if(highlights.isEmpty() || state.isMenu()) return;

        Lines.stroke(3f);
        Draw.color(Color.scarlet, 0.5f + Mathf.absin(Time.time, 6f, 0.35f));
        for(Highlight h : highlights){
            Lines.square(h.x, h.y, tilesize * 0.9f);
        }
        Draw.reset();
    }

    //---- фильтр блоков end-проверки (пикер по образцу LineRotate) ----

    /** Участвует ли блок цепочки в проверке выхода. Ключ отсутствует = все кандидаты. */
    static boolean endAllows(Block b){
        if(b == null) return false;
        if(endSelected == null) rebuildEndFilter();
        return endSelected.contains(b.name);
    }

    /** Кандидаты фильтра = ровно те блоки, из которых onLinePlaced собирает цепочки. */
    static Seq<Block> endCandidates(){
        return content.blocks().select(b ->
            b.rotate && b.conveyorPlacement && (b.hasItems || b.hasLiquids)
            && !b.isHidden() && b.uiIcon != null && b.uiIcon.found());
    }

    static void rebuildEndFilter(){
        endSelected = new ObjectSet<>();
        String raw = Core.settings.getString(endFilterKey, null);
        if(raw == null){
            //ключа нет = все кандидаты (поведение до появления фильтра)
            for(Block b : endCandidates()) endSelected.add(b.name);
        }else if(!raw.isEmpty()){
            for(String name : raw.split(",")) endSelected.add(name);
        }
    }

    static void saveEndFilter(){
        Seq<String> names = endSelected.toSeq();
        names.sort(); //стабильный порядок - settings.bin не «дребезжит» от порядка ObjectSet
        Core.settings.put(endFilterKey, names.toString(","));
    }

    static void toggleEndFilter(Block b){
        if(endSelected == null) rebuildEndFilter();
        if(!endSelected.add(b.name)) endSelected.remove(b.name);
        saveEndFilter();
    }

    /** Пикер блоков end-проверки: сетка иконок-тоглов, паттерн {@link LineRotate.PickerDialog}. */
    public static class EndFilterDialog extends mindustry.ui.dialogs.BaseDialog{
        public EndFilterDialog(){
            super("@client.sonka.chainwarn.filter.title");
            addCloseButton();
            shown(this::setup);
            onResize(this::setup);
        }

        private void setup(){
            cont.clear();
            if(endSelected == null) rebuildEndFilter();

            cont.add("@client.sonka.chainwarn.filter.hint").width(520f).wrap().pad(6f).row();

            cont.table(t -> {
                t.defaults().growX().height(44f).pad(2f);
                t.button("@client.sonka.linerotate.all", () -> {
                    for(Block b : endCandidates()) endSelected.add(b.name);
                    saveEndFilter();
                    setup();
                });
                t.button("@client.sonka.linerotate.none", () -> {
                    endSelected.clear();
                    saveEndFilter();
                    setup();
                });
            }).growX().row();

            Seq<Block> blocks = endCandidates();
            int cols = Mathf.clamp((int)(Core.graphics.getWidth() / arc.scene.ui.layout.Scl.scl(64f)) - 4, 4, 12);

            cont.pane(p -> {
                int i = 0;
                for(Block b : blocks){
                    arc.scene.ui.ImageButton btn = p.button(Tex.whiteui, mindustry.ui.Styles.clearTogglei, 40, () -> toggleEndFilter(b))
                        .size(56f).tooltip(b.localizedName).get();
                    //ImageButton копирует shared-стиль в конструкторе - мутировать getStyle() безопасно
                    btn.getStyle().imageUp = new arc.scene.style.TextureRegionDrawable(b.uiIcon);
                    btn.update(() -> btn.setChecked(endSelected != null && endSelected.contains(b.name)));
                    if(++i % cols == 0) p.row();
                }
            }).grow().pad(4f);
        }
    }
}
