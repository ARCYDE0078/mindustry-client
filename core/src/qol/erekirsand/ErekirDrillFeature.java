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
import qol.core.Feature;

import static mindustry.Vars.content;
import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.ui;
import static mindustry.Vars.world;

/**
 * Общая часть фич авторасстановки буров Эрекира (песок - {@link ErekirSandFeature}, настенная руда -
 * {@link ErekirOreFeature}): зажать клавишу, обвести прямоугольник, отпустить - в очередь стройки юнита ставится
 * линия: буры лицом к стене, дерево уток, собирающее добычу в одну выходную утку, и лучевые узлы, которые
 * связывают буры в одну сеть и тянутся к ближайшей уже стоящей. Ничего сверх этого не ставится: линия кончается
 * открытой уткой (к ближайшему ядру, если оно есть), дальше её ведёт игрок.
 * <p>
 * Сам расчёт - в {@link DrillPlanner} (чистый, без движка, проверяется стендом); здесь только то, что без движка
 * не сделать: снимок мира в массивы ({@link DrillSnapshot}), фоновый поток, очередь {@link BuildPlan}-ов.
 * Подклассы дают клавишу, блоки, префикс ключей локализации и донастройку параметров под свой бур.
 * Жеста для телефона нет (нужна клавиатура).
 */
public abstract class ErekirDrillFeature implements Feature{
    /** Запас вокруг рамки: сюда достают лучи узлов до чужой сети и сюда же можно ставить сами узлы. */
    static final int MARGIN = 24;
    /** Потолок площади рамки: дальше планировщик (Дейкстра по состояниям) уже заметно думает. */
    static final int MAX_ZONE_TILES = 4096;
    static final int MIN_ZONE_SIDE = 8;
    /** Бур (для руды выбирается при каждом запуске по настройке), утка и лучевой узел. */
    Block drillBlock, ductBlock, nodeBlock;

    boolean dragging = false;
    int dragStartX, dragStartY;
    /** Планирование идёт в фоновом потоке - второй запуск параллельно не нужен. */
    volatile boolean busy = false;

    /** Клавиша этой фичи. */
    abstract KeyBind key();

    /** Префикс ключей локализации и метка в логе: {@code qol.<tag>.busy} и т.д. */
    abstract String tag();

    /** Блок бура на этот запуск (null - не найден в этой версии игры). */
    abstract Block pickDrill();

    /** Донастроить параметры под свой бур (цель по скорости, размер, дальность луча...). */
    abstract void configure(DrillPlanner.Params params);

    /** Строка итога: что поставили, сколько добычи на выходе. */
    abstract String doneText(DrillPlanner.Result res, DrillPlanner.Params params);

    String bundle(){
        return "qol." + tag();
    }

    @Override
    public String titleKey(){
        return "qol.feature." + tag() + ".title";
    }

    @Override
    public void init(){
        ductBlock = content.block("duct");
        nodeBlock = content.block("beam-node");
        drillBlock = pickDrill();

        Events.on(WorldLoadEvent.class, e -> dragging = false);
        Events.run(Trigger.update, this::update);
        Events.run(Trigger.draw, this::draw);
    }

    @Override
    public void buildSettings(SettingsTable table){
        //кроме тумблера настроек нет - клавиша переназначается в стандартном экране «Управление»
    }

    boolean blocksReady(){
        return drillBlock != null && ductBlock != null && nodeBlock != null;
    }

