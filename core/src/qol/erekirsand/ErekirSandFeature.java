package qol.erekirsand;

import arc.Core;
import arc.Events;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.input.KeyBind;
import arc.input.KeyCode;
import arc.util.Log;
import mindustry.core.World;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.blocks.power.BeamNode;
import mindustry.world.blocks.production.WallCrafter;
import qol.core.Feature;

import static mindustry.Vars.content;
import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.ui;
import static mindustry.Vars.world;

/**
 * Авторасстановка песчаных буров Эрекира: зажать клавишу, обвести прямоугольник у песчаной скалы, отпустить -
 * в очередь стройки юнита ставится линия: скальные дробители ({@code cliff-crusher}) лицом к стене, дерево
 * уток, собирающее песок в одну выходную утку, и лучевые узлы ({@code beam-node}), которые связывают
 * дробители в одну сеть и тянутся к ближайшей уже стоящей.
 * <p>
 * Печи, роутеры и т.п. фича НЕ ставит: линия просто кончается открытой уткой, смотрящей в одну сторону (к
 * ближайшему ядру, если оно есть), и перед ней оставлено свободное место - куда вести песок дальше, решает игрок.
 * <p>
 * Сам расчёт - в {@link SandPlanner} (чистый, без движка, проверяется стендом {@code devtest/run.sh}); здесь
 * только то, что без движка не сделать: снимок мира в массивы ({@link SandSnapshot}), фоновый поток, очередь
 * {@link BuildPlan}-ов.
 * <p>
 * Что НЕ делается (сознательно, первая версия): настенные руды (плазменный бур) - следующий шаг; жеста для
 * телефона нет (нужна клавиатура).
 */
public class ErekirSandFeature implements Feature{
    static final KeyBind sandKey = KeyBind.add("erekir-sand-drills", KeyCode.slash, "erekir-sand"); //K в клиенте занят ванильным auto_mine, а свободных букв не осталось - «/»

    /** Запас вокруг рамки: сюда достают лучи узлов до чужой сети и сюда же можно ставить сами узлы. */
    static final int MARGIN = 24;
    /** Потолок площади рамки: дальше планировщик (Дейкстра по состояниям) уже заметно думает. */
    static final int MAX_ZONE_TILES = 4096;
    static final int MIN_ZONE_SIDE = 8;
    /**
     * Сколько песка набирать: на 3 кремниевые дуговые печи, которые игрок поставит сам. Печь - GenericCrafter
     * siliconArcFurnace: consumeItems(sand 4, graphite 1), craftTime 50 тиков => 4.8/с; одна утка тянет 15/с, как раз 3 печи.
     */
    static final float TARGET_SAND = 3 * 4f * 60f / 50f;

    Block crusherBlock, ductBlock, nodeBlock;

    boolean dragging = false;
    int dragStartX, dragStartY;
    /** Планирование идёт в фоновом потоке - второй запуск параллельно не нужен. */
    volatile boolean busy = false;

    @Override
    public String id(){
        return "erekir-sand";
    }

    @Override
    public String titleKey(){
        return "qol.feature.erekir-sand.title";
    }

    @Override
    public void init(){
        crusherBlock = content.block("cliff-crusher");
        ductBlock = content.block("duct");
        nodeBlock = content.block("beam-node");

        Events.on(WorldLoadEvent.class, e -> dragging = false);
        Events.run(Trigger.update, this::update);
        Events.run(Trigger.draw, this::draw);
    }

    @Override
    public void buildSettings(SettingsTable table){
        //кроме тумблера настроек нет - клавиша переназначается в стандартном экране «Управление»
    }

    boolean blocksReady(){
        return crusherBlock != null && ductBlock != null && nodeBlock != null;
    }

