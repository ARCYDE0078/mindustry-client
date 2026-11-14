package mindustrytool.features.smartupgrade;

import java.util.Optional;

import arc.Core;
import arc.Events;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.ui.Dialog;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Scaling;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.StateChangeEvent;
import mindustry.game.EventType.TapEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.distribution.DirectionBridge;
import mindustry.world.blocks.distribution.DirectionBridge.DirectionBridgeBuild;
import mindustry.world.blocks.distribution.DirectionLiquidBridge;
import mindustry.world.blocks.distribution.Duct;
import mindustry.world.blocks.distribution.DuctRouter;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild;
import mindustry.world.blocks.distribution.Junction;
import mindustry.world.blocks.distribution.OverflowDuct;
import mindustry.world.blocks.distribution.OverflowGate;
import mindustry.world.blocks.distribution.Router;
import mindustry.world.blocks.distribution.Sorter;
import mindustry.world.blocks.distribution.StackConveyor;
import mindustry.world.blocks.liquid.Conduit;
import mindustry.world.blocks.liquid.LiquidBridge;
import mindustry.world.blocks.liquid.LiquidBridge.LiquidBridgeBuild;
import mindustry.world.blocks.liquid.LiquidJunction;
import mindustry.world.blocks.liquid.LiquidRouter;
import mindustry.world.blocks.defense.Wall;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.BeamDrill;
import mindustrytool.features.Feature;
import mindustrytool.features.FeatureMetadata;
import mindustrytool.services.TapListener;

public class SmartUpgradeFeature implements Feature {
    /* ключи настроек: свои (mindustrytool.*), как у остальных вшитых фич - у sonka они уже сохранены,
     * а апстримовый Signal/ConfigGroup здесь не нужен: вся логика читает Core.settings */
    public static final String KEY_MAX_UPDATES = "mindustrytool.smart-upgrade.max-updates";
    public static final String KEY_ONLY_SAME_TYPE = "mindustrytool.smart-upgrade.only-same-type";
    public static final String KEY_TRAVERSE_BRIDGES = "mindustrytool.smart-upgrade.traverse-bridges";

    public static final int DEFAULT_MAX_UPDATES = 500;

    private Table currentMenu;
    private Tile selectedTile;

    /* снимок настроек на время одной операции: BFS по цепочке дёргает их на каждом узле,
     * а читать Core.settings 500+ раз за проход незачем */
    private int maxUpdates = DEFAULT_MAX_UPDATES;
    private boolean onlySameType = false;
    private boolean traverseBridges = true;

    /* перф: metadata неизменяема, а isEnabled()/UI зовут getMetadata() каждый кадр -
     * ленивая мемоизация вместо новой цепочки builder+Drawable на каждый вызов */
    private FeatureMetadata cachedMetadata;

    @Override
    public FeatureMetadata getMetadata() {
        if (cachedMetadata == null)
            cachedMetadata = FeatureMetadata.builder()
                .name("feature.smart-upgrade")
                .description("feature.smart-upgrade.description")
                .icon(Icon.up)
                .quickAccess(true)
                .build();
        return cachedMetadata;
    }

    @Override
    public Optional<Dialog> setting() {
        return Optional.of(new SmartUpgradeSettingDialog());
    }

    public static int getMaxUpdates() {
        return Core.settings.getInt(KEY_MAX_UPDATES, DEFAULT_MAX_UPDATES);
    }

    public static boolean isOnlySameType() {
        return Core.settings.getBool(KEY_ONLY_SAME_TYPE, false);
    }

    public static boolean isTraverseBridges() {
        return Core.settings.getBool(KEY_TRAVERSE_BRIDGES, true);
    }

    private void loadOptions() {
        maxUpdates = Math.max(1, getMaxUpdates());
        onlySameType = isOnlySameType();
        traverseBridges = isTraverseBridges();
    }

