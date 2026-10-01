package qol.minedefaults;

import arc.Core;
import arc.Events;
import arc.math.geom.Vec2;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.ImageButton;
import arc.scene.ui.layout.Table;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Scaling;
import arc.util.Time;
import mindustry.ai.ItemUnitStance;
import mindustry.ai.UnitCommand;
import mindustry.ai.UnitStance;
import mindustry.ai.types.CommandAI;
import mindustry.content.UnitTypes;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.entities.Units;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.type.UnitType;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import mindustry.world.blocks.defense.turrets.BaseTurret;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.meta.BlockFlag;
import qol.assistshare.AssistShareFeature;
import qol.core.ButtonSetting;
import qol.core.Feature;
import qol.core.SafeSettings;
import qol.core.UnitClaims;

import static mindustry.Vars.content;
import static mindustry.Vars.indexer;
import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;

/**
 * Per-unit-type default mining ores. Vanilla's mine command starts every unit on the "auto" stance
 * (mine whatever the core has least of - which in practice means sand and coal noise); this feature
 * lets you configure, per minable unit type, which ore stances a unit should get the moment it's given
 * the mine command - whether by you or by rolling out of a factory/reconstructor with it.
 * <p>
 * The mechanism is exactly what the vanilla command UI does by hand: mining ore choice in v8 is a set
 * of per-item stances ({@link ItemUnitStance}) on the unit's {@link CommandAI}, toggled over the
 * network with {@link Call#setUnitStance} - so this works in multiplayer the same as clicking the
 * stance buttons yourself, and enabling any item stance auto-clears the incompatible "auto" stance
 * server-side.
 * <p>
 * Defaults are applied ONCE per stretch of mine command (tracked by unit id until the unit switches to
 * another command or dies): change a unit's ores manually afterwards and nothing fights you. Types
 * with no ores configured are never touched at all.
 */
public class MineDefaultsFeature implements Feature{
    /** How often (in ticks) the team's units are re-scanned for freshly mine-commanded ones. */
    static final float SCAN_INTERVAL_TICKS = 30f;

    static MineDefaultsFeature instance;

    /**
     * Cross-feature hook for assist-share: whether this unit is a poly the split has diverted to
     * assisting. Such units keep poly-split's own {@link UnitClaims} claim, so assist-share manages
     * them without claiming - and drops them the moment this stops returning true.
     */
    public static boolean divertedToAssist(int id){
        return instance != null && instance.diverted.containsKey(id);
    }

    /** Items some block actually drops (floor/wall ore, boulders) - the only ones worth offering as defaults. */
    final Seq<Item> oreItems = new Seq<>();
    /** Unit types that can mine at all, in content order - the rows of the config dialog. */
    final Seq<UnitType> minerTypes = new Seq<>();
    /** type -> configured default ores; missing/empty entry = leave that type's units on vanilla behavior. */
    final ObjectMap<UnitType, Seq<Item>> configured = new ObjectMap<>();

    /**
     * Units already set up for their current stretch of mine command. Rebuilt as a fresh set every
     * scan from the units actually seen holding the command, then swapped - so ids of units that died
     * or were re-commanded drop out automatically instead of accumulating forever.
     */
    IntSet applied = new IntSet();
    IntSet appliedNext = new IntSet();

    /** Per-scan batches: one setUnitStance call per (stance, on/off) pair covers every affected unit at once. */
    final ObjectMap<ItemUnitStance, IntSeq> toEnable = new ObjectMap<>();
    final ObjectMap<ItemUnitStance, IntSeq> toDisable = new ObjectMap<>();

    //--- distribute mode ("minedefaults-distribute") state ---
    /** id -> the single ore this feature assigned to the unit. Presence here = "managed by distribution". */
    final IntMap<Item> assignedOre = new IntMap<>();
    /**
     * id -> Time.millis() of the assignment. Our own stance RPC only shows up in the unit's
     * client-side stance state after a server round-trip - within this window a mismatch means sync
     * lag, not the player editing the unit's ores, so the hands-off check must skip it.
     */
    final IntMap<Long> assignedAt = new IntMap<>();
    static final long STANCE_SYNC_GRACE_MS = 2000L;