    void update(){
        if(!state.isGame() || !isEnabled() || player == null){
            dragging = false;
            return;
        }

        if(!dragging){
            //hasKeyboard - игрок печатает (чат, поле ввода); hasDialog - открыто окно: клавиша не наша
            if(Core.input.keyTap(key()) && !Core.scene.hasKeyboard() && !Core.scene.hasDialog()){
                dragging = true;
                dragStartX = World.toTile(Core.input.mouseWorld().x);
                dragStartY = World.toTile(Core.input.mouseWorld().y);
            }
            return;
        }

        if(Core.input.keyRelease(key())){
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
            toast(bundle() + ".busy", "Planning is still running");
            return;
        }
        drillBlock = pickDrill(); //настройки могли смениться (большой бур, тип руды)
        if(!blocksReady()){
            toast(bundle() + ".noblocks", "Erekir blocks not found (is this an Erekir-capable game version?)");
            return;
        }
        Unit unit = player.unit();
        if(unit == null || !unit.canBuild()){
            toast(bundle() + ".cantbuild", "Your unit can't build");
            return;
        }
        String locked = lockedNames();
        if(!locked.isEmpty()){
            ui.showInfoToast(Core.bundle.format(bundle() + ".locked", locked), 4f);
            return;
        }

        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(world.width() - 1, x1);
        y1 = Math.min(world.height() - 1, y1);
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        if(w < MIN_ZONE_SIDE || h < MIN_ZONE_SIDE){
            ui.showInfoToast(Core.bundle.format(bundle() + ".small", MIN_ZONE_SIDE), 4f);
            return;
        }
        if(w * h > MAX_ZONE_TILES){
            ui.showInfoToast(Core.bundle.format(bundle() + ".big", MAX_ZONE_TILES), 4f);
            return;
        }

        DrillPlanner.Grid grid = snapshot(x0, y0, x1, y1);
        DrillPlanner.Params params = new DrillPlanner.Params();
        params.exitDir = exitDirTowardCore(x0, y0, x1, y1);
        if(nodeBlock instanceof BeamNode bn) params.nodeRange = Math.max(1, (int)bn.range);
        configure(params);

        busy = true;
        Thread t = new Thread(() -> {
            DrillPlanner.Result res = null;
            Throwable err = null;
            try{
                res = new DrillPlanner(grid, params).plan();
            }catch(Throwable e){
                err = e;
            }
            DrillPlanner.Result fres = res;
            Throwable ferr = err;
            //в главный поток: очередь юнита и мир трогаем только оттуда
            Core.app.post(() -> {
                busy = false;
                if(ferr != null){
                    Log.err("[" + tag() + "] planner failed", ferr);
                    ui.showInfoToast(Core.bundle.get(bundle() + ".crash", "Planner crashed - see log"), 4f);
                    return;
                }
                apply(fres, params);
            });
        }, "qol-" + tag() + "-planner");
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
        for(Block b : new Block[]{drillBlock, ductBlock, nodeBlock}){
            if(!b.unlockedNow()){
                if(sb.length() > 0) sb.append(", ");
                sb.append(b.localizedName);
            }
        }
        return sb.toString();
    }

    /** Мир -> массивы для планировщика (только главный поток). */
    DrillPlanner.Grid snapshot(int zx0, int zy0, int zx1, int zy1){
        Unit unit = player.unit();
        return DrillSnapshot.take(player.team(), ductBlock, unit == null ? null : unit.plans, zx0, zy0, zx1, zy1, MARGIN);
    }

    void apply(DrillPlanner.Result res, DrillPlanner.Params params){
        if(!state.isGame() || player == null) return;
        if(res.error != null){
            String key = switch(res.error){
                case "no-walls" -> bundle() + ".nowalls";
                case "no-room" -> bundle() + ".noroom";
                default -> bundle() + ".unreachable";
            };
            ui.showInfoToast(Core.bundle.get(key), 5f);
            return;
        }
        Unit unit = player.unit();
        if(unit == null || !unit.canBuild()) return;

        int rejected = 0;
        Team team = player.team();
        for(DrillPlanner.Item it : res.items){
            Block block = blockFor(it.kind);
            //мир мог измениться, пока считали в фоне (или на клетке стоит юнит): невалидное движок всё равно молча выкинет из очереди
            if(!Build.validPlace(block, team, it.x, it.y, it.rot)){
                rejected++;
                continue;
            }
            unit.addBuild(new BuildPlan(it.x, it.y, it.rot, block));
        }

        //главное - суммарная скорость бурения; остальное показываем только если что-то пошло не так
        StringBuilder msg = new StringBuilder(doneText(res, params));
        if(!res.powerOk) msg.append('\n').append(Core.bundle.get(bundle() + ".nopower"));
        else if(!res.gridLinked) msg.append('\n').append(Core.bundle.get(bundle() + ".nogrid"));
        if(!res.pipeOk) msg.append('\n').append(Core.bundle.get(bundle() + ".nopipe"));
        if(rejected > 0) msg.append('\n').append(Core.bundle.format(bundle() + ".rejected", rejected));
        ui.showInfoToast(msg.toString(), 5f);
    }

    Block blockFor(int kind){
        return switch(kind){
            case DrillPlanner.DRILL -> drillBlock;
            case DrillPlanner.DUCT -> ductBlock;
            default -> nodeBlock;
        };
    }

    void toast(String key, String fallback){
        ui.showInfoToast(Core.bundle.get(key, fallback), 3f);
    }
}