    @Override
    public void init() {
        TapListener.getInstance().registerHoldListener(300, 10, null, (tile, data) -> {
            if (!isEnabled() || tile == null || tile.build == null || Vars.player == null) {
                return;
            }
            //чужие/дереликтные постройки не трогаем: план на них всё равно не станет «своим» апгрейдом
            if (tile.team() != Vars.player.team()) {
                return;
            }
            if (currentMenu == null) {
                if (getGroup(tile.block()) != BlockGroup.NONE) {
                    showMenu(tile);
                }
            }
        });

        Events.on(TapEvent.class, e -> {
            if (!isEnabled()) {
                return;
            }

            if (currentMenu != null) {
                if (e.tile == selectedTile) {
                    closeMenu();
                    return;
                }

                closeMenu();
            }
        });

        Events.on(StateChangeEvent.class, e -> {
            if (e.to == State.menu) {
                closeMenu();
            }
        });

        //Esc / «назад» на телефоне закрывает меню, не дожидаясь тапа мимо (как в апстриме)
        Events.run(Trigger.update, () -> {
            if (currentMenu == null) {
                return;
            }
            if (!isEnabled() || Vars.state == null || !Vars.state.isGame()
                    || Core.input.keyTap(KeyCode.escape) || Core.input.keyTap(KeyCode.back)) {
                closeMenu();
            }
        });
    }

    @Override
    public void onDisable() {
        closeMenu();
    }

    private void closeMenu() {
        if (currentMenu != null) {
            currentMenu.remove();
            currentMenu = null;
            selectedTile = null;
        }
    }

    /* Мосты вынесены из CONVEYOR/CONDUIT в свои группы: «улучшить» мост на конвейер (или трубу) нельзя,
     * а вот цепочку мостов целиком (мост-конвейер -> фазовый) - можно, и конфиг-ссылка при этом сохраняется */
    private enum BlockGroup {
        CONVEYOR, CONDUIT, ITEM_BRIDGE, LIQUID_BRIDGE, WALL, DRILL, NONE
    }

    private static BlockGroup getGroup(Block block) {
        if (block == null)
            return BlockGroup.NONE;
        //LiquidBridge наследует ItemBridge, поэтому жидкостные проверяем первыми
        if (block instanceof LiquidBridge || block instanceof DirectionLiquidBridge)
            return BlockGroup.LIQUID_BRIDGE;
        if (block instanceof ItemBridge || block instanceof DirectionBridge)
            return BlockGroup.ITEM_BRIDGE;
        if (block instanceof Conveyor || block instanceof StackConveyor || block instanceof Duct)
            return BlockGroup.CONVEYOR;
        if (block instanceof Conduit)
            return BlockGroup.CONDUIT;
        if (block instanceof Wall)
            return BlockGroup.WALL;
        if (block instanceof Drill || block instanceof BeamDrill)
            return BlockGroup.DRILL;

        return BlockGroup.NONE;
    }

    private boolean unlocked(Block block) {
        return block.unlockedNowHost() && block.placeablePlayer && block.environmentBuildable() &&
                block.supportsEnv(Vars.state.rules.env);
    }

    /* Кандидат на замену: та же группа и размер. Для мостов ещё и то же «семейство»: у ItemBridge конфиг -
     * Point2-ссылка, у DirectionBridge (duct-мост) ссылки нет, и подмена одного другим теряла бы связь. */
    private boolean isCandidate(Block current, Block block) {
        return current != block
                && getGroup(block) == getGroup(current)
                && block.size == current.size
                && (block instanceof DirectionBridge) == (current instanceof DirectionBridge)
                && unlocked(block);
    }

    private void showMenu(Tile tile) {
        selectedTile = tile;
        currentMenu = new Table(Styles.black6);
        currentMenu.visible(() -> Vars.ui.hudfrag != null && Vars.ui.hudfrag.shown);
        currentMenu.touchable = arc.scene.event.Touchable.enabled;

        currentMenu.update(() -> {
            if (selectedTile == null || selectedTile.block() == null || selectedTile.build == null
                    || Vars.player == null || selectedTile.team() != Vars.player.team()
                    || getGroup(selectedTile.block()) == BlockGroup.NONE) {
                closeMenu();
                return;
            }

            //от центра постройки, а не от тапнутой клетки: у 2x2/3x3 они не совпадают
            Vec2 pos = Core.camera.project(selectedTile.build.x, selectedTile.build.y);
            float x = pos.x;
            float y = pos.y + selectedTile.block().size * Vars.tilesize * 3;

            //апстрим-фикс: у края экрана меню уезжало за границу и кнопки было не нажать
            float w = currentMenu.getWidth();
            float h = currentMenu.getHeight();
            x = Mathf.clamp(x, w / 2f + 8f, Core.graphics.getWidth() - w / 2f - 8f);
            y = Mathf.clamp(y, 8f, Core.graphics.getHeight() - h - 8f);

            currentMenu.setPosition(x, y, Align.bottom | Align.center);
        });

        int i = 0;

        /* Build.validPlace тут больше не проверяем (апстрим убрал): при апгрейде поверх своей же
         * постройки он отсекал вполне допустимые замены, а настоящую проверку делает plan.placeable()
         * на каждой клетке цепочки */
        for (var block : Vars.content.blocks()) {
            if (isCandidate(tile.block(), block)) {
                addUpgradeButton(currentMenu, tile, block);
                if (++i % 5 == 0) {
                    currentMenu.row();
                }
            }
        }

        if (i > 0 && i % 5 == 0) {
            currentMenu.row();
        }
        addDeleteButton(currentMenu, tile);

        Vars.ui.hudGroup.addChild(currentMenu);
        Timer.schedule(() -> {
            if (currentMenu != null) {
                currentMenu.toFront();
            }
        }, 5f);
        currentMenu.pack();
    }