    //--- flee ("minedefaults-flee") state ---
    /** id -> Time.millis() the flee-home command was sent. Presence here = "fleeing under this feature". */
    final IntMap<Long> fleeing = new IntMap<>();
    /** id -> Time.millis() danger was last seen near the unit, for the calm-down delay before resuming mining. */
    final IntMap<Long> lastDangerNear = new IntMap<>();
    final IntSeq fleeDropIds = new IntSeq();
    final IntSeq resumeIds = new IntSeq();
    final ObjectMap<CoreBuild, IntSeq> fleeBatch = new ObjectMap<>();
    /**
     * Fleeing mechs (canBoost types - pulsar, quasar) walk home by default: the flee move command runs
     * plain CommandAI, which only lifts a mech off when the vanilla "boost" stance is set. So flee
     * sets that stance for the trip. Only ids whose stance WE enabled are tracked here - a unit the
     * player had already set to boost keeps it after the episode, everyone else gets it cleared.
     */
    final IntSet boostedByFlee = new IntSet();
    final IntSeq fleeBoostIds = new IntSeq();
    final IntSeq unboostIds = new IntSeq();
    final Seq<Building> enemyTurrets = new Seq<>();
    static final long COMMAND_SYNC_GRACE_MS = 2000L;
    /** How long (ms) a fleeing unit must stay out of danger before it's sent back to mining. */
    static final long CALM_DOWN_MS = 3000L;
    /** Extra margin (tiles) added to a turret's own range when judging it dangerous. */
    static final float TURRET_MARGIN_TILES = 2f;

    //--- poly split ("minedefaults-poly-split") state ---
    /** id -> Time.millis() a poly was switched from mine to assist. Presence here = "diverted by the split". */
    final IntMap<Long> diverted = new IntMap<>();
    final Seq<Unit> polyMiners = new Seq<>();
    final IntSeq splitDropIds = new IntSeq();
    final IntSeq splitCmdIds = new IntSeq();
    /** Units whose stances no longer match what was assigned - the player tweaked them by hand. Left alone until re-commanded. */
    final IntSet handsOff = new IntSet();
    final IntSet seenIds = new IntSet();
    final IntSeq dropIds = new IntSeq();
    final ObjectMap<UnitType, Seq<Unit>> pools = new ObjectMap<>();
    final Seq<Item> candidates = new Seq<>();
    final Seq<Unit> needAssign = new Seq<>();

    //--- abundance assist ("minedefaults-abundance-assist") state ---
    /** id -> Time.millis() a miner was diverted to assist because every ore it's configured for is abundant in the core. */
    final IntMap<Long> abundanceDiverted = new IntMap<>();
    final IntSeq abundanceDropIds = new IntSeq();
    final IntSeq abundanceRestoreIds = new IntSeq();
    final IntSeq abundanceDivertIds = new IntSeq();

    float scanTimer = 0f;

    @Override
    public String id(){
        return "mine-defaults";
    }

    @Override
    public String titleKey(){
        return "qol.feature.mine-defaults.title";
    }

    @Override
    public void init(){
        instance = this;
        //content is fully loaded by ClientLoadEvent, so both catalogs can be computed once here.
        //An item is offered as a default if any block drops it - map-independent, unlike vanilla's
        //stance list which only shows ores present on the current map (a stance for an ore the map
        //lacks is harmless: MinerAI just never picks that item).
        oreItems.addAll(content.items().select(i -> content.blocks().contains(b -> b.itemDrop == i)));
        //core spawn units (alpha/beta/gamma etc., detected as some core block's unitType) are excluded:
        //they can't be produced in survival, so offering defaults for them would just clutter the dialog
        minerTypes.addAll(content.units().select(t -> !t.isHidden() && t.mineTier >= 0
            && !content.blocks().contains(b -> b instanceof CoreBlock core && core.unitType == t)));
        loadConfig();

        Events.on(WorldLoadEvent.class, e -> {
            applied.clear();
            assignedOre.clear();
            assignedAt.clear();
            handsOff.clear();
            fleeing.clear();
            lastDangerNear.clear();
            boostedByFlee.clear();
            diverted.clear();
            abundanceDiverted.clear();
            scanTimer = 0f;
        });
        Events.run(Trigger.update, this::update);
    }

