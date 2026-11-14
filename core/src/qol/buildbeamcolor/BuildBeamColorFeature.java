package qol.buildbeamcolor;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.game.EventType.Trigger;
import mindustry.graphics.Pal;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import mindustry.world.Block;
import mindustry.world.blocks.defense.MendProjector;
import mindustry.world.blocks.power.PowerNode;
import mindustry.world.blocks.units.RepairTurret;
import qol.core.ButtonSetting;
import qol.core.Feature;
import qol.core.SafeSettings;
import qol.ui.QolWindow;

import static mindustry.Vars.content;
import static mindustry.Vars.ui;

/**
 * Recolors several in-world beam/aura colors, each to a custom color, a two-color gradient shimmer
 * (sine sweep between two picked colors; rainbow wins if both toggles are on), or a rainbow:
 * <ul>
 * <li><b>Build beam</b> - the beam units draw while building/repairing a block. Vanilla always uses
 * {@link Pal#accent}, the same shared "highlight" color the engine reuses for a handful of other
 * in-world overlays (shield auras, overdrive/regen auras), so those follow along.</li>
 * <li><b>Heal beams</b> - {@link Pal#heal} covers unit repair beams (poly/mega and friends reference
 * the very same Color instance through {@code RepairBeamWeapon.healColor}) and the green heal
 * effects. Mend projectors and repair turrets hold their OWN green Color instances instead of
 * Pal.heal, so those are covered separately by swapping the blocks' public color fields
 * ({@code MendProjector.baseColor}/{@code phaseColor}, {@code RepairTurret.laserColor}) for the
 * duration of the same draw pass.</li>
 * <li><b>Power lasers</b> - power node/surge tower connection lasers. These read the block's public
 * {@code laserColor1}/{@code laserColor2} fields (a white-to-{@link Pal#powerLight} gradient by
 * power satisfaction), swapped the same way; the starvation cue is kept by using a darkened copy of
 * the custom color as the second gradient stop.</li>
 * <li><b>Lancer/lightning laser, arc lightning, suppression fields</b> - {@link Pal#lancerLaser}
 * (lancer's beam, shock mines, most lightning bolts), {@link Pal#surge} (the default lightning color
 * for most other bullets and electric walls) and {@link Pal#sapBullet} (suppression-field units and
 * abilities) are each a single live Color instance referenced directly - not copied - by everything
 * that uses them, so swapping the Pal field itself (same trick as {@code accent} above) recolors all
 * of it for free. One fringe case stays vanilla: lancer's own bullet type additionally keeps a frozen
 * {@code .cpy()} of the original blue for a dim outer-glow layer ({@code LaserBulletType.colors[0]}),
 * baked in once at content load - that thin outline never updates.</li>
 * <li><b>Mining beam</b> - the beam units draw while mining ore. As a jar mod this one was
 * unreachable: {@code UnitType.drawMiningBeam} tints with the global {@code Color.lightGray}/
 * {@code Color.white} constants, which everything else in the world uses too, so neither a Pal swap
 * nor the draw-pass bracket below could touch it without repainting half the game. Now that this
 * code IS the engine, the draw site reads the resolved color directly ({@link #miningBase()}/
 * {@link #miningFlash()} - null base means "feature inactive, keep vanilla"), which is both simpler
 * and safer than widening the bracket: no global constant is ever mutated. The colors are resolved
 * once per frame here (in the same {@link Trigger#drawOver} hook the other beams use) into reused
 * static buffers, so the per-unit draw loop allocates nothing. The vanilla white flash pulse
 * (absin) is preserved; the flash stop is a whitened copy of the picked color.</li>
 * </ul>
 * There's no supported mod hook for any of these individually - e.g. {@code
 * BuilderComp.drawBuildingBeam()} is baked into the generated {@code Unit} class and can't be
 * overridden or wrapped. The narrowest available seam is bracketing the palette around the exact
 * world draw pass: {@link Trigger#drawOver} fires right before {@code blocks.drawBlocks()} +
 * {@code Groups.draw.draw(...)} (Renderer.java:416-419 - so BOTH block and unit drawing sit inside
 * the bracket), and {@link Trigger#postDraw} fires right after - the swap never bleeds into menus or
 * the settings UI (a separate Scene2D pass outside the bracket), nor into placement previews and
 * selection overlays (drawn at Layer.plans/overlayUI BEFORE drawOver fires).
 */