    void update(){
        if(!state.isGame() || !isEnabled() || player == null){
            dragging = false;
            return;
        }

        if(!dragging){
            //hasKeyboard - игрок печатает (чат, поле ввода); hasDialog - открыто окно: клавиша не наша
            if(Core.input.keyTap(sandKey) && !Core.scene.hasKeyboard() && !Core.scene.hasDialog()){
                dragging = true;
                dragStartX = World.toTile(Core.input.mouseWorld().x);
                dragStartY = World.toTile(Core.input.mouseWorld().y);
            }
            return;
        }

        if(Core.input.keyRelease(sandKey)){
            dragging = false;
            int endX = World.toTile(Core.input.mouseWorld().x);
            int endY = World.toTile(Core.input.mouseWorld().y);
            start(Math.min(dragStartX, endX), Math.min(dragStartY, endY), Math.max(dragStartX, endX), Math.max(dragStartY, endY));
        }
    }

    void draw(){
        if(!dragging) return;

        int endX = World.toTile(Core.input.mouseWorld().x);
        int endY = World.toTile(Core.input.mouseWorld().y);
        float x = Math.min(dragStartX, endX) * tilesize - tilesize / 2f;
        float y = Math.min(dragStartY, endY) * tilesize - tilesize / 2f;
        float w = (Math.abs(endX - dragStartX) + 1) * tilesize;
        float h = (Math.abs(endY - dragStartY) + 1) * tilesize;

        //тот же слот Layer.overlayUI, что у ванильной рамки выделения: поверх блоков и юнитов
        Draw.draw(Layer.overlayUI, () -> {
            Draw.color(Pal.accent);
            Lines.stroke(1f);
            Lines.rect(x, y, w, h);
            Draw.reset();
        });
    }

    // ------------------------------------------------------------------------------------------