    @Override
    public void buildSettings(SettingsTable table){
        table.checkPref("minedefaults-distribute", true);
        table.checkPref("minedefaults-flee", true);
        table.sliderPref("minedefaults-flee-range", 12, 4, 30, 2, v -> v + " tiles");
        table.checkPref("minedefaults-poly-split", true);
        table.checkPref("minedefaults-abundance-assist", true);
        table.sliderPref("minedefaults-abundance-high", 90, 50, 100, 5, v -> v + "%");
        table.sliderPref("minedefaults-abundance-low", 60, 0, 95, 5, v -> v + "%");
        table.pref(new ButtonSetting("qol-mine-defaults-configure", this::showConfigDialog));
    }

    String settingKey(UnitType type, Item item){
        return "minedefaults-" + type.name + "-" + item.name;
    }

    boolean isConfigured(UnitType type, Item item){
        return SafeSettings.getBool(settingKey(type, item), false);
    }

    /** Same check vanilla's stance list uses for whether a unit may mine an item (Unit.canMine), minus map-specific ore presence. */
    boolean typeCanMine(UnitType type, Item item){
        return item.hardness <= type.mineTier;
    }

    /** Rebuilds the type->ores cache from settings; cached so the per-scan hot path never string-concats setting keys. */
    void loadConfig(){
        configured.clear();
        for(UnitType type : minerTypes){
            Seq<Item> items = oreItems.select(i -> typeCanMine(type, i) && isConfigured(type, i));
            if(!items.isEmpty()) configured.put(type, items);
        }
    }

    void showConfigDialog(){
        BaseDialog dialog = new BaseDialog(Core.bundle.get("qol.mine-defaults.dialog.title", "Default mining ores"));
        dialog.addCloseButton();
        dialog.cont.pane(t -> {
            t.defaults().left().pad(4f);
            t.add(Core.bundle.get("qol.mine-defaults.dialog.hint", "")).wrap().width(520f).row();

            for(UnitType type : minerTypes){
                Table row = new Table();
                row.left();
                row.image(type.uiIcon).size(32f).scaling(Scaling.fit).padRight(6f);
                row.add(type.localizedName).width(140f).left().wrap();

                for(Item item : oreItems){
                    if(!typeCanMine(type, item)) continue;
                    //same toggle idiom as ControlHelperWindow.addToggleButton: state flips in clicked(),
                    //visual checked state follows the setting in update() - never setChecked in the handler
                    ImageButton btn = row.button(new TextureRegionDrawable(item.uiIcon), Styles.clearTogglei, 24f, () -> {
                        Core.settings.put(settingKey(type, item), !isConfigured(type, item));
                        loadConfig();
                    }).size(40f).padLeft(2f).tooltip(item.localizedName).get();
                    btn.update(() -> btn.setChecked(isConfigured(type, item)));
                }
                t.add(row).growX().row();
            }
        }).width(620f).height(420f);
        dialog.show();
    }

    void update(){
        if(!state.isGame() || !isEnabled() || player == null || player.team().data() == null) return;

        scanTimer += Time.delta;
        if(scanTimer < SCAN_INTERVAL_TICKS) return;
        scanTimer = 0f;

        toEnable.clear();
        toDisable.clear();

        if(SafeSettings.getBool("minedefaults-poly-split", true)){
            updatePolySplit();
        }
        if(SafeSettings.getBool("minedefaults-abundance-assist", true)){
            updateAbundanceSplit();
        }
        if(SafeSettings.getBool("minedefaults-flee", true)){
            updateFlee();
        }
        if(SafeSettings.getBool("minedefaults-distribute", true)){
            updateDistribute();
        }else{
            updateOneShot();
        }

        toEnable.each((stance, ids) -> Call.setUnitStance(player, ids.toArray(), stance, true));
        toDisable.each((stance, ids) -> Call.setUnitStance(player, ids.toArray(), stance, false));
    }