    private void addUpgradeButton(Table table, Tile tile, Block targetBlock) {
        table.button(b -> b.image(targetBlock.uiIcon).scaling(Scaling.fit), Styles.clearNonei, () -> {
            Vars.control.input.isBuilding = false;
            Core.app.post(() -> {
                int count = upgradeChain(tile, targetBlock);
                if (count > 0) {
                    Vars.ui.hudfrag.showToast(Icon.up,
                            Core.bundle.format("feature.smart-upgrade.upgraded", count, targetBlock.localizedName));
                }
            });
            closeMenu();
        }).size(48f).pad(4).tooltip(targetBlock.localizedName);
    }

    private void addDeleteButton(Table table, Tile tile) {
        table.button(b -> b.image(Icon.trash).scaling(Scaling.fit).color(Pal.remove), Styles.clearNonei, () -> {
            Vars.control.input.isBuilding = false;
            Core.app.post(() -> {
                int count = deleteChain(tile);
                if (count > 0) {
                    Vars.ui.hudfrag.showToast(Icon.trash,
                            Core.bundle.format("feature.smart-upgrade.deleted", count));
                }
            });
            closeMenu();
        }).size(48f).pad(4).tooltip(Core.bundle.get("feature.smart-upgrade.delete", "Delete"));
    }

    private boolean hasBuilder() {
        if (Vars.player == null || Vars.player.unit() == null) {
            Vars.ui.hudfrag.showToast(Icon.warning,
                    Core.bundle.get("feature.smart-upgrade.no-builder", "No builder unit available."));
            return false;
        }
        return true;
    }

    private int upgradeChain(Tile startTile, Block targetBlock) {
        if (startTile == null || startTile.build == null || targetBlock == null || !hasBuilder()) {
            return 0;
        }

        loadOptions();

        Team team = Vars.player.team();
        Block startBlock = startTile.block();
        BlockGroup group = getGroup(startBlock);
        if (group == BlockGroup.NONE) {
            return 0;
        }

        ObjectSet<Tile> visited = new ObjectSet<>();
        Seq<Tile> queue = new Seq<>();

        queue.add(startTile);
        visited.add(startTile);

        int updates = 0;

        while (!queue.isEmpty() && updates < maxUpdates) {
            Tile current = queue.pop();

            if (current == null || current.build == null || current.team() != team) {
                continue;
            }

            Block currentBlock = current.block();

            /* размер сверяем с целью: цепочка может зацепить блок другого размера (например бур 3x3
             * рядом с 2x2), а замена с иным размером сломала бы соседей */
            boolean shouldUpgrade = (onlySameType ? currentBlock == startBlock : getGroup(currentBlock) == group)
                    && currentBlock.size == targetBlock.size
                    && currentBlock != targetBlock;

            if (shouldUpgrade) {
                //конфиг переносим: иначе улучшенный мост/сортировщик терял бы ссылку и настройку
                var plan = new BuildPlan(current.x, current.y, current.build.rotation, targetBlock,
                        current.build.config());
                if (plan.placeable(team)) {
                    //старый план на той же клетке (прошлый апгрейд/деконструкция) убираем, чтобы не копились дубли
                    removePlansAt(plan.x, plan.y);
                    Vars.player.unit().addBuild(plan);
                    updates++;
                }
            }

            expandNeighbors(queue, visited, current.build, group, team);
        }

        return updates;
    }

