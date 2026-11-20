package qol.forcebuildschematic;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.input.KeyCode;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Eachable;
import arc.util.Time;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.input.Binding;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import qol.core.Feature;
import qol.core.QueueCoordination;
import qol.ui.QolWindow;

import java.util.HashSet;
import java.util.Set;

import static mindustry.Vars.control;
import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.ui;
import static mindustry.Vars.world;

/**
 * Ported from the standalone Force Build Schematic script mod (JS). Hold Ctrl and click while a
 * schematic (or a single block) is held: your own buildings that stand in the way (the footprint of every
 * plan plus the whole bounding box) get queued for demolition first, then - once the site is actually
 * clear - the plans get queued for real. A plain click still places normally, exactly like vanilla.
 * <p>
 * Доработки sonka:
 * <ul>
 *   <li>diff со стоящим: точно такой же блок (тот же тип/позиция/поворот) не сносится и не строится заново -
 *       только донастраивается конфиг; блок, который ваниль и так заменяет «по месту» (конвейер на конвейер),
 *       тоже не сносится;</li>
 *   <li>ghost-превью планов показывается СРАЗУ после клика, а не после сноса (ждём чистого места, но рисуем
 *       сразу);</li>
 *   <li>самолечение: если план сноса потерялся (балдер выкидывает невалидные планы из очереди, после смерти
 *       очередь пуста) - он ставится заново, а блок, который не удаётся снести после нескольких попыток,
 *       отбрасывается, чтобы остальная схема всё же построилась, а не висела вечно;</li>
 *   <li>Ctrl+клик с одиночным блоком в руках = то же самое для одного блока.</li>
 * </ul>
 */
public class ForceBuildSchematicFeature implements Feature{
    /**
     * Empirically-tuned pixel nudge for the Ctrl-held highlight only (not the actual demolish/build
     * logic, which works in tile coordinates and needs no correction) - carried over from the original
     * script mod, which found the naive tile-center formula rendered a few pixels off.
     */
    static final float HIGHLIGHT_OFFSET_X = -4f, HIGHLIGHT_OFFSET_Y = -4f;

    /** Раз в сколько мс проверять, не потерялся ли план сноса. */
    static final long HEAL_INTERVAL_MS = 500;
    /** Сколько раз подряд можно ставить заново план сноса одного и того же блока, прежде чем счесть его неснимаемым. */
    static final int MAX_HEALS = 4;

    /** Экземпляр для вызова из DesktopInput: включена ли фича (чтобы ваниль не начинала линию под Ctrl). */
    static ForceBuildSchematicFeature instance;

    boolean mouseWasDown = false;
    Pending pending;
    long lastHealMs;

    @Override
    public String id(){
        return "force-build-schematic";
    }

    @Override
    public String titleKey(){
        return "qol.feature.force-build-schematic.title";
    }

    @Override
    public boolean hasWindow(){
        return false;
    }

    @Override
    public QolWindow window(){
        return null;
    }

    @Override
    public void init(){
        instance = this;
        Events.run(Trigger.draw, this::draw);
        Events.run(Trigger.update, this::update);
    }

    @Override
    public void buildSettings(SettingsTable table){
    }

    /**
     * Ctrl + ЛКМ с блоком/схемой в руках обрабатывает эта фича. DesktopInput зовёт это, чтобы ваниль не ставила
     * в тот же клик свою копию (мимо ожидания сноса) и не начинала линию.
     */
    public static boolean handlesCtrlClick(){
        ForceBuildSchematicFeature f = instance;
        if(f == null || !f.isEnabled() || state.isMenu() || player == null || player.dead()) return false;
        return f.ctrlDown() && (control.input.block != null || !control.input.selectPlans.isEmpty());
    }

    boolean ctrlDown(){
        return Core.input.keyDown(KeyCode.controlLeft) || Core.input.keyDown(KeyCode.controlRight);
    }

    //----------------------------------------------------------------------------------------------- отрисовка

    void draw(){
        if(!isEnabled() || state.isMenu() || player == null || player.team() == null) return;

        if(pending != null) drawPendingGhost(pending);

        if(!ctrlDown()) return;

        Seq<BuildPlan> selectPlans = control.input.selectPlans;
        Draw.color(Color.purple, 0.4f);
        if(!selectPlans.isEmpty()){
            for(BuildPlan p : selectPlans){
                int size = p.block != null ? p.block.size : 1;
                for(Tile t : footprintTiles(p.x, p.y, size)) highlight(t);
            }
        }else if(control.input.block != null && !Core.scene.hasMouse()){
            int cx = control.input.tileX(Core.input.mouseX()), cy = control.input.tileY(Core.input.mouseY());
            for(Tile t : footprintTiles(cx, cy, control.input.block.size)) highlight(t);
        }
        Draw.reset();
    }