    /** Original behavior: hand each freshly mine-commanded unit its type's whole configured ore set, once. */
    void updateOneShot(){
        appliedNext.clear();

        player.team().data().units.each(u -> {
            if(!u.isCommandable() || !(u.controller() instanceof CommandAI ai)) return;
            if(ai.currentCommand() != UnitCommand.mineCommand) return;
            if(UnitClaims.isClaimed(u.id)) return; //mid-cycle under flee/core-heal - not really mining

            if(applied.contains(u.id)){
                appliedNext.add(u.id);
                return;
            }

            Seq<Item> wanted = configured.get(u.type);
            if(wanted == null) return; //type not configured - vanilla behavior, and keep re-checking cheaply

            //diff current stances against the configured set, batching the deltas. Only ever touching
            //ItemUnitStance bits: enabling any of them clears the incompatible "auto" stance server-side
            //(ItemUnitStance's constructor declares that incompatibility), and non-mining stances
            //(hold position etc.) are none of this feature's business.
            for(Item item : oreItems){
                ItemUnitStance stance = ItemUnitStance.getByItem(item);
                if(stance == null || !typeCanMine(u.type, item)) continue;

                boolean desired = wanted.contains(item);
                if(desired != ai.hasStance(stance)){
                    (desired ? toEnable : toDisable).get(stance, IntSeq::new).add(u.id);
                }
            }
            appliedNext.add(u.id);
        });

        //swap: units that left the mine command (or died) fall out and will be re-defaulted next time
        IntSet swap = applied;
        applied = appliedNext;
        appliedNext = swap;
    }

    /**
     * Distribute mode: each configured miner gets ONE ore (a unit can only mine one item at a time
     * anyway), and the fleet is split between the type's configured ores proportionally to how scarce
     * each is in the core - scarcest gets the most units, the rest get fewer but at least one each
     * (when there are enough units to go around). Vanilla's own auto-pick would herd every unit onto
     * the single scarcest item simultaneously; per-unit single-ore stances are the only client-side
     * lever that yields an actual split. Ores absent from the map or with full core storage get no
     * units. Assignments are sticky: rebalancing only moves units when the target counts shift, and a
     * unit whose stances stop matching what it was given (the player edited them) goes hands-off
     * until it's re-commanded.
     */
    void updateDistribute(){
        CoreBuild core = player.team().core();
        if(core == null) return;

        seenIds.clear();
        for(Seq<Unit> pool : pools.values()) pool.clear();

        player.team().data().units.each(u -> {
            if(!u.isCommandable() || !(u.controller() instanceof CommandAI ai)) return;
            if(ai.currentCommand() != UnitCommand.mineCommand) return;
            if(UnitClaims.isClaimed(u.id)) return; //mid-cycle under flee/core-heal - not really mining
            if(configured.get(u.type) == null) return;

            seenIds.add(u.id);
            if(handsOff.contains(u.id)) return;

            Item assigned = assignedOre.get(u.id);
            if(assigned != null
                && Time.timeSinceMillis(assignedAt.get(u.id, 0L)) > STANCE_SYNC_GRACE_MS
                && !stancesMatchSingle(ai, assigned)){
                handsOff.add(u.id);
                return;
            }
            pools.get(u.type, Seq::new).add(u);
        });

        pools.each((type, units) -> {
            if(units.isEmpty()) return;

            //ores worth mining right now: configured for the type, actually present on this map
            //(same floor/wall check MinerAI itself uses), and with room left in the core.
            //core.storageCapacity, NOT core.getMaximumAccepted(item) - under the coreIncinerates rule
            //that method returns Integer.MAX_VALUE/2 (a "no cap enforced" placeholder, not a real number
            //to compare against), which made this "full" check never trigger at all under that rule -
            //same root cause as the bug allOresAbundant hit, fixed there first.
            candidates.clear();
            for(Item item : configured.get(type)){
                if(ItemUnitStance.getByItem(item) == null) continue;
                if(!((type.mineFloor && indexer.hasOre(item)) || (type.mineWalls && indexer.hasWallOre(item)))) continue;
                if(core.storageCapacity > 0 && core.items.get(item) >= core.storageCapacity) continue;
                candidates.add(item);
            }
            if(candidates.isEmpty()) return;

            int k = candidates.size, n = units.size;
            int[] desired = new int[k];
            apportion(n, k, core, desired);

            //keep units already sitting on a still-wanted ore; everything else lands in the
            //reassignment pool, filled greedily toward the biggest shortfall
            int[] have = new int[k];
            needAssign.clear();
            for(Unit u : units){
                Item assigned = assignedOre.get(u.id);
                int idx = assigned == null ? -1 : candidates.indexOf(assigned, true);
                if(idx >= 0 && have[idx] < desired[idx]){
                    have[idx]++;
                }else{
                    needAssign.add(u);
                }
            }
            for(Unit u : needAssign){
                int pick = 0, bestShortfall = Integer.MIN_VALUE;
                for(int i = 0; i < k; i++){
                    if(desired[i] - have[i] > bestShortfall){
                        bestShortfall = desired[i] - have[i];
                        pick = i;
                    }
                }
                have[pick]++;
                assignOre(u, candidates.get(pick));
            }
        });

        //forget units that died or left the mine command - they'll be treated as new next time
        dropIds.clear();
        for(IntMap.Entry<Item> entry : assignedOre){
            if(!seenIds.contains(entry.key)) dropIds.add(entry.key);
        }
        IntSet.IntSetIterator it = handsOff.iterator();
        while(it.hasNext){
            int id = it.next();
            if(!seenIds.contains(id)) dropIds.add(id);
        }
        for(int i = 0; i < dropIds.size; i++){
            assignedOre.remove(dropIds.get(i));
            assignedAt.remove(dropIds.get(i));
            handsOff.remove(dropIds.get(i));
        }
    }

