package mi2u.ui;

import arc.*;
import arc.graphics.*;
import arc.math.geom.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mi2u.*;
import mi2u.ui.elements.*;
import mindustry.client.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;

import static mi2u.MI2UVars.*;
import static mindustry.Vars.*;

/**
 * Обзор карты: сколько на ней каких руд/полов/стен, клик по иконке облетает камерой по очереди все такие тайлы.
 * Плюс активные команды с их множителями и запрещённый контент.
 * Идея и раскладка взяты из MapInfoFrag клиента fk4b, но окно сделано на Mindow2 (перетаскивание/позиция общие с остальными).
 */
public class MapAnalyzerMindow extends Mindow2{
    private static final int perRow = 6;

    private ObjectMap<Item, IntSeq> orePositions = new ObjectMap<>();
    private ObjectMap<Block, IntSeq> floorPositions = new ObjectMap<>();
    private ObjectMap<Block, IntSeq> wallPositions = new ObjectMap<>();
    private final ObjectIntMap<Object> cycleIndices = new ObjectIntMap<>();

    private final Table resTable = new Table(), floorTable = new Table(), wallTable = new Table(), teamTable = new Table(), bannedTable = new Table();
    private boolean scanned, scanning;

    public MapAnalyzerMindow(){
        super("MapAnalyzer");
        setVisibleInGame();
        hasCloseButton = true;

        titlePane.defaults().height(buttonSize);
        titlePane.button("" + Iconc.refresh, textb, this::scanWorld).size(buttonSize).with(b -> MI2Utils.tooltip(b, "@mapanalyzer.rescan"));
        titlePane.add("@" + name + ".MI2U").labelAlign(Align.center).growX();

        Events.on(WorldLoadEvent.class, e -> {
            cycleIndices.clear();
            orePositions = new ObjectMap<>();
            floorPositions = new ObjectMap<>();
            wallPositions = new ObjectMap<>();
            scanned = false;
            if(!closed()) scanWorld();
        });
    }

    @Override
    public void setupCont(Table cont){
        cont.clear();
        cont.pane(Styles.noBarPane, p -> {
            p.top().left().defaults().growX().pad(2f).left();

            section(p, "@mapanalyzer.ores", resTable, () -> copySection("Resources", orePositions));
            section(p, "@mapanalyzer.floors", floorTable, () -> copySection("Floors", floorPositions));
            section(p, "@mapanalyzer.walls", wallTable, () -> copySection("Walls", wallPositions));

            p.table(Styles.black3, t -> {
                t.add("@mapanalyzer.teams").color(Pal.accent).pad(4f).row();
                t.add(teamTable).growX();
            }).row();

            p.table(Styles.black3, t -> {
                t.add("@mapanalyzer.banned").color(Pal.accent).pad(4f).row();
                t.add(bannedTable).growX().left();
            }).row();
        }).maxHeight(Core.graphics.getHeight() * 0.7f).width(320f).scrollX(false);

        if(!scanned && !scanning) scanWorld();
        else fillTables();
    }

    private void section(Table p, String title, Table body, Runnable copy){
        p.table(Styles.black3, t -> {
            t.button(title, Styles.cleart, copy::run).growX().pad(4f).row();
            t.add(body).growX();
        }).row();
    }

    private void scanWorld(){
        if(scanning || world == null || world.tiles == null) return;
        scanning = true;

        Threads.daemon(() -> {
            var ores = new ObjectMap<Item, IntSeq>();
            var floors = new ObjectMap<Block, IntSeq>();
            var walls = new ObjectMap<Block, IntSeq>();

            try{
                for(Tile tile : world.tiles){
                    Item drop = tile.drop();
                    if(drop != null) ores.get(drop, IntSeq::new).add(tile.pos());

                    Block b = tile.block();
                    if(b != Blocks.air && b.isStatic()) walls.get(b, IntSeq::new).add(tile.pos());

                    Block floor = tile.floor();
                    if(floor != null && floor != Blocks.air) floors.get(floor, IntSeq::new).add(tile.pos());
                }
            }catch(Throwable t){
                //карту могли выгрузить посреди скана - результат этого прохода уже не нужен
                Core.app.post(() -> scanning = false);
                return;
            }

            Core.app.post(() -> {
                orePositions = ores;
                floorPositions = floors;
                wallPositions = walls;
                scanned = true;
                scanning = false;
                fillTables();
            });
        });
    }

    private void fillTables(){
        resTable.clear();
        floorTable.clear();
        wallTable.clear();
        teamTable.clear();
        bannedTable.clear();

        fillIconTable(resTable, orePositions);
        fillIconTable(floorTable, floorPositions);
        fillIconTable(wallTable, wallPositions);
        buildTeams();
        buildBanned();
    }

    private <T extends UnlockableContent> void fillIconTable(Table table, ObjectMap<T, IntSeq> data){
        int count = 0;
        for(T content : data.keys().toSeq().sort(k -> -data.get(k).size)){
            IntSeq positions = data.get(content);
            table.button(b -> {
                b.image(content.uiIcon).size(22f);
                b.add(UI.formatAmount(positions.size)).fontScale(0.8f).padLeft(2f);
            }, Styles.cleart, () -> cycle(content, positions)).pad(2f);
            if(++count % perRow == 0) table.row();
        }
    }