    private void removePlansAt(int x, int y) {
        if (Vars.player.unit().plans != null) {
            Vars.player.unit().plans.remove(p -> p != null && p.x == x && p.y == y);
        }
    }

    /* Что считать частью цепочки при удалении. При onlySameType - строго тот же блок; иначе вся группа,
     * а при traverseBridges ещё и «транзитные» блоки (мосты, роутеры, сортировщики), чтобы линия
     * удалялась целиком, а не рвалась на куски на каждом мосту. */
    private boolean shouldDeleteBlock(Block startBlock, BlockGroup group, Block currentBlock) {
        if (startBlock == null || group == BlockGroup.NONE || currentBlock == null) {
            return false;
        }
        if (onlySameType) {
            return currentBlock == startBlock;
        }
        if (getGroup(currentBlock) == group) {
            return true;
        }
        return traverseBridges
                && ((group == BlockGroup.CONVEYOR && isConveyorTraversable(currentBlock))
                        || (group == BlockGroup.CONDUIT && isConduitTraversable(currentBlock)));
    }

    private int deleteChain(Tile startTile) {
        if (startTile == null || startTile.build == null || !hasBuilder()) {
            return 0;
        }

        loadOptions();

        Team team = Vars.player.team();
        Block startBlock = startTile.block();
        BlockGroup group = getGroup(startBlock);
        if (group == BlockGroup.NONE) {
            return 0;
        }

        ObjectSet<Tile> visited = new ObjectSet<>();
        ObjectSet<Building> deleted = new ObjectSet<>();
        Seq<Tile> queue = new Seq<>();

        queue.add(startTile);
        visited.add(startTile);

        int updates = 0;

        while (!queue.isEmpty() && updates < maxUpdates) {
            Tile current = queue.pop();

            if (current == null || current.build == null || current.team() != team) {
                continue;
            }

            Building build = current.build;

            //multi-tile блок виден через несколько клеток - ставим один план деконструкции на постройку
            if (shouldDeleteBlock(startBlock, group, current.block()) && deleted.add(build)) {
                int bx = build.tileX();
                int by = build.tileY();
                removePlansAt(bx, by);
                Vars.player.unit().addBuild(new BuildPlan(bx, by));
                updates++;
            }

            expandNeighbors(queue, visited, build, group, team);
        }

        return updates;
    }

    /* Обход цепочки в ширину. Для линейных блоков (конвейер/труба) идём вперёд/назад и к соседям, что
     * подают В этот блок сбоку (раньше боковые ответвления терялись). Для мостов - по ссылке, по входящим
     * и вокруг обоих концов; для роутеров/сортировщиков - по четырём сторонам. */
    private void expandNeighbors(Seq<Tile> queue, ObjectSet<Tile> visited, Building build, BlockGroup group,
            Team team) {
        Block block = build.block;

        switch (group) {
            case CONVEYOR -> {
                if (block instanceof Conveyor || block instanceof StackConveyor || block instanceof Duct) {
                    expandLine(queue, visited, build, group, team);
                } else if (traverseBridges && isConveyorTraversable(block)) {
                    expandTransit(queue, visited, build, group, team);
                }
            }
            case CONDUIT -> {
                if (block instanceof Conduit) {
                    expandLine(queue, visited, build, group, team);
                } else if (traverseBridges && isConduitTraversable(block)) {
                    expandTransit(queue, visited, build, group, team);
                }
            }
            //группы мостов обходят только мосты, без перехода на линию: переход туда - задача траверса конвейера
            case ITEM_BRIDGE, LIQUID_BRIDGE -> {
                addBridgeLinks(queue, visited, build, group, team, false);
                addAround(queue, visited, build, group, team);
            }
            case WALL, DRILL -> {
                if (build.proximity != null) {
                    for (Building next : build.proximity) {
                        checkAndAdd(queue, visited, next, group, team);
                    }
                }
            }
            default -> {
            }
        }
    }

    private void expandLine(Seq<Tile> queue, ObjectSet<Tile> visited, Building build, BlockGroup group, Team team) {
        checkAndAdd(queue, visited, build.front(), group, team);
        checkAndAdd(queue, visited, build.back(), group, team);
        for (int i = 0; i < 4; i++) {
            Building near = build.nearby(i);
            if (near != null && near.front() == build) {
                checkAndAdd(queue, visited, near, group, team);
            }
        }
    }