public class BuildBeamColorFeature implements Feature{
    static final float RAINBOW_SPEED = 1.2f; // degrees per tick -> full cycle roughly every 5s at normal speed
    static final float GRADIENT_SWING = 40f; // sine time scale for the two-color shimmer -> full A->B->A swing every ~4s

    final Color savedAccent = new Color();
    final Color savedHeal = new Color();
    final Color savedLancer = new Color();
    final Color savedSurge = new Color();
    final Color savedSap = new Color();
    /** Our owned instances the swapped block fields point at during the pass - mutating these never touches vanilla objects. */
    final Color healOwned = new Color();
    final Color powerC1 = new Color(), powerC2 = new Color();
    boolean overriding = false, healFieldsSwapped = false, powerSwapped = false;

    //blocks whose color lives in their own public fields rather than a Pal entry, with the original
    //Color references saved for restoring after each pass (laserColor2 in particular IS the shared
    //Pal.powerLight instance - restore must put back the reference, never mutate it)
    final Seq<PowerNode> powerBlocks = new Seq<>();
    final Seq<Color> powerOrig1 = new Seq<>(), powerOrig2 = new Seq<>();
    final Seq<MendProjector> mendBlocks = new Seq<>();
    final Seq<Color> mendOrigBase = new Seq<>(), mendOrigPhase = new Seq<>();
    final Seq<RepairTurret> repairBlocks = new Seq<>();
    final Seq<Color> repairOrig = new Seq<>();

    //mining beam: resolved once per frame in beginOverride() into these reused static buffers and
    //read straight from UnitType.drawMiningBeam (see class javadoc for why the draw site reads the
    //feature instead of a Pal-style bracket swap). Static so the draw site needs no instance lookup;
    //if the suite stands down (external jar mod present) they simply stay inactive.
    static final Color mineBase = new Color(), mineFlash = new Color();
    static boolean mineActive = false;

    @Override
    public String id(){
        return "buildbeam-color";
    }

    @Override
    public String titleKey(){
        return "qol.feature.buildbeam-color.title";
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
        //content is final by ClientLoadEvent - covers modded PowerNode/MendProjector/RepairTurret subclasses too
        for(Block b : content.blocks()){
            if(b instanceof PowerNode pn){
                powerBlocks.add(pn);
                powerOrig1.add(pn.laserColor1);
                powerOrig2.add(pn.laserColor2);
            }else if(b instanceof MendProjector mp){
                mendBlocks.add(mp);
                mendOrigBase.add(mp.baseColor);
                mendOrigPhase.add(mp.phaseColor);
            }else if(b instanceof RepairTurret rt){
                repairBlocks.add(rt);
                repairOrig.add(rt.laserColor);
            }
        }
        Events.run(Trigger.drawOver, this::beginOverride);
        Events.run(Trigger.postDraw, this::endOverride);
    }

    /**
     * Base color for the mining beam this frame, or null when the feature is inactive (disabled, no
     * custom color picked and no rainbow/gradient) - the draw site keeps vanilla lightGray/white then.
     * Never mutate the returned instance.
     */
    public static @arc.util.Nullable Color miningBase(){
        return mineActive ? mineBase : null;
    }

    /** Flash stop for the mining beam - a whitened copy of {@link #miningBase()}; only valid when miningBase() != null. */
    public static Color miningFlash(){
        return mineFlash;
    }