    private void cycle(Object key, IntSeq positions){
        if(positions == null || positions.size == 0) return;

        int index = cycleIndices.get(key, 0);
        int pos = positions.get(index % positions.size);

        Spectate.INSTANCE.spectate(new Vec2(Point2.x(pos) * tilesize, Point2.y(pos) * tilesize));
        cycleIndices.put(key, index + 1);
    }

    private void buildTeams(){
        Seq<Team> active = new Seq<>();
        for(Team team : Team.all){
            var data = team.data();
            if(data != null && (!data.cores.isEmpty() || data.unitCount > 0)) active.add(team);
        }

        //колонки множителей показываем только если хоть у одной активной команды значение не 1
        var cols = new Seq<MulCol>();
        cols.add(new MulCol("BHp", r -> r.blockHealthMultiplier, state.rules.blockHealthMultiplier));
        cols.add(new MulCol("BDmg", r -> r.blockDamageMultiplier, state.rules.blockDamageMultiplier));
        cols.add(new MulCol("BSpd", r -> r.buildSpeedMultiplier, state.rules.buildSpeedMultiplier));
        cols.add(new MulCol("USpd", r -> r.unitBuildSpeedMultiplier, state.rules.unitBuildSpeedMultiplier));
        cols.add(new MulCol("UCost", r -> r.unitCostMultiplier, state.rules.unitCostMultiplier));
        cols.add(new MulCol("UHp", r -> r.unitHealthMultiplier, state.rules.unitHealthMultiplier));
        cols.add(new MulCol("UDmg", r -> r.unitDamageMultiplier, state.rules.unitDamageMultiplier));
        cols.add(new MulCol("UCras", r -> r.unitCrashDamageMultiplier, state.rules.unitCrashDamageMultiplier));

        var used = cols.select(c -> active.contains(t -> c.value(t) != 1f));
        boolean infRes = active.contains(this::infiniteResources);

        teamTable.table(h -> {
            h.defaults().pad(2f).fontScale(0.8f);
            h.add("[gray]Team").width(100f).left();
            for(var c : used) h.add("[gray]" + c.name).width(45f).center();
            h.add("[gray]" + Iconc.host).width(40f).center();
            h.add("[gray]" + Iconc.units).width(40f).center();
            if(infRes) h.add("[gray]InfRes").width(55f).center();
        }).growX().padBottom(2f).row();

        for(Team team : active){
            var data = team.data();
            teamTable.table(r -> {
                r.defaults().fontScale(0.85f).center();
                r.add(team.name).color(team.color).width(100f).left().ellipsis(true);
                for(var c : used) r.add(formatMul(c.value(team))).width(45f);
                r.add("" + data.cores.size).width(40f).color(Color.white);
                r.add("" + data.unitCount).width(40f).color(Color.white);
                if(infRes) r.add(infiniteResources(team) ? "[green]" + Iconc.ok : "[gray]" + Iconc.cancel).width(55f);
            }).growX().row();
        }
    }

    private boolean infiniteResources(Team team){
        var tr = state.rules.teams.get(team);
        return state.rules.infiniteResources || (tr != null && tr.infiniteResources);
    }

    private static String formatMul(float v){
        if(v == 1f) return "[gray]1.0";
        return (v > 1f ? "[green]" : "[scarlet]") + Strings.fixed(v, 1);
    }

    private void buildBanned(){
        bannedTable.top().left();
        boolean blocks = !state.rules.bannedBlocks.isEmpty(), units = !state.rules.bannedUnits.isEmpty();

        if(blocks){
            bannedTable.add("[lightgray]" + Core.bundle.get("mapanalyzer.blocks") + ": ").padRight(4f);
            int i = 0;
            for(Block b : state.rules.bannedBlocks){
                if(b == null) continue;
                bannedTable.image(b.uiIcon).size(20f).pad(1f);
                if(++i % 12 == 0) bannedTable.row().add();
            }
            bannedTable.row();
        }

        if(units){
            bannedTable.add("[lightgray]" + Core.bundle.get("mapanalyzer.units") + ": ").padRight(4f).padTop(blocks ? 4f : 0f);
            int i = 0;
            for(UnitType u : state.rules.bannedUnits){
                if(u == null) continue;
                bannedTable.image(u.uiIcon).size(20f).pad(1f);
                if(++i % 12 == 0) bannedTable.row().add();
            }
        }

        if(!blocks && !units) bannedTable.add("@mapanalyzer.nobans").color(Color.gray).fontScale(0.8f);
    }

    private <T extends UnlockableContent> void copySection(String title, ObjectMap<T, IntSeq> data){
        if(data.isEmpty()) return;

        var sb = new StringBuilder("-- ").append(title).append(" --\n");
        for(T content : data.keys().toSeq().sort(k -> -data.get(k).size)){
            sb.append(Fonts.getUnicodeStr(content.name)).append("x").append(data.get(content).size).append("; ");
        }

        Core.app.setClipboardText(sb.toString());
    }

    private class MulCol{
        final String name;
        final arc.func.Floatf<Rules.TeamRule> getter;
        final float global;

        MulCol(String name, arc.func.Floatf<Rules.TeamRule> getter, float global){
            this.name = name;
            this.getter = getter;
            this.global = global;
        }

        float value(Team team){
            var tr = state.rules.teams.get(team);
            return tr != null ? getter.get(tr) : global;
        }
    }
}