    void highlight(Tile t){
        float wx = t.x * tilesize + tilesize / 2f + HIGHLIGHT_OFFSET_X;
        float wy = t.y * tilesize + tilesize / 2f + HIGHLIGHT_OFFSET_Y;
        Fill.square(wx, wy, tilesize / 2f);
    }

    /** Показывает планы, которые встанут после сноса, сразу - как обычные призраки плана. */
    void drawPendingGhost(Pending pend){
        Seq<BuildPlan> plans = pend.plans;
        if(plans.isEmpty()) return;
        Eachable<BuildPlan> list = cons -> {
            for(int i = 0; i < plans.size; i++) cons.get(plans.get(i));
        };
        float topAlpha = 0.24f + arc.math.Mathf.absin(Time.globalTime, 6f, 0.28f);

        Draw.z(Layer.plans);
        for(int i = 0; i < plans.size; i++){
            BuildPlan plan = plans.get(i);
            if(plan.block == null) continue;
            plan.animScale = 1f;
            plan.block.drawPlan(plan, list, true, 0.85f, false);
        }
        for(int i = 0; i < plans.size; i++){
            BuildPlan plan = plans.get(i);
            if(plan.block == null) continue;
            Draw.reset();
            Draw.mixcol(Pal.accent, topAlpha);
            Draw.alpha(1f);
            plan.block.drawPlanConfigTop(plan, plans);
        }
        Draw.reset();
    }

    //---------------------------------------------------------------------------------------------- логика

    void update(){
        //kept in sync unconditionally, ahead of every early return below, so it can never get stuck
        //true if this feature is disabled or the player dies mid-sequence
        QueueCoordination.forceBuildPending = pending != null;

        boolean mouseDown = Core.input.keyDown(KeyCode.mouseLeft);
        boolean mouseJustPressed = mouseDown && !mouseWasDown;
        mouseWasDown = mouseDown;

        if(!isEnabled()){
            pending = null;
            return;
        }
        if(state.isMenu() || player == null || player.team() == null){
            pending = null;
            return;
        }
        if(player.dead()) return; //очередь потеряна вместе с юнитом; после возрождения самолечение вернёт планы сноса

        Unit unit = player.unit();

        if(pending != null){
            if(unit == null){ pending = null; return; }
            //Q (очистить очередь) отменяет и ожидающую схему, иначе самолечение упрямо ставило бы снос заново
            if(Core.input.keyTap(Binding.clearBuilding)){
                pending = null;
                return;
            }
            tickPending(unit);
            return;
        }

        if(!ctrlDown() || !mouseJustPressed || Core.scene.hasMouse()) return;
        if(unit == null) return;

        Seq<BuildPlan> selectPlans = control.input.selectPlans;
        Seq<BuildPlan> previewPlans = new Seq<>();
        if(!selectPlans.isEmpty()){
            for(BuildPlan p : selectPlans){
                previewPlans.add(new BuildPlan(p.x, p.y, p.rotation, p.block, p.config));
            }
            selectPlans.clear();
        }else if(control.input.block != null){
            Block block = control.input.block;
            int cx = control.input.tileX(Core.input.mouseX()), cy = control.input.tileY(Core.input.mouseY());
            BuildPlan one = new BuildPlan(cx, cy, control.input.rotation, block);
            if(block.saveConfig) one.config = block.lastConfig;
            previewPlans.add(one);
        }
        if(previewPlans.isEmpty()) return;

        start(previewPlans, unit);
    }

    /** Считает diff со стоящим, ставит на снос только нужное и создаёт ожидание (или сразу строит, если сносить нечего). */
    void start(Seq<BuildPlan> previewPlans, Unit unit){
        ObjectSet<Building> keep = new ObjectSet<>();    //уже стоят точно как в схеме - не трогаем
        ObjectSet<Building> replace = new ObjectSet<>(); //ваниль заменит по месту (конвейер на конвейер) - не сносим
        Seq<BuildPlan> toBuild = new Seq<>();

        for(BuildPlan p : previewPlans){
            if(p.block == null) continue;
            Building same = matchingExisting(p);
            if(same != null){
                keep.add(same);
                control.input.queueExistingPlanConfig(p); //только конфиг (ссылка моста, лазеры, массивы), если отличается
                continue;
            }
            toBuild.add(p);
            if(Build.validPlace(p.block, player.team(), p.x, p.y, p.rotation)){
                for(Tile t : footprintTiles(p.x, p.y, p.block.size)){
                    if(t.build != null && t.build.team == player.team()) replace.add(t.build);
                }
            }
        }

        Set<Long> seen = new HashSet<>();
        Seq<Tile> demolishTiles = new Seq<>();

        for(BuildPlan p : toBuild){
            for(Tile t : footprintTiles(p.x, p.y, p.block.size)) tryDemolish(t.x, t.y, unit, seen, demolishTiles, keep, replace);
        }

        if(!previewPlans.isEmpty()){
            int minX = previewPlans.get(0).x, maxX = previewPlans.get(0).x;
            int minY = previewPlans.get(0).y, maxY = previewPlans.get(0).y;
            for(BuildPlan p : previewPlans){
                minX = Math.min(minX, p.x);
                maxX = Math.max(maxX, p.x);
                minY = Math.min(minY, p.y);
                maxY = Math.max(maxY, p.y);
            }
            for(int wx = minX; wx <= maxX; wx++){
                for(int wy = minY; wy <= maxY; wy++) tryDemolish(wx, wy, unit, seen, demolishTiles, keep, replace);
            }
        }

        if(demolishTiles.isEmpty()){
            for(BuildPlan p : toBuild) unit.addBuild(new BuildPlan(p.x, p.y, p.rotation, p.block, p.config));
            return;
        }

        pending = new Pending(toBuild, demolishTiles);
        lastHealMs = Time.millis();
    }