    @Override
    public void buildSettings(SettingsTable table){
        table.checkPref(rainbowKey(), false);
        table.checkPref(gradientKey(), false);
        table.pref(new ButtonSetting(pickKey(), () -> openPicker(colorKey(), defaultColorInt())));
        table.pref(new ButtonSetting(pick2Key(), () -> openPicker(color2Key(), defaultColor2Int())));
        table.checkPref(healRainbowKey(), false);
        table.checkPref(healGradientKey(), false);
        table.pref(new ButtonSetting(healPickKey(), () -> openPicker(healColorKey(), defaultHealInt())));
        table.pref(new ButtonSetting(healPick2Key(), () -> openPicker(healColor2Key(), defaultColor2Int())));
        table.checkPref(powerRainbowKey(), false);
        table.checkPref(powerGradientKey(), false);
        table.pref(new ButtonSetting(powerPickKey(), () -> openPicker(powerColorKey(), defaultPowerInt())));
        table.pref(new ButtonSetting(powerPick2Key(), () -> openPicker(powerColor2Key(), defaultColor2Int())));
        table.checkPref(lancerRainbowKey(), false);
        table.checkPref(lancerGradientKey(), false);
        table.pref(new ButtonSetting(lancerPickKey(), () -> openPicker(lancerColorKey(), defaultLancerInt())));
        table.pref(new ButtonSetting(lancerPick2Key(), () -> openPicker(lancerColor2Key(), defaultColor2Int())));
        table.checkPref(surgeRainbowKey(), false);
        table.checkPref(surgeGradientKey(), false);
        table.pref(new ButtonSetting(surgePickKey(), () -> openPicker(surgeColorKey(), defaultSurgeInt())));
        table.pref(new ButtonSetting(surgePick2Key(), () -> openPicker(surgeColor2Key(), defaultColor2Int())));
        table.checkPref(sapRainbowKey(), false);
        table.checkPref(sapGradientKey(), false);
        table.pref(new ButtonSetting(sapPickKey(), () -> openPicker(sapColorKey(), defaultSapInt())));
        table.pref(new ButtonSetting(sapPick2Key(), () -> openPicker(sapColor2Key(), defaultColor2Int())));
        table.checkPref(mineRainbowKey(), false);
        table.checkPref(mineGradientKey(), false);
        table.pref(new ButtonSetting(minePickKey(), () -> openPicker(mineColorKey(), defaultMineInt())));
        table.pref(new ButtonSetting(minePick2Key(), () -> openPicker(mineColor2Key(), defaultColor2Int())));
    }