    /**
     * Largest-remainder apportionment of n units over the candidates, weighted by scarcity
     * (1/(core amount + 100) - the +100 softens the split when everything is nearly empty). When
     * there are at least as many units as ores, every ore is guaranteed one unit first, so less
     * scarce ores still get mined - just by fewer units.
     */
    void apportion(int n, int k, CoreBuild core, int[] desired){
        float[] weights = new float[k];
        float sumWeights = 0f;
        for(int i = 0; i < k; i++){
            weights[i] = 1f / (core.items.get(candidates.get(i)) + 100f);
            sumWeights += weights[i];
        }

        int remaining = n;
        if(n >= k){
            for(int i = 0; i < k; i++) desired[i] = 1;
            remaining -= k;
        }

        float[] fraction = new float[k];
        int used = 0;
        for(int i = 0; i < k; i++){
            float quota = remaining * weights[i] / sumWeights;
            int whole = (int)quota;
            desired[i] += whole;
            used += whole;
            fraction[i] = quota - whole;
        }
        for(int extra = used; extra < remaining; extra++){
            int pick = 0;
            for(int i = 1; i < k; i++){
                if(fraction[i] > fraction[pick]) pick = i;
            }
            desired[pick]++;
            fraction[pick] = -1f;
        }
    }

    /**
     * Poly split: of all polys the player put on the mine command, only half actually mine - the
     * other half is switched to the assist command (help the player build, same thing the Unit Notifications "help player"
     * hotkey does), with the odd one going to mining since that's the command
     * that was actually given. The balance is maintained continuously: fresh mine-commanded polys
     * (factory output included) grow the pool and some get diverted; diverted polys dying or being
     * re-commanded by the player shrink the assist half and it's topped back up from the miners -
     * and vice versa, surplus assist polys are returned to mining. Diverted polys are claimed via
     * {@link UnitClaims} for the whole stretch so core-heal/flee don't grab them mid-cycle.
     */
    void updatePolySplit(){
        //current poly miners, eligible to divert
        polyMiners.clear();
        player.team().data().units.each(u -> {
            if(u.type != UnitTypes.poly) return;
            if(!u.isCommandable() || !(u.controller() instanceof CommandAI ai)) return;
            if(ai.currentCommand() != UnitCommand.mineCommand) return;
            if(UnitClaims.isClaimed(u.id)) return;
            polyMiners.add(u);
        });

        //maintain the diverted half: drop the dead and the player-re-commanded, count the rest
        splitDropIds.clear();
        int assistCount = 0;
        for(IntMap.Entry<Long> entry : diverted){
            Unit u = Groups.unit.getByID(entry.key);
            if(u == null || !u.isValid() || u.team != player.team() || !(u.controller() instanceof CommandAI ai)){
                splitDropIds.add(entry.key);
                continue;
            }
            //a poly assist-share has parked on its move command is still "assisting" - that feature
            //does its own manual-override detection and stops answering for the unit if it triggers
            if(ai.currentCommand() != UnitCommand.assistCommand
                && AssistShareFeature.managedExpected(entry.key) == null
                && Time.timeSinceMillis(entry.value) > COMMAND_SYNC_GRACE_MS){
                splitDropIds.add(entry.key);
                continue;
            }
            assistCount++;
        }
        for(int i = 0; i < splitDropIds.size; i++){
            diverted.remove(splitDropIds.get(i));
            UnitClaims.release(splitDropIds.get(i));
        }

        int targetAssist = (polyMiners.size + assistCount) / 2;

        if(assistCount < targetAssist){
            //recruit miners into the assist half
            splitCmdIds.clear();
            for(int i = 0; i < targetAssist - assistCount && i < polyMiners.size; i++){
                Unit u = polyMiners.get(i);
                UnitClaims.claim(u.id);
                diverted.put(u.id, Time.millis());
                splitCmdIds.add(u.id);
            }
            if(splitCmdIds.size > 0){
                Call.setUnitCommand(player, splitCmdIds.toArray(), UnitCommand.assistCommand);
            }
        }else if(assistCount > targetAssist){
            //return surplus assist polys to mining; distribution re-equips them as fresh miners
            splitCmdIds.clear();
            for(IntMap.Entry<Long> entry : diverted){
                if(splitCmdIds.size >= assistCount - targetAssist) break;
                splitCmdIds.add(entry.key);
            }
            for(int i = 0; i < splitCmdIds.size; i++){
                diverted.remove(splitCmdIds.get(i));
                UnitClaims.release(splitCmdIds.get(i));
            }
            Call.setUnitCommand(player, splitCmdIds.toArray(), UnitCommand.mineCommand);
        }
    }