    private void expandTransit(Seq<Tile> queue, ObjectSet<Tile> visited, Building build, BlockGroup group,
            Team team) {
        addBridgeLinks(queue, visited, build, group, team, true);
        addAround(queue, visited, build, group, team);
    }

    private void addAround(Seq<Tile> queue, ObjectSet<Tile> visited, Building build, BlockGroup group, Team team) {
        for (int i = 0; i < 4; i++) {
            checkAndAdd(queue, visited, build.nearby(i), group, team);
        }
    }

    /* Другой конец моста и (при withAround) соседи этого конца: линия за мостом продолжается не в самом мосту,
     * а рядом с ним, поэтому одного звена мало - иначе обход упирался в первый же мост. */
    private void addBridgeEnd(Seq<Tile> queue, ObjectSet<Tile> visited, Building end, BlockGroup group, Team team,
            boolean withAround) {
        if (end == null) {
            return;
        }
        checkAndAdd(queue, visited, end, group, team);
        if (withAround) {
            addAround(queue, visited, end, group, team);
        }
    }

    private void addBridgeLinks(Seq<Tile> queue, ObjectSet<Tile> visited, Building build, BlockGroup group,
            Team team, boolean withAround) {
        if (build instanceof ItemBridgeBuild bridge) {
            //LiquidBridgeBuild тоже ItemBridgeBuild: в траверсе конвейера жидкостные мосты не трогаем, и наоборот
            boolean liquid = bridge instanceof LiquidBridgeBuild;
            if (liquid != (group == BlockGroup.CONDUIT || group == BlockGroup.LIQUID_BRIDGE)) {
                return;
            }
            if (bridge.link != -1) {
                addBridgeEnd(queue, visited, Vars.world.build(bridge.link), group, team, withAround);
            }
            if (bridge.incoming != null) {
                for (int i = 0; i < bridge.incoming.size; i++) {
                    addBridgeEnd(queue, visited, Vars.world.build(bridge.incoming.get(i)), group, team, withAround);
                }
            }
        } else if (build instanceof DirectionBridgeBuild bridge) {
            boolean liquid = bridge.block instanceof DirectionLiquidBridge;
            if (liquid != (group == BlockGroup.CONDUIT || group == BlockGroup.LIQUID_BRIDGE)) {
                return;
            }
            addBridgeEnd(queue, visited, bridge.findLink(), group, team, withAround);
            //входящие duct-мосты запоминаются в occupied (по направлению), отдельного списка у них нет
            for (var source : bridge.occupied) {
                addBridgeEnd(queue, visited, source, group, team, withAround);
            }
        }
    }

    private void checkAndAdd(Seq<Tile> queue, ObjectSet<Tile> visited, Building target, BlockGroup group, Team team) {
        //isValid: у duct-мостов occupied[] может держать уже снесённую постройку
        if (target == null || target.tile == null || !target.isValid() || target.team != team) {
            return;
        }

        Tile tile = target.tile;

        if (visited.contains(tile)) {
            return;
        }

        Block block = tile.block();
        boolean accept = switch (group) {
            case CONVEYOR -> getGroup(block) == BlockGroup.CONVEYOR || (traverseBridges && isConveyorTraversable(block));
            case CONDUIT -> getGroup(block) == BlockGroup.CONDUIT || (traverseBridges && isConduitTraversable(block));
            case ITEM_BRIDGE, LIQUID_BRIDGE, WALL, DRILL -> getGroup(block) == group;
            default -> false;
        };

        if (accept) {
            visited.add(tile);
            queue.add(tile);
        }
    }

    /* «Транзитные» блоки конвейерной линии: через них идёт поток, но сами они не апгрейдятся. LiquidBridge
     * наследует ItemBridge - исключаем явно, иначе обход конвейера уходил бы в трубопровод. */
    private boolean isConveyorTraversable(Block block) {
        return block instanceof Junction
                || (block instanceof ItemBridge && !(block instanceof LiquidBridge))
                || (block instanceof DirectionBridge && !(block instanceof DirectionLiquidBridge))
                || block instanceof Router
                || block instanceof Sorter
                || block instanceof OverflowGate
                || block instanceof DuctRouter
                || block instanceof OverflowDuct;
    }

    private boolean isConduitTraversable(Block block) {
        return block instanceof LiquidJunction
                || block instanceof LiquidRouter
                || block instanceof LiquidBridge
                || block instanceof DirectionLiquidBridge;
    }
}