    void beginOverride(){
        if(!isEnabled()){
            overriding = false;
            mineActive = false;
            return;
        }

        savedAccent.set(Pal.accent);
        Pal.accent.set(resolved(isRainbow(), isGradient(), colorKey(), defaultColorInt(), color2Key()));

        //heal: Pal.heal swap covers unit repair beams + heal effects; the block-field swap below
        //covers menders/repair turrets, which keep their own green instances. defaultHealInt() must
        //be read BEFORE the swap - it samples the live (still vanilla here) Pal.heal.
        int healDef = defaultHealInt();
        boolean healActive = isHealRainbow() || isHealGradient() || SafeSettings.getInt(healColorKey(), healDef) != healDef;
        savedHeal.set(Pal.heal);
        if(healActive){
            healOwned.set(resolved(isHealRainbow(), isHealGradient(), healColorKey(), healDef, healColor2Key()));
            Pal.heal.set(healOwned);

            healFieldsSwapped = true;
            for(int i = 0; i < mendBlocks.size; i++){
                mendBlocks.get(i).baseColor = healOwned;
                mendBlocks.get(i).phaseColor = healOwned;
            }
            for(int i = 0; i < repairBlocks.size; i++){
                repairBlocks.get(i).laserColor = healOwned;
            }
        }

        int powerDef = defaultPowerInt();
        boolean powerActive = isPowerRainbow() || isPowerGradient() || SafeSettings.getInt(powerColorKey(), powerDef) != powerDef;
        if(powerActive){
            powerC1.set(resolved(isPowerRainbow(), isPowerGradient(), powerColorKey(), powerDef, powerColor2Key()));
            //keep vanilla's "starved lasers look different" cue: gradient runs custom -> darker custom
            powerC2.set(powerC1).mul(0.55f);
            powerC2.a = 1f;

            powerSwapped = true;
            for(int i = 0; i < powerBlocks.size; i++){
                powerBlocks.get(i).laserColor1 = powerC1;
                powerBlocks.get(i).laserColor2 = powerC2;
            }
        }

        //plain Pal-field swaps, same shape as accent above - each of these three is referenced
        //directly (never .cpy()'d) by everything that draws with it, so mutating the shared instance
        //recolors all of it with no per-block bookkeeping needed
        savedLancer.set(Pal.lancerLaser);
        Pal.lancerLaser.set(resolved(isLancerRainbow(), isLancerGradient(), lancerColorKey(), defaultLancerInt(), lancerColor2Key()));

        savedSurge.set(Pal.surge);
        Pal.surge.set(resolved(isSurgeRainbow(), isSurgeGradient(), surgeColorKey(), defaultSurgeInt(), surgeColor2Key()));

        savedSap.set(Pal.sapBullet);
        Pal.sapBullet.set(resolved(isSapRainbow(), isSapGradient(), sapColorKey(), defaultSapInt(), sapColor2Key()));

        //mining beam: no engine state to swap - just refresh the buffers UnitType.drawMiningBeam reads.
        //Units draw inside the drawOver..postDraw bracket, so these are always fresh for this frame.
        int mineDef = defaultMineInt();
        mineActive = isMineRainbow() || isMineGradient() || SafeSettings.getInt(mineColorKey(), mineDef) != mineDef;
        if(mineActive){
            mineBase.set(resolved(isMineRainbow(), isMineGradient(), mineColorKey(), mineDef, mineColor2Key()));
            //keep vanilla's "pulse toward a brighter stop" feel: flash = the same color pushed toward white
            mineFlash.set(mineBase).lerp(Color.white, 0.35f);
            mineFlash.a = 1f;
        }

        overriding = true;
    }

    void endOverride(){
        if(!overriding) return;
        Pal.accent.set(savedAccent);
        Pal.heal.set(savedHeal);
        Pal.lancerLaser.set(savedLancer);
        Pal.surge.set(savedSurge);
        Pal.sapBullet.set(savedSap);
        if(healFieldsSwapped){
            for(int i = 0; i < mendBlocks.size; i++){
                mendBlocks.get(i).baseColor = mendOrigBase.get(i);
                mendBlocks.get(i).phaseColor = mendOrigPhase.get(i);
            }
            for(int i = 0; i < repairBlocks.size; i++){
                repairBlocks.get(i).laserColor = repairOrig.get(i);
            }
            healFieldsSwapped = false;
        }
        if(powerSwapped){
            for(int i = 0; i < powerBlocks.size; i++){
                powerBlocks.get(i).laserColor1 = powerOrig1.get(i);
                powerBlocks.get(i).laserColor2 = powerOrig2.get(i);
            }
            powerSwapped = false;
        }
        overriding = false;
    }

    /* перф: переиспользуемые скретчи для resolved() - каждый из 4 вызовов в beginOverride
     * немедленно копирует результат через .set(), так что одного out-буфера (плюс буфер второго
     * градиентного цвета) хватает; до 8 new Color() за кадр уходят */
    private static final Color resolvedScratch = new Color(), gradient2Scratch = new Color();