    void start(int x0, int y0, int x1, int y1){
        if(busy){
            toast("qol.erekir-sand.busy", "Planning is still running");
            return;
        }
        if(!blocksReady()){
            toast("qol.erekir-sand.noblocks", "Erekir blocks not found (is this an Erekir-capable game version?)");
            return;
        }
        Unit unit = player.unit();
        if(unit == null || !unit.canBuild()){
            toast("qol.erekir-sand.cantbuild", "Your unit can't build");
            return;
        }
        String locked = lockedNames();
        if(!locked.isEmpty()){
            ui.showInfoToast(Core.bundle.format("qol.erekir-sand.locked", locked), 4f);
            return;
        }

        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(world.width() - 1, x1);
        y1 = Math.min(world.height() - 1, y1);
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        if(w < MIN_ZONE_SIDE || h < MIN_ZONE_SIDE){
            ui.showInfoToast(Core.bundle.format("qol.erekir-sand.small", MIN_ZONE_SIDE), 4f);
            return;
        }
        if(w * h > MAX_ZONE_TILES){
            ui.showInfoToast(Core.bundle.format("qol.erekir-sand.big", MAX_ZONE_TILES), 4f);
            return;
        }

        SandPlanner.Grid grid = snapshot(x0, y0, x1, y1);
        SandPlanner.Params params = new SandPlanner.Params();
        params.targetSand = TARGET_SAND;
        params.exitDir = exitDirTowardCore(x0, y0, x1, y1);
        if(crusherBlock instanceof WallCrafter wc) params.drillTime = wc.drillTime;
        if(nodeBlock instanceof BeamNode bn) params.nodeRange = Math.max(1, (int)bn.range);

        busy = true;
        toast("qol.erekir-sand.planning", "Planning sand drills...");
        Thread t = new Thread(() -> {
            SandPlanner.Result res = null;
            Throwable err = null;
            try{
                res = new SandPlanner(grid, params).plan();
            }catch(Throwable e){
                err = e;
            }
            SandPlanner.Result fres = res;
            Throwable ferr = err;
            //в главный поток: очередь юнита и мир трогаем только оттуда
            Core.app.post(() -> {
                busy = false;
                if(ferr != null){
                    Log.err("[erekir-sand] planner failed", ferr);
                    ui.showInfoToast(Core.bundle.get("qol.erekir-sand.crash", "Sand planner crashed - see log"), 4f);
                    return;
                }
                apply(fres, params);
            });
        }, "qol-erekir-sand-planner");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Куда смотреть выходу: сторона света (0 вправо, 1 вверх, 2 влево, 3 вниз), в которой лежит ближайшее своё ядро
     * относительно центра рамки, - песок обычно нужен на базе. -1: ядра нет или оно внутри рамки, тогда планировщик
     * выберет самое дешёвое направление.
     */
    int exitDirTowardCore(int x0, int y0, int x1, int y1){
        float cx = (x0 + x1 + 1) / 2f * tilesize, cy = (y0 + y1 + 1) / 2f * tilesize;
        Building core = state.teams.closestCore(cx, cy, player.team());
        if(core == null) return -1;
        int tx = core.tileX(), ty = core.tileY();
        if(tx >= x0 && tx <= x1 && ty >= y0 && ty <= y1) return -1;
        float dx = core.x - cx, dy = core.y - cy;
        if(Math.abs(dx) >= Math.abs(dy)) return dx >= 0 ? 0 : 2;
        return dy >= 0 ? 1 : 3;
    }

    String lockedNames(){
        StringBuilder sb = new StringBuilder();
        for(Block b : new Block[]{crusherBlock, ductBlock, nodeBlock}){
            if(!b.unlockedNow()){
                if(sb.length() > 0) sb.append(", ");
                sb.append(b.localizedName);
            }
        }
        return sb.toString();
    }

    /** Мир -> массивы для планировщика (только главный поток). */
    SandPlanner.Grid snapshot(int zx0, int zy0, int zx1, int zy1){
        Unit unit = player.unit();
        return SandSnapshot.take(player.team(), ductBlock, unit == null ? null : unit.plans, zx0, zy0, zx1, zy1, MARGIN);
    }

    void apply(SandPlanner.Result res, SandPlanner.Params params){
        if(!state.isGame() || player == null) return;
        if(res.error != null){
            String key = switch(res.error){
                case "no-walls" -> "qol.erekir-sand.nowalls";
                case "no-room" -> "qol.erekir-sand.noroom";
                default -> "qol.erekir-sand.unreachable";
            };
            ui.showInfoToast(Core.bundle.get(key), 5f);
            return;
        }
        Unit unit = player.unit();
        if(unit == null || !unit.canBuild()) return;

        int queued = 0, rejected = 0;
        Team team = player.team();
        for(SandPlanner.Item it : res.items){
            Block block = blockFor(it.kind);
            //мир мог измениться, пока считали в фоне (или на клетке стоит юнит): невалидное движок всё равно молча выкинет из очереди
            if(!Build.validPlace(block, team, it.x, it.y, it.rot)){
                rejected++;
                continue;
            }
            unit.addBuild(new BuildPlan(it.x, it.y, it.rot, block));
            queued++;
        }

        String rate = String.format("%.1f", res.sandRate);
        String need = String.format("%.1f", params.targetSand);
        StringBuilder msg = new StringBuilder(Core.bundle.format("qol.erekir-sand.done", res.crushers, res.ducts, res.nodes, rate, need));
        msg.append('\n').append(Core.bundle.format("qol.erekir-sand.exit", res.exitX, res.exitY, Core.bundle.get("qol.erekir-sand.dir." + res.exitDir)));
        if(res.sandRate < params.targetSand * 0.999f) msg.append('\n').append(Core.bundle.get("qol.erekir-sand.partial"));
        if(!res.powerOk) msg.append('\n').append(Core.bundle.get("qol.erekir-sand.nopower"));
        else if(!res.gridLinked) msg.append('\n').append(Core.bundle.get("qol.erekir-sand.nogrid"));
        if(rejected > 0) msg.append('\n').append(Core.bundle.format("qol.erekir-sand.rejected", rejected));
        ui.showInfoToast(msg.toString(), 7f);
        Log.info("[erekir-sand] queued @ plans (rejected @)\n@", queued, rejected, res.ascii);
    }

    Block blockFor(int kind){
        return switch(kind){
            case SandPlanner.CRUSHER -> crusherBlock;
            case SandPlanner.DUCT -> ductBlock;
            default -> nodeBlock;
        };
    }

    void toast(String key, String fallback){
        ui.showInfoToast(Core.bundle.get(key, fallback), 3f);
    }
}