    /** Свой достроенный блок с тем же типом, позицией и поворотом, что у плана (иначе null). */
    Building matchingExisting(BuildPlan p){
        Tile tile = world.tile(p.x, p.y);
        if(tile == null) return null;
        Building b = tile.build;
        if(b == null || b instanceof ConstructBuild || b.team != player.team()) return null;
        if(tile.block() != p.block || b.tileX() != p.x || b.tileY() != p.y) return null;
        if(p.block.rotate && b.rotation != p.rotation) return null;
        return b;
    }

    void tickPending(Unit unit){
        Pending pend = pending;

        //1) что ещё мешает; у кого потерялся план сноса
        int blocked = 0;
        Seq<Building> lost = null;
        Seq<Tile> gone = null;
        for(Tile t : pend.tiles){
            Building build = t.build;
            if(build != null && build.team == player.team()){
                blocked++;
                if(!hasBreakPlan(unit, build)){
                    if(lost == null) lost = new Seq<>();
                    lost.add(build);
                }
            }else{
                if(gone == null) gone = new Seq<>();
                gone.add(t);
            }
        }
        if(gone != null) for(Tile t : gone) pend.tiles.remove(t, true);

        //2) нечего сносить - строим то, что ждало
        if(blocked == 0){
            for(BuildPlan p : pend.plans){
                unit.addBuild(new BuildPlan(p.x, p.y, p.rotation, p.block, p.config));
            }
            if(pend.abandoned > 0) ui.showInfoFade(Core.bundle.format("qol.forcebuild.abandoned", pend.abandoned));
            pending = null;
            return;
        }

        //3) самолечение: потерянный план сноса ставим заново, но не бесконечно
        long now = Time.millis();
        if(lost != null && now - lastHealMs >= HEAL_INTERVAL_MS){
            lastHealMs = now;
            for(Building b : lost){
                int tries = pend.heals.get(b.tile, 0) + 1;
                pend.heals.put(b.tile, tries);
                if(tries > MAX_HEALS){
                    pend.tiles.remove(b.tile, true); //не сносится - не ждём его, строим остальное
                    pend.abandoned++;
                    continue;
                }
                BuildPlan breakPlan = new BuildPlan(b.tile.x, b.tile.y);
                breakPlan.breaking = true;
                unit.addBuild(breakPlan);
            }
        }
    }

    static boolean hasBreakPlan(Unit unit, Building b){
        for(int i = 0; i < unit.plans.size; i++){
            BuildPlan q = unit.plans.get(i);
            if(q.breaking && q.x == b.tile.x && q.y == b.tile.y) return true;
        }
        return false;
    }

    void tryDemolish(int wx, int wy, Unit unit, Set<Long> seen, Seq<Tile> demolishTiles, ObjectSet<Building> keep, ObjectSet<Building> replace){
        Tile tile = world.tile(wx, wy);
        if(tile == null) return;

        Building build = tile.build;
        if(build == null || build.team != player.team()) return;
        if(keep.contains(build) || replace.contains(build)) return;

        long key = (long)build.tile.x << 32 | (build.tile.y & 0xFFFFFFFFL);
        if(!seen.add(key)) return;

        demolishTiles.add(build.tile);

        BuildPlan breakPlan = new BuildPlan(build.tile.x, build.tile.y);
        breakPlan.breaking = true;
        unit.addBuild(breakPlan);
    }

    static Seq<Tile> footprintTiles(int px, int py, int size){
        Seq<Tile> out = new Seq<>();
        int off = (size - 1) / 2;
        for(int ddx = 0; ddx < size; ddx++){
            for(int ddy = 0; ddy < size; ddy++){
                Tile t = world.tile(px - off + ddx, py - off + ddy);
                if(t != null) out.add(t);
            }
        }
        return out;
    }

    static class Pending{
        final Seq<BuildPlan> plans;
        final Seq<Tile> tiles;
        /** Сколько раз план сноса этого блока приходилось ставить заново. */
        final ObjectIntMap<Tile> heals = new ObjectIntMap<>();
        int abandoned;

        Pending(Seq<BuildPlan> plans, Seq<Tile> tiles){
            this.plans = plans;
            this.tiles = tiles;
        }
    }
}