    /**
     * The configured color for one beam right now: rainbow wins, then the two-color gradient
     * shimmer (a sine sweep between the main and the second picked color), then the flat pick.
     * Returns a shared scratch instance: every branch fully rewrites r/g/b (fromHsv covers all
     * three; set(int) covers rgba) and the unconditional {@code out.a = 1f} covers the one channel
     * fromHsv leaves alone - the historical "fresh locals so a stale alpha can't gray out the
     * rainbow" concern is already neutralized by that line, so callers must just copy the result
     * before the next resolved() call (all four call sites .set() it immediately).
     */
    static Color resolved(boolean rainbow, boolean gradient, String valueKey, int def, String value2Key){
        Color out = resolvedScratch;
        if(rainbow){
            out.fromHsv((Time.time * RAINBOW_SPEED) % 360f, 1f, 1f);
        }else if(gradient){
            out.set(SafeSettings.getInt(valueKey, def))
                .lerp(gradient2Scratch.set(SafeSettings.getInt(value2Key, defaultColor2Int())),
                    0.5f + 0.5f * Mathf.sin(Time.time, GRADIENT_SWING, 1f));
        }else{
            out.set(SafeSettings.getInt(valueKey, def));
        }
        out.a = 1f;
        return out;
    }

    void openPicker(String valueKey, int def){
        Color current = new Color().set(SafeSettings.getInt(valueKey, def));
        ui.picker.show(current, false, picked -> Core.settings.put(valueKey, Color.rgba8888(picked.r, picked.g, picked.b, picked.a)));
    }

    /** Vanilla {@link Pal#accent}, packed - the "no custom color picked yet" default, so the feature is a no-op until the player actually opens the picker or turns on rainbow. */
    static int defaultColorInt(){
        return Color.rgba8888(Pal.accent.r, Pal.accent.g, Pal.accent.b, Pal.accent.a);
    }

    static int defaultHealInt(){
        return Color.rgba8888(Pal.heal.r, Pal.heal.g, Pal.heal.b, Pal.heal.a);
    }

    static int defaultPowerInt(){
        return Color.rgba8888(Pal.powerLight.r, Pal.powerLight.g, Pal.powerLight.b, Pal.powerLight.a);
    }

    static int defaultLancerInt(){
        return Color.rgba8888(Pal.lancerLaser.r, Pal.lancerLaser.g, Pal.lancerLaser.b, Pal.lancerLaser.a);
    }

    static int defaultSurgeInt(){
        return Color.rgba8888(Pal.surge.r, Pal.surge.g, Pal.surge.b, Pal.surge.a);
    }

    static int defaultSapInt(){
        return Color.rgba8888(Pal.sapBullet.r, Pal.sapBullet.g, Pal.sapBullet.b, Pal.sapBullet.a);
    }

    /** Vanilla {@link Color#lightGray}, packed - the mining beam's "nothing picked" default, keeping the feature a no-op until touched. */
    static int defaultMineInt(){
        return Color.rgba8888(Color.lightGray.r, Color.lightGray.g, Color.lightGray.b, Color.lightGray.a);
    }

    /** White - the "no second gradient color picked yet" default, so a fresh gradient visibly pulses toward white instead of doing nothing. */
    static int defaultColor2Int(){
        return Color.rgba8888(1f, 1f, 1f, 1f);
    }

    boolean isRainbow(){
        return SafeSettings.getBool(rainbowKey(), false);
    }

    boolean isGradient(){
        return SafeSettings.getBool(gradientKey(), false);
    }

    boolean isHealRainbow(){
        return SafeSettings.getBool(healRainbowKey(), false);
    }

    boolean isHealGradient(){
        return SafeSettings.getBool(healGradientKey(), false);
    }

    boolean isPowerRainbow(){
        return SafeSettings.getBool(powerRainbowKey(), false);
    }

    boolean isLancerRainbow(){
        return SafeSettings.getBool(lancerRainbowKey(), false);
    }

    boolean isLancerGradient(){
        return SafeSettings.getBool(lancerGradientKey(), false);
    }

    boolean isSurgeRainbow(){
        return SafeSettings.getBool(surgeRainbowKey(), false);
    }

    boolean isSurgeGradient(){
        return SafeSettings.getBool(surgeGradientKey(), false);
    }