    /**
     * Abundance assist: any own commandable miner (any configured type, not just poly) whose ENTIRE
     * configured ore set is sitting at or above {@code minedefaults-abundance-high} percent of core
     * storage - meaning there's genuinely nothing worth mining for it right now - is switched to the
     * assist command instead of idling on a full stockpile. It's handed back to mining the moment ANY
     * of its configured ores drops below {@code minedefaults-abundance-low} percent (a lower threshold
     * than the trigger, so it doesn't flap back and forth right at one boundary value); distribution
     * mode re-equips it fresh from there. Ores absent from this map are ignored for the check (an
     * always-zero core amount for an unminable ore would otherwise permanently block diversion) - a
     * type with NONE of its configured ores present on the map is left alone entirely rather than
     * treated as vacuously "abundant". Diverted units are claimed via {@link UnitClaims} for the whole
     * stretch, same as poly-split, so core-heal/flee don't draft them mid-cycle; a player manually
     * re-commanding a diverted unit releases it without forcing it back to mining.
     */
    void updateAbundanceSplit(){
        CoreBuild core = player.team().core();
        if(core == null) return;

        int highPct = SafeSettings.getInt("minedefaults-abundance-high", 90);
        int lowPct = SafeSettings.getInt("minedefaults-abundance-low", 60);

        //two different reasons to stop tracking a diverted unit: dead/invalid/player-took-over just gets
        //released (whatever command it's on now is none of our business - same as poly-split's own
        //handling), while "resources dropped below the low mark" is a deliberate restore, actively
        //commanded back to mining
        abundanceDropIds.clear();
        abundanceRestoreIds.clear();
        for(IntMap.Entry<Long> entry : abundanceDiverted){
            Unit u = Groups.unit.getByID(entry.key);
            if(u == null || !u.isValid() || u.team != player.team() || !(u.controller() instanceof CommandAI ai)){
                abundanceDropIds.add(entry.key);
                continue;
            }
            if(ai.currentCommand() != UnitCommand.assistCommand
                && AssistShareFeature.managedExpected(entry.key) == null
                && Time.timeSinceMillis(entry.value) > COMMAND_SYNC_GRACE_MS){
                abundanceDropIds.add(entry.key); //player took over
                continue;
            }
            if(!allOresAbundant(u.type, core, lowPct)){
                abundanceRestoreIds.add(entry.key); //dropped below the low mark - back to mining
            }
        }

        for(int i = 0; i < abundanceDropIds.size; i++){
            abundanceDiverted.remove(abundanceDropIds.get(i));
            UnitClaims.release(abundanceDropIds.get(i));
        }
        for(int i = 0; i < abundanceRestoreIds.size; i++){
            abundanceDiverted.remove(abundanceRestoreIds.get(i));
            UnitClaims.release(abundanceRestoreIds.get(i));
        }
        if(abundanceRestoreIds.size > 0){
            Call.setUnitCommand(player, abundanceRestoreIds.toArray(), UnitCommand.mineCommand);
        }

        //divert fresh candidates: unclaimed mine-commanded units whose whole configured ore set is abundant
        abundanceDivertIds.clear();
        player.team().data().units.each(u -> {
            if(!u.isCommandable() || !(u.controller() instanceof CommandAI ai)) return;
            if(ai.currentCommand() != UnitCommand.mineCommand) return;
            if(UnitClaims.isClaimed(u.id)) return;
            if(!allOresAbundant(u.type, core, highPct)) return;

            UnitClaims.claim(u.id);
            abundanceDiverted.put(u.id, Time.millis());
            abundanceDivertIds.add(u.id);
        });
        if(abundanceDivertIds.size > 0){
            Call.setUnitCommand(player, abundanceDivertIds.toArray(), UnitCommand.assistCommand);
        }
    }