    boolean isSapRainbow(){
        return SafeSettings.getBool(sapRainbowKey(), false);
    }

    boolean isSapGradient(){
        return SafeSettings.getBool(sapGradientKey(), false);
    }

    boolean isMineRainbow(){
        return SafeSettings.getBool(mineRainbowKey(), false);
    }

    boolean isMineGradient(){
        return SafeSettings.getBool(mineGradientKey(), false);
    }

    boolean isPowerGradient(){
        return SafeSettings.getBool(powerGradientKey(), false);
    }

    String rainbowKey(){
        return "buildbeam-color-rainbow";
    }

    String pickKey(){
        return "buildbeam-color-pick";
    }

    String colorKey(){
        return "buildbeam-color-value";
    }

    String healRainbowKey(){
        return "buildbeam-color-heal-rainbow";
    }

    String healPickKey(){
        return "buildbeam-color-heal-pick";
    }

    String healColorKey(){
        return "buildbeam-color-heal-value";
    }

    String powerRainbowKey(){
        return "buildbeam-color-power-rainbow";
    }

    String powerPickKey(){
        return "buildbeam-color-power-pick";
    }

    String powerColorKey(){
        return "buildbeam-color-power-value";
    }

    String gradientKey(){
        return "buildbeam-color-gradient";
    }

    String pick2Key(){
        return "buildbeam-color-pick2";
    }

    String color2Key(){
        return "buildbeam-color-value2";
    }

    String healGradientKey(){
        return "buildbeam-color-heal-gradient";
    }

    String healPick2Key(){
        return "buildbeam-color-heal-pick2";
    }

    String healColor2Key(){
        return "buildbeam-color-heal-value2";
    }

    String powerGradientKey(){
        return "buildbeam-color-power-gradient";
    }

    String powerPick2Key(){
        return "buildbeam-color-power-pick2";
    }

    String powerColor2Key(){
        return "buildbeam-color-power-value2";
    }

    String mineRainbowKey(){
        return "buildbeam-color-mine-rainbow";
    }

    String mineGradientKey(){
        return "buildbeam-color-mine-gradient";
    }

    String minePickKey(){
        return "buildbeam-color-mine-pick";
    }

    String mineColorKey(){
        return "buildbeam-color-mine-value";
    }

    String minePick2Key(){
        return "buildbeam-color-mine-pick2";
    }

    String mineColor2Key(){
        return "buildbeam-color-mine-value2";
    }

    String lancerRainbowKey(){
        return "buildbeam-color-lancer-rainbow";
    }

    String lancerPickKey(){
        return "buildbeam-color-lancer-pick";
    }

    String lancerColorKey(){
        return "buildbeam-color-lancer-value";
    }

    String lancerGradientKey(){
        return "buildbeam-color-lancer-gradient";
    }

    String lancerPick2Key(){
        return "buildbeam-color-lancer-pick2";
    }

    String lancerColor2Key(){
        return "buildbeam-color-lancer-value2";
    }

    String surgeRainbowKey(){
        return "buildbeam-color-surge-rainbow";
    }

    String surgePickKey(){
        return "buildbeam-color-surge-pick";
    }

    String surgeColorKey(){
        return "buildbeam-color-surge-value";
    }

    String surgeGradientKey(){
        return "buildbeam-color-surge-gradient";
    }

    String surgePick2Key(){
        return "buildbeam-color-surge-pick2";
    }

    String surgeColor2Key(){
        return "buildbeam-color-surge-value2";
    }

    String sapRainbowKey(){
        return "buildbeam-color-sap-rainbow";
    }

    String sapPickKey(){
        return "buildbeam-color-sap-pick";
    }

    String sapColorKey(){
        return "buildbeam-color-sap-value";
    }

    String sapGradientKey(){
        return "buildbeam-color-sap-gradient";
    }

    String sapPick2Key(){
        return "buildbeam-color-sap-pick2";
    }

    String sapColor2Key(){
        return "buildbeam-color-sap-value2";
    }
}