    /**
     * True if every one of {@code type}'s configured ores that's actually present on this map is at or
     * above {@code pct} percent of core storage. False if none of them are present at all.
     * <p>
     * Uses {@code core.storageCapacity} directly, NOT {@link CoreBuild#getMaximumAccepted}, on purpose:
     * under the {@code coreIncinerates} rule (excess items burn instead of hard-capping production),
     * {@code getMaximumAccepted} returns {@code Integer.MAX_VALUE/2} instead of the real capacity - a
     * placeholder meaning "no cap enforced", not an actual number to divide by. Dividing the current
     * amount by that placeholder always rounds down to ~0%, so every ore looked permanently empty and
     * no unit was ever diverted - the exact bug sonka hit. {@code storageCapacity} is the real per-item
     * cap regardless of that rule.
     */
    boolean allOresAbundant(UnitType type, CoreBuild core, int pct){
        Seq<Item> ores = configured.get(type);
        if(ores == null) return false;

        boolean any = false;
        for(Item item : ores){
            if(!((type.mineFloor && indexer.hasOre(item)) || (type.mineWalls && indexer.hasWallOre(item)))) continue;
            any = true;
            float ratio = core.storageCapacity <= 0 ? 1f : core.items.get(item) / (float)core.storageCapacity;
            if(ratio * 100f < pct) return false;
        }
        return any;
    }

    /**
     * Flee mode: any own commandable miner that an armed enemy unit has closed in on, or that sits
     * inside an enemy turret's range, is sent flying home to its nearest core (position command -
     * mine auto-switches to move, same as a manual right-click). Mechs additionally get the vanilla
     * boost stance for the trip so they actually take off instead of walking (see {@link #boostedByFlee}
     * for the tracking rules). Once it's been out of danger for
     * {@link #CALM_DOWN_MS} it's handed the mine command back, and the normal defaults/distribution
     * logic re-equips it from scratch. Claims the unit via {@link UnitClaims} for the whole cycle so
     * core-heal can't draft it mid-flight; a player re-command (detected as "not on move anymore",
     * past the sync grace) releases it immediately.
     */
    void updateFlee(){
        //enemy turret snapshot once per scan - the indexer keeps flagged enemy buildings per team
        enemyTurrets.clear();
        enemyTurrets.addAll(indexer.getEnemy(player.team(), BlockFlag.turret));

        float fleeRange = SafeSettings.getInt("minedefaults-flee-range", 12) * tilesize;

        fleeBatch.clear();
        fleeBoostIds.clear();
        player.team().data().units.each(u -> {
            if(!u.isCommandable() || !(u.controller() instanceof CommandAI ai)) return;
            if(ai.currentCommand() != UnitCommand.mineCommand) return;
            if(UnitClaims.isClaimed(u.id)) return;
            if(!dangerNear(u, fleeRange)) return;

            CoreBuild home = u.closestCore();
            if(home == null) return;

            UnitClaims.claim(u.id);
            fleeing.put(u.id, Time.millis());
            lastDangerNear.put(u.id, Time.millis());
            fleeBatch.get(home, IntSeq::new).add(u.id);
            //mechs should fly home, not walk through the danger they're fleeing from
            if(u.type.canBoost && !ai.hasStance(UnitStance.boost)){
                boostedByFlee.add(u.id);
                fleeBoostIds.add(u.id);
            }
        });
        fleeBatch.each((home, ids) ->
            Call.commandUnits(player, ids.toArray(), null, null, new Vec2(home.x, home.y), false, true));
        if(fleeBoostIds.size > 0){
            Call.setUnitStance(player, fleeBoostIds.toArray(), UnitStance.boost, true);
        }

        //recovery pass: resume mining once the unit has stayed out of danger long enough
        fleeDropIds.clear();
        resumeIds.clear();
        for(IntMap.Entry<Long> entry : fleeing){
            Unit u = Groups.unit.getByID(entry.key);
            if(u == null || !u.isValid() || u.team != player.team() || !(u.controller() instanceof CommandAI ai)){
                fleeDropIds.add(entry.key);
                continue;
            }
            if(ai.currentCommand() != UnitCommand.moveCommand){
                if(Time.timeSinceMillis(entry.value) > COMMAND_SYNC_GRACE_MS) fleeDropIds.add(entry.key);
                continue;
            }
            if(dangerNear(u, fleeRange)){
                lastDangerNear.put(entry.key, Time.millis());
            }else if(Time.timeSinceMillis(lastDangerNear.get(entry.key, 0L)) > CALM_DOWN_MS){
                resumeIds.add(entry.key);
                fleeDropIds.add(entry.key);
            }
        }
        unboostIds.clear();
        for(int i = 0; i < fleeDropIds.size; i++){
            int id = fleeDropIds.get(i);
            fleeing.remove(id);
            lastDangerNear.remove(id);
            UnitClaims.release(id);
            //take back the boost stance we added, so both a resumed miner and a manually
            //re-commanded unit are left with vanilla behavior for whatever they do next
            if(boostedByFlee.remove(id) && Groups.unit.getByID(id) != null){
                unboostIds.add(id);
            }
        }
        if(resumeIds.size > 0){
            Call.setUnitCommand(player, resumeIds.toArray(), UnitCommand.mineCommand);
        }
        if(unboostIds.size > 0){
            Call.setUnitStance(player, unboostIds.toArray(), UnitStance.boost, false);
        }
    }

    boolean dangerNear(Unit u, float fleeRange){
        //an armed enemy unit that can actually hit this unit, within the flee radius
        if(Units.closestEnemy(u.team, u.x, u.y, fleeRange,
            e -> e.type.hasWeapons() && (u.isFlying() ? e.type.targetAir : e.type.targetGround)) != null) return true;

        //inside (or nearly inside) an enemy turret's own range. Non-Turret BaseTurrets (point
        //defense, tractor beams) don't expose target flags - treated as dangerous to be safe
        float margin = TURRET_MARGIN_TILES * tilesize;
        for(Building b : enemyTurrets){
            if(!(b.block instanceof BaseTurret turret)) continue;
            if(b.block instanceof Turret t && !(u.isFlying() ? t.targetAir : t.targetGround)) continue;
            if(u.within(b, turret.range + margin)) return true;
        }
        return false;
    }

    /** True when the unit's ore stances are exactly {item} - i.e. still what this feature assigned. */
    boolean stancesMatchSingle(CommandAI ai, Item item){
        for(Item ore : oreItems){
            ItemUnitStance stance = ItemUnitStance.getByItem(ore);
            if(stance == null) continue;
            if(ai.hasStance(stance) != (ore == item)) return false;
        }
        return true;
    }

    void assignOre(Unit u, Item item){
        assignedOre.put(u.id, item);
        assignedAt.put(u.id, Time.millis());
        CommandAI ai = (CommandAI)u.controller();
        for(Item ore : oreItems){
            ItemUnitStance stance = ItemUnitStance.getByItem(ore);
            if(stance == null) continue;
            boolean desired = ore == item;
            if(desired != ai.hasStance(stance)){
                (desired ? toEnable : toDisable).get(stance, IntSeq::new).add(u.id);
            }
        }
    }
}
