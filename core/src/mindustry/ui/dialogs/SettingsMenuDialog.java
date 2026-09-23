package mindustry.ui.dialogs;

import arc.*;
import arc.files.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.Texture.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.TextButton.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import mindustry.*;
import mindustry.client.*;
import mindustry.client.ui.*;
import mindustry.client.utils.*;
import mindustry.content.*;
import mindustry.content.TechTree.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.*;
import mindustry.logic.*;
import mindustry.service.*;
import mindustry.type.*;
import mindustry.type.Planet;
import mindustry.ui.*;
import mindustry.ui.fragments.*;
import mindustry.world.blocks.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.storage.*;

import java.io.*;
import java.util.zip.*;

import static arc.Core.*;
import static mindustry.Vars.*;

public class SettingsMenuDialog extends BaseDialog{
    public SettingsTable graphics;
    public SettingsTable game;
    public SettingsTable sound;
    public SettingsTable dev;
    public SettingsTable client;
    public SettingsTable main;

    private Table prefs;
    private Table menu;
    private BaseDialog dataDialog;
    private BaseDialog planetDataDialog;
    private Planet planet = Planets.serpulo;
    private Seq<SettingsCategory> categories = new Seq<>();
    private SettingsSearch globalSearch;

    public SettingsMenuDialog(){
        super(bundle.get("settings", "Settings"));
        addCloseButton();

        cont.add(main = new SettingsTable());
        shouldPause = true;

        hidden(ConstructBlock::updateWarnBlocks); // FINISHME: Horrible

        shown(() -> {
            back();
            rebuildMenu();
        });

        int[] lastRebuildSize = {Core.graphics.getWidth(), Core.graphics.getHeight()};
        onResize(() -> {
            if(lastRebuildSize[0] != Core.graphics.getWidth() || lastRebuildSize[1] != Core.graphics.getHeight()){
                graphics.rebuild();
                sound.rebuild();
                game.rebuild();
                dev.rebuild();
                client.rebuild();
                updateScrollFocus();
                lastRebuildSize[0] = Core.graphics.getWidth();
                lastRebuildSize[1] = Core.graphics.getHeight();
            }
        });

        cont.clearChildren();
        cont.remove();
        buttons.remove();

        menu = new Table(Tex.button);

        game = new SettingsTable();
        graphics = new SettingsTable();
        sound = new SettingsTable();
        dev = new SettingsTable();
        client = new SettingsTable();

        prefs = new Table();
        prefs.top();
        prefs.margin(14f);

        rebuildMenu();

        prefs.clearChildren();
        prefs.add(menu);

        planetDataDialog = new BaseDialog("@settings.data");
        planetDataDialog.addCloseButton();

        planetDataDialog.cont.table(Tex.button, t -> {
            t.defaults().size(280f, 60f).left();
            TextButtonStyle style = Styles.flatt;

            t.button(bundle.format("settings.planetselect", "[#" + planet.iconColor + "]" + planet.localizedName), Icon.planet, style, () -> {
                BaseDialog dialog = new BaseDialog("");
                dialog.cont.pane(p -> {
                    p.background(Tex.button);
                    int i = 0;

                    for(var plan : content.planets()){
                        if(plan.generator == null || plan.sectors.size == 0 || !plan.accessible) continue;

                        p.button(plan.localizedName, Styles.flatTogglet, () -> {
                            planet = plan;
                            dialog.hide();
                        }).size(110f, 45f).checked(planet == plan);

                        if(++i % 4 == 0){
                            p.row();
                        }
                    }
                });
                dialog.setFillParent(false);
                dialog.addCloseButton();
                dialog.show();
            }).marginLeft(4).get().getLabel().setText(() -> bundle.format("settings.planetselect", "[#" + planet.iconColor + "]" + planet.localizedName));

            t.row();

            t.button("@settings.clearplanetresearch", Icon.trash, style, () -> {
                ui.showConfirm("@confirm", bundle.format("settings.clearplanetresearch.confirm", planet.localizedName), () -> {
                    universe.clearLoadoutInfo();
                    for(TechNode node : TechTree.all){
                        if(node.rootNode == planet.techTree){
                            node.reset();
                        }
                    }
                    content.each(c -> {
                        if(c instanceof UnlockableContent u && u.databaseTabs.contains(planet)){
                            u.clearUnlock();
                        }
                    });
                    settings.remove("unlocks");
                });
            }).marginLeft(4);

            t.row();

            t.button("@settings.clearplanetcampaignsaves", Icon.trash, style, () -> {
                ui.showConfirm("@confirm", bundle.format("settings.clearplanetcampaignsaves.confirm", planet.localizedName), () -> {
                    planet.clearStats();
                    boolean any = false;
                    for(var sec : planet.sectors){
                        sec.clearInfo();
                        if(sec.save != null){
                            any = true;
                            sec.save.delete();
                            sec.save = null;
                        }
                    }
                    if(any){
                        planet.reloadMeshAsync();
                    }

                    for(var slot : control.saves.getSaveSlots().copy()){
                        if(slot.isSector() && slot.getSector().planet == planet){
                            slot.delete();
                        }
                    }
                });
            }).marginLeft(4);

            t.row();
        });

        dataDialog = new BaseDialog("@settings.data");
        dataDialog.addCloseButton();

        dataDialog.cont.table(Tex.button, t -> {
            t.defaults().size(280f, 60f).left();
            TextButtonStyle style = Styles.flatt;

            t.button("@settings.cleardata", Icon.trash, style, () -> ui.showConfirm("@confirm", "@settings.clearall.confirm", () -> {
                ObjectMap<String, Object> map = new ObjectMap<>();
                for(String value : Core.settings.keys()){
                    if(value.contains("usid") || value.contains("uuid")){
                        map.put(value, Core.settings.get(value, null));
                    }
                }
                Core.settings.clear();
                Core.settings.putAll(map);

                for(Fi file : dataDirectory.list()){
                    file.deleteDirectory();
                }

                Core.app.exit();
            })).marginLeft(4);

            t.row();

            t.button("@settings.clearplanetdata", Icon.trash, style, () -> planetDataDialog.show()).marginLeft(4).row();

            t.button("@settings.clearsaves", Icon.trash, style, () -> {
                ui.showConfirm("@confirm", "@settings.clearsaves.confirm", control.saves::deleteAll);
            }).marginLeft(4);

            t.row();

            t.button("@settings.clearresearch", Icon.trash, style, () -> {
                ui.showConfirm("@confirm", "@settings.clearresearch.confirm", () -> {
                    universe.clearLoadoutInfo();
                    for(TechNode node : TechTree.all){
                        node.reset();
                    }
                    content.each(c -> {
                        if(c instanceof UnlockableContent u){
                            u.clearUnlock();
                        }
                    });
                    settings.remove("unlocks");
                });
            }).marginLeft(4);

            t.row();

            t.button("@settings.clearcampaignsaves", Icon.trash, style, () -> {
                ui.showConfirm("@confirm", "@settings.clearcampaignsaves.confirm", () -> {
                    control.saves.load(true);
                    for(var planet : content.planets()){
                        planet.clearStats();
                        boolean any = false;
                        for(var sec : planet.sectors){
                            sec.clearInfo();
                            if(sec.save != null){
                                any = true;
                                sec.save.delete();
                                sec.save = null;
                            }
                        }
                        if(any){
                            planet.reloadMeshAsync();
                        }
                    }

                    if (control.saves.loadedSaveCount() > 0){ // If we don't explicitly check for loaded saves, getSaveSlots() will load *all* saves when there are 0 sectors loaded which we don't want as it's needlessly slow.
                        for(var slot : control.saves.getSaveSlots().copy()){
                            if(slot.isSector()){
                                slot.delete();
                            }
                        }
                    }
                    control.saves.unload();
                });
            }).marginLeft(4);

            t.row();

            t.button("@data.export", Icon.upload, style, () -> {
                FileChooser.export("mindustry-data-export", "zip", this::exportData);
            }).marginLeft(4);

            t.row();

            t.button("@data.import", Icon.download, style, () -> ui.showConfirm("@confirm", "@data.import.confirm", () -> FileChooser.open("zip").submit(file -> {
                try{
                    importData(file);
                    mapPreviewDirectory.deleteDirectory();
                    control.saves.resetSave();
                    state = new GameState();
                    Core.app.exit();
                }catch(IllegalArgumentException e){
                    ui.showErrorMessage("@data.invalid");
                }catch(Exception e){
                    Log.err(e);
                    if(e.getMessage() == null || !e.getMessage().contains("too short")){
                        ui.showException(e);
                    }else{
                        ui.showErrorMessage("@data.invalid");
                    }
                }
            }))).marginLeft(4);

            if(!mobile){
                t.row();
                t.button("@data.openfolder", Icon.folder, style, () -> Core.app.openFolder(Core.settings.getDataDirectory().absolutePath())).marginLeft(4);
            }

            t.row();

            t.button("@crash.export", Icon.upload, style, () -> {
                if(settings.getDataDirectory().child("crashes").list().length == 0 && !settings.getDataDirectory().child("last_log.txt").exists()){
                    ui.showInfo("@crash.none");
                }else{
                    FileChooser.export("logs", "txt", file -> file.writeString(getLogs()));
                }
            }).marginLeft(4);
        });

        row();
        ScrollPane pane = pane(prefs).grow().top().get();
        pane.setFadeScrollBars(true);
        pane.setCancelTouchFocus(false);
        row();
        add(buttons).fillX();

        addSettings();

        globalSearch = new SettingsSearch(this);
    }

    String getLogs(){
        Fi log = settings.getDataDirectory().child("last_log.txt");

        StringBuilder out = new StringBuilder();
        for(Fi fi : settings.getDataDirectory().child("crashes").list()){
            out.append(fi.name()).append("\n\n").append(fi.readString()).append("\n");
        }

        if(log.exists()){
            out.append("\nlast log:\n").append(log.readString());
        }

        return out.toString();
    }

    /** Adds a custom settings category, with the icon being the specified region. */
    public void addCategory(String name, @Nullable String region, Cons<SettingsTable> builder){
        categories.add(new SettingsCategory(name, region == null ? null : new TextureRegionDrawable(atlas.find(region)), builder));
    }

    /** Adds a custom settings category, for use in mods. The specified consumer should add all relevant mod settings to the table. */
    public void addCategory(String name, @Nullable Drawable icon, Cons<SettingsTable> builder){
        categories.add(new SettingsCategory(name, icon, builder));
    }

    /** Adds a custom settings category, for use in mods. The specified consumer should add all relevant mod settings to the table. */
    public void addCategory(String name, Cons<SettingsTable> builder){
        addCategory(name, (Drawable)null, builder);
    }

    public Seq<SettingsCategory> getCategories(){
        return categories;
    }

    /** Вкладка настроек: её название, таблица и индекс для {@link #visible(int)} (0-4 - встроенные, дальше - {@link #getCategories()}). */
    public static class Tab{
        public final String name;
        public final SettingsTable table;
        public final int index;

        Tab(String name, SettingsTable table, int index){
            this.name = name;
            this.table = table;
            this.index = index;
        }
    }

    /** Все вкладки с настройками в порядке индексов {@link #visible(int)} - для глобального поиска. */
    public Seq<Tab> tabs(){
        Seq<Tab> out = Seq.with(
            new Tab(bundle.get("settings.game"), game, 0),
            new Tab(bundle.get("settings.graphics"), graphics, 1),
            new Tab(bundle.get("settings.sound"), sound, 2),
            new Tab(bundle.get("settings.dev"), dev, 3),
            new Tab(bundle.get("settings.client"), client, 4)
        );
        int i = 5;
        for(var cat : categories) out.add(new Tab(cat.name, cat.table, i++));
        return out;
    }

    /** Подпись-разделитель групп в левом меню настроек. */
    private void menuGroup(String key){
        menu.add(key, Pal.accent).size(300f, 26f).left().padTop(8f).padLeft(4f).row();
    }

    void rebuildMenu(){
        menu.clearChildren();

        TextButtonStyle style = Styles.flatt;

        float marg = 8f, isize = iconMed;

        menu.defaults().size(300f, 60f);
        //поиск по ВСЕМ вкладкам сразу - первой строкой: настройка может лежать в любой из шести вкладок
        menu.button("@client.settings.searchall", Icon.zoom, style, isize, () -> {
            prefs.clearChildren();
            prefs.add(globalSearch.open());
        }).marginLeft(marg).row();

        //меню сгруппировано: ванильные вкладки / клиент и моды / прочее. Индексы visible() не меняются -
        //порядок кнопок здесь на них не влияет
        menuGroup("@client.settings.group.game");
        menu.button("@settings.game", Icon.settings, style, isize, () -> visible(0)).marginLeft(marg).row();
        menu.button("@settings.graphics", Icon.image, style, isize, () -> visible(1)).marginLeft(marg).row();
        menu.button("@settings.sound", Icon.filters, style, isize, () -> visible(2)).marginLeft(marg).row();
        menu.button("@settings.dev", Icon.fileCode, style, isize, () -> visible(3)).marginLeft(marg).row();

        menuGroup("@client.settings.group.client");
        menu.button("@settings.client", Icon.wrench, style, isize, () -> visible(4)).marginLeft(marg).row();
        int i = 5;
        for(var cat : categories){
            int index = i;
            if(cat.icon == null){
                menu.button(cat.name, style, () -> visible(index)).marginLeft(marg).row();
            }else{
                menu.button(cat.name, cat.icon, style, isize, () -> visible(index)).with(b -> ((Image)b.getChildren().get(1)).setScaling(Scaling.fit)).marginLeft(marg).row();
            }
            i++;
        }

        menuGroup("@client.settings.group.system");
        menu.button("@settings.language", Icon.chat, style, isize, ui.language::show).marginLeft(marg).row();
        if(!mobile || Core.settings.getBool("keyboard")){
            menu.button("@settings.controls", Icon.move, style, isize, ui.controls::show).marginLeft(marg).row();
        }

        menu.button("@settings.data", Icon.save, style, isize, () -> dataDialog.show()).marginLeft(marg).row();
    }

    void addSettings(){
        sound.checkPref("alwaysmusic", false);
        sound.sliderPref("musicvol", 100, 0, 100, 1, i -> { mainExecutor.execute(() -> Musics.load(false)); return i + "%"; });
        sound.sliderPref("sfxvol", 100, 0, 100, 1, i -> { mainExecutor.execute(() -> Sounds.load(false)); return i + "%"; });
        sound.sliderPref("ambientvol", 100, 0, 100, 1, i -> { mainExecutor.execute(() -> Sounds.load(false)); return i + "%"; });

        // Client Settings, organized by topic (sonka 2026-09-21: the old "misc" section was a ~50-item dumping ground). Sections go
        // roughly in order of how often a player touches them; inside a section: buttons/text first, sliders second, checked boxes
        // third, unchecked last. Setting KEYS never change when things move between sections - saved values carry over. Section
        // titles are bundle keys client.setting.<name>.category. Anything can also be found through the search-all page of the menu.

        //movement and input: what the player's own unit and camera do
        client.category("controls");
        client.sliderPref("minzoom", 0, 0, 100, s -> Strings.fixed(Mathf.pow(10, 0.0217f * s) / 100f, 2) + "x");
        client.checkPref("blockreplace", true);
        client.checkPref("instantturn", true);
        client.checkPref("unitnoclip", false);
        client.checkPref("allowinvturrets", true);
        client.checkPref("autoboost", false);
        client.checkPref("assumeunstrict", false);
        client.checkPref("returnonmove", false);
        client.checkPref("nostrafepenalty", false);
        client.checkPref("decreasedrift", false);
        client.checkPref("zerodrift", false);
        client.checkPref("fastrespawn", false);
        client.checkPref("circleassist", false);
        client.checkPref("betterenemyblocktapping", false);
        client.checkPref("automega", false, i -> ui.unitPicker.type = i ? UnitTypes.mega : ui.unitPicker.type);

        //autopilots: paths and what they switch between on their own
        client.category("automation");
        client.textPref("defaultbuildpathargs", "self"); // Keep it to just self. Skill issue players going afk make this too problematic otherwise. FINISHME: Add an afk detection system and revert this once we can reliably detect afk players and allow others to stop their pathing
        client.textPref("defaultminepathargs", "all");
        client.sliderPref("minepathcap", 5000, -100, 5000, 100, s -> s == 0 ? "Unlimited" : s == -100 ? "Never" : String.valueOf(s));
        client.sliderPref("defaultbuildpathradius", 0, 0, 250, 5, s -> s == 0 ? "Unlimited" : String.valueOf(s));
        client.sliderPref("automapvote", 0, 0, 4, s -> s == 0 ? "Never" : s == 4 ? "Random vote" : "Always " + new String[]{"downvote", "novote", "upvote"}[--s]);
        client.checkPref("pathnav", true);

        //no separate AutoTransfer delay slider here: 2026-08-27 it was rewired onto eui's own
        //InteractTimer/"eui-action-delay" (Settings > Mods > Extended UI++, plus its Alt+=/Alt+- binds) -
        //see AutoTransfer.update()'s doc comment
        client.category("autotransfer");
        //per-block Auto Transfer service priorities - the dialog is eui's, but since the dedupe pass its
        //config drives the native AutoTransfer (see AutoTransfer.loadPriorities' doc)
        client.pref(new qol.core.ButtonSetting("autotransfer-priority", () -> {
            if(eui.interact.AutofillPriorityDialog.instance != null) eui.interact.AutofillPriorityDialog.instance.show();
        }));
        //per-turret ammo priority + min-core-to-feed-it overrides (sonka's request, 2026-09-12) - see
        //AmmoPriorityDialog and AutoTransfer.loadAmmoPriorities()/loadAmmoMinCores()'s doc comments
        client.pref(new qol.core.ButtonSetting("autotransfer-ammo-priority", () -> {
            if(eui.interact.AmmoPriorityDialog.instance != null) eui.interact.AmmoPriorityDialog.instance.show();
        }));
        //which container types the "fromContainers" fallback below may draw from - the dialog is eui's,
        //config drives the native AutoTransfer, same wiring as the priority dialogs (sonka's request, 2026-09-12)
        client.pref(new qol.core.ButtonSetting("autotransfer-sourceblocks", () -> {
            if(eui.interact.SourceBlocksDialog.instance != null) eui.interact.SourceBlocksDialog.instance.show();
        }));
        //exposed as sliders 2026-09-12 (sonka's request) - previously only settable by editing the raw
        //settings keys AutoTransfer.init() already reads; wiring the changed-callback keeps the running
        //instance in sync immediately instead of waiting for the next init() (e.g. a server rejoin)
        client.sliderPref("autotransfer-mincoreitems", 100, 0, 2000, 10, s -> String.valueOf(s), i -> mindustry.client.utils.AutoTransfer.minCoreItems = i);
        client.sliderPref("autotransfer-mintransfer", 2, 0, 50, 1, s -> String.valueOf(s), i -> mindustry.client.utils.AutoTransfer.minTransfer = i);
        client.sliderPref("autotransfer-mintransfertotal", 10, 0, 200, 5, s -> String.valueOf(s), i -> mindustry.client.utils.AutoTransfer.minTransferTotal = i);
        //fromContainers used to be silently hardcoded on (transfer() falls back to whichever storage
        //block is nearest whenever it can't reach the core) with a "prefer the one near the core" guess
        //bolted on top; sonka asked to drop the guessing entirely and just let the player decide instead
        client.checkPref("autotransfer-fromcontainers", true, b -> mindustry.client.utils.AutoTransfer.fromContainers = b);
        //drain: opposite direction of the transfer above - pulls full factories/drills into the core or
        //(if enabled) into a configured container. Promoted out of "experimental, no UI" 2026-09-12 at
        //sonka's request - see AutoTransfer.init()'s doc comment.
        client.checkPref("autotransfer-drain", false, b -> mindustry.client.utils.AutoTransfer.drain = b);
        client.checkPref("autotransfer-draintocontainers", false, b -> mindustry.client.utils.AutoTransfer.drainToContainers = b);

        //how the world looks: rendering quality and cosmetic effects
        client.category("graphics");
        client.sliderPref("weatheropacity", 50, 0, 100, s -> s + "%");
        client.sliderPref("beamdrillopacity", 100, 0, 100, 1, s -> s + "%");
        client.sliderPref("formationopacity", 30, 10, 100, 5, s -> { UnitType.formationAlpha = s / 100f; return s + "%"; });
        client.checkPref("lighting", true);
        client.checkPref("drawwrecks", true);
        client.checkPref("drawallitems", true, i -> UnitType.drawAllItems = i);
        client.checkPref("enableunderwaterenv", true);

        //extra information drawn over the world: ranges, paths, flows, graphs, hitboxes
        client.category("overlays");
        client.sliderPref("junctionview", 0, -1, 1, 1, s -> { Junction.setBaseOffset(s); return s == -1 ? "@client.left" : s == 1 ? "@client.right" : "Do not show"; });
        client.sliderPref("spawntime", 5, -1, 60, s -> { ClientVars.spawnTime = 60 * s; if (Vars.pathfinder.thread == null) Vars.pathfinder.start(); return s == -1 ? "Solid Line" : s == 0 ? "@off" : String.valueOf(s); });
        client.sliderPref("traveltime", 10, 0, 60, s -> { ClientVars.travelTime = 60f / s; return s == 0 ? "@off" : String.valueOf(s); });
        client.sliderPref("hitboxopacity", 0, 0, 100, 5, s -> { UnitType.hitboxAlpha = s / 100f; return s == 0 ? "@off" : s + "%"; });
        client.sliderPref("transferrangeopacity", 0, 0, 100, 5, s -> s == 0 ? "@off" : s + "%");
        client.checkPref("drawpath", true);
        client.checkPref("selectionsizeoncursor", true);
        client.checkPref("highlightselectedgraph", true, i -> content.blocks().<BeamNode>each(b -> b instanceof BeamNode, b -> b.configurable = i));
        client.checkPref("powerinfo", true);
        client.checkPref("junctionflowratedirection", false, s -> Junction.flowRateByDirection = s);
        client.checkPref("drawselectionvanilla", false);
        client.checkPref("drawcursors", false);
        client.checkPref("drawdisplayborder", false);
        client.checkPref("tracelogicunits", false);
        client.checkPref("enemyunitranges", false);
        client.checkPref("allyunitranges", false);
        client.checkPref("highlighthoveredgraph", false);
        client.checkPref("showreactors", false);
        client.checkPref("showdomes", false);
        client.checkPref("showmassdriverdistance", false);
        client.checkPref("unloaderview", false, i -> Unloader.drawUnloaderItems = i);
        client.checkPref("customnullunloader", false, i -> Unloader.customNullLoader = i);

        //HUD, menus, player list and the main-menu background
        client.category("interface");
        int[] lastCursednessLevelI = {Core.settings.getInt("cursednesslevel", 0)};
        client.sliderPref("cursednesslevel", 1, 0, 4, s -> CursednessLevel.fromInteger(s).name(), s -> {
            if(Vars.ui.menufrag.renderer != null && Vars.state.isMenu() && s != lastCursednessLevelI[0]){
                Vars.ui.menufrag.renderer.refresh();
                lastCursednessLevelI[0] = s;
            }
        });
        // sonka: выбор юнита фона главного меню - рядом с cursednesslevel, второй настройкой этого фона
        client.pref(new qol.core.ButtonSetting("menu-unit-configure", () -> new sonkaextras.MenuUnitDialog().show()));
        client.checkPref("placementfragmentsearch", true);
        client.checkPref("uselocalizedname", true);
        client.checkPref("showhiddenresources", true);
        client.checkPref("showtoasts", true);
        client.checkPref("showcutscenes", true);
        client.checkPref("activemodesdisplay", true);
        client.checkPref("playerliststyle", true);
        client.checkPref("mobileui", false, i -> mobile = !mobile);
        client.checkPref("alwaysfullnumbers", false);
        client.checkPref("alwaysshowteams", false);
        client.checkPref("showuserid", false);
        client.checkPref("hidebannedblocks", false);
        // sonka: свой текст вместо "Mindustry" в лого главного меню, буквы рисуются спрайтами logofont-*
        client.textPref("customlogotext", "");

        client.category("chat");
        client.sliderPref("shownmessagescount", 10, 1, 25, 1, s -> {
            ChatFragment.setShownMessages();
            return String.valueOf(s);
        });
        client.checkPref("clearchatonleave", true);
        client.checkPref("logmsgstoconsole", true);
        client.checkPref("clientjoinleave", true);
        client.checkPref("highlightcryptomsg", true);
        client.checkPref("showclientmsgsendername", true);
        client.checkPref("displayasuser", true);
        client.checkPref("showidinjoinleave", false);
        client.checkPref("highlightclientmsg", false);
        client.checkPref("enablechatlimit", false);

        //text sent or run automatically: key-bound commands and join/win/lose messages
        client.category("commands");
        client.textPref("keybind1command", "");
        client.textPref("keybind1shiftcommand", "");
        client.textPref("keybind1ctrlcommand", "");
        client.textPref("keybind1altcommand", "");
        client.textPref("gamejointext", "");
        client.textPref("gamewintext", "");
        client.textPref("gamelosetext", "");

        client.category("schematics");
        client.sliderPref("maxschematicslisted", 300, 0, 3000, 150, s -> s == 0 ? "Unlimited" : String.valueOf(s));
        client.sliderPref("processorstatementscale", 80, 10, 100, 1, s -> String.format("%.2fx", s/100f)); // This is the most scuffed setting you have ever seen
        client.checkPref("forceallowschematics", true);
        client.checkPref("schematicmenuexporttags", true);
        client.checkPref("schematicbrowserimporttags", true);
        client.checkPref("schematicuicarryover", false);
        client.checkPref("processorconfigs", false);
        client.checkPref("logiclinkorder", false);

        client.category("antigrief");
        client.sliderPref("reactorwarningdistance", 40, 0, 101, s -> s == 101 ? "Always" : s == 0 ? "Never" : Integer.toString(s));
        client.sliderPref("reactorsounddistance", 25, 0, 101, s -> s == 101 ? "Always" : s == 0 ? "Never" : Integer.toString(s));
        client.sliderPref("incineratorwarningdistance", 5, 0, 101, s -> s == 101 ? "Always" : s == 0 ? "Never" : Integer.toString(s));
        client.sliderPref("incineratorsounddistance", 3, 0, 101, s -> s == 101 ? "Always" : s == 0 ? "Never" : Integer.toString(s));
        client.sliderPref("slagwarningdistance", 10, 0, 101, s -> s == 101 ? "Always" : s == 0 ? "Never" : Integer.toString(s));
        client.sliderPref("slagsounddistance", 5, 0, 101, s -> s == 101 ? "Always" : s == 0 ? "Never" : Integer.toString(s));
        client.checkPref("breakwarnings", true); // Warnings for removal of certain sandbox stuff (mostly sources)
        client.checkPref("powersplitwarnings", true); // FINISHME: Add a minimum building requirement and a setting for it
        client.checkPref("viruswarnings", true, b -> LExecutor.virusWarnings = b);
        client.checkPref("removecorenukes", false);

        //client and mod updates, servers and server-specific workarounds, files and the launcher
        client.category("system");
        client.updatePref();
        client.sliderPref("modautoupdate", 1, 0, 2, s -> s == 0 ? "@off" : s == 1 ? "In Background" : "Restart Game");
        client.sliderPref("pingexecutorthreads", OS.isWindows && !OS.is64Bit ? 5 : 65, 5, 105, 5, s -> s > 100 ? "Unlimited" : String.valueOf(s));
        client.checkPref("autoupdate", true, i -> becontrol.checkUpdates = i);
        client.checkPref("discordrpc", true, i -> platform.toggleDiscord(i));
        client.checkPref("confirmexit", true, i -> Vars.confirmExit = i);
        client.checkPref("autorestart", true);
        client.checkPref("realautorestart", true);
        client.checkPref("downloadmusic", true);
        client.checkPref("downloadsound", true);
        client.checkPref("nyduspadpatch", true);
        client.checkPref("blockfishannoyances", true, i -> Fish.blockAnnoyances = i);
        client.checkPref("hideserversbydefault", false); // Inverts behavior of server hiding
        client.checkPref("allowjoinany", false);
        client.checkPref("autoohno", false);

        //rarely needed: debugging, compatibility overrides and unfinished features
        client.category("advanced");
        client.checkPref("debug", false, i -> Log.level = i ? Log.LogLevel.debug : Log.LogLevel.info); // Sets the log level to debug
        if (steam) client.checkPref("unlockallachievements", false, i -> { Structs.each(Achievement::complete, Achievement.all); Core.settings.remove("unlockallachievements"); });
        client.checkPref("ignoremodminversion", false);
        client.checkPref("client-experimentals", false);

        if (settings.getBool("client-experimentals") || OS.hasProp("policone")) {
            client.category("experimental");
            client.checkPref("trackcoreitems", false, i -> CoreItemsDisplay.trackItems = i && !net.server());
            client.checkPref("modiconloadingoptimization", false);
        }
        // End Client Settings

        game.sliderPref("saveinterval", 60, 10, 5 * 120, 10, i -> Core.bundle.format("setting.seconds", i));
        game.checkPref("autotarget", false);
        if(mobile){
            game.checkPref("keyboard", false, val -> {
                control.setInput(val ? new DesktopInput() : new MobileInput());
                input.setUseKeyboard(val);
            });
            if(Core.settings.getBool("keyboard")){
                control.setInput(new DesktopInput());
                input.setUseKeyboard(true);
            }

        }
        //the issue with touchscreen support on desktop is that:
        //1) I can't test it
        //2) the SDL backend doesn't support multitouch
        /*else{
            game.checkPref("touchscreen", false, val -> control.setInput(!val ? new DesktopInput() : new MobileInput()));
            if(Core.settings.getBool("touchscreen")){
                control.setInput(new MobileInput());
            }
        }*/

        game.checkPref("communityservers", true, val -> {
            defaultServers.clear();
            if(val){
                // FINISHME: JoinDialog methods are not static in foos client because of extra error handling flags
                // -BalaM314, merging v8, Apr 13 2025
                // JoinDialog.fetchServers();
            }
        });

        game.checkPref("savecreate", true);
//        game.checkPref("blockreplace", true); We have this as a client setting in foo's
        game.checkPref("conveyorpathfinding", true);
        game.checkPref("hints", true);

        if(!mobile){
            game.checkPref("backgroundpause", true);
            game.checkPref("buildautopause", false);
            game.checkPref("automineonpause", false);
            game.textPref("automineonpausepriority", "copper lead coal titanium thorium");
            game.checkPref("distinctcontrolgroups", true);
        }

        game.checkPref("doubletapmine", false);
        game.checkPref("commandmodehold", true);
        game.checkPref("unitboosthold", true);

        if(steam){
            game.sliderPref("playerlimit", 16, 2, 250, i -> {
                platform.updateLobby();
                return i + "";
            });
        }

        graphics.sliderPref("uiEdgePadding", 0, 0, 100, s -> s + "px", s -> {
            if(ui != null){
                ui.updateMargins();
                Core.scene.resize(Core.graphics.getWidth(), Core.graphics.getHeight());
            }
        });

        int[] lastUiScale = {settings.getInt("uiscale", 100)};

        graphics.sliderPref("uiscale", 100, 25, 300, 5, s -> {
            //if the user changed their UI scale, but then put it back, don't consider it 'changed'
            Core.settings.put("uiscalechanged", s != lastUiScale[0]);
            return s + "%";
        });

        graphics.sliderPref("screenshake", 4, 0, 8, i -> (i / 4f) + "x");

        graphics.sliderPref("bloomintensity", 6, 0, 16, i -> (int)(i / 4f * 100f) + "%");
        graphics.sliderPref("bloomblur", 2, 1, 16, i -> i + "x");

        graphics.sliderPref("fpscap", 240, 10, 245, 5, s -> {
            if(ios){
                Core.graphics.setPreferredFPS(s > 240 ? 0 : s);
            }
            return (s > 240 ? Core.bundle.get("setting.fpscap.none") : Core.bundle.format("setting.fpscap.text", s));
        });

        if(ios){
            int value = Core.settings.getInt("fpscap", 240);
            Core.graphics.setPreferredFPS(value > 240 ? 0 : value);
        }

        graphics.sliderPref("chatopacity", 100, 0, 100, 5, s -> s + "%");
        graphics.sliderPref("lasersopacity", 100, 0, 100, 5, s -> {
            if(ui.settings != null){
                Core.settings.put("preferredlaseropacity", s);
            }
            return s + "%";
        });

        graphics.sliderPref("unitlaseropacity", 100, 0, 100, 5, s -> s + "%");
        graphics.checkPref("hideminingbeam", false);
        graphics.sliderPref("bridgeopacity", 100, 0, 100, 5, s -> s + "%");

        graphics.sliderPref("maxmagnificationmultiplierpercent", 100, 100, 200, 25, s -> {
            if(ui.settings != null){
                Core.settings.put("maxzoomingamemultiplier", (float)s / 100.0f);
            }
            return s + "%";
        });

        graphics.sliderPref("minmagnificationmultiplierpercent", 100, 100, 300, 25, s -> {
            if(ui.settings != null){
                Core.settings.put("minzoomingamemultiplier", (float)s / 100.0f);
            }
            return s + "%";
        });

        if(!mobile){
            graphics.checkPref("vsync", true, b -> Core.graphics.setVSync(b));
            graphics.checkPref("fullscreen", false, b -> Core.graphics.setFullscreen(b));

            Core.graphics.setVSync(Core.settings.getBool("vsync"));

            if(Core.settings.getBool("fullscreen")){
                Core.app.post(() -> Core.graphics.setFullscreen(true));
            }
        }else if(!ios){
            graphics.checkPref("landscape", false, b -> {
                if(b){
                    platform.beginForceLandscape();
                }else{
                    platform.endForceLandscape();
                }
            });

            if(Core.settings.getBool("landscape")){
                platform.beginForceLandscape();
            }
        }

        graphics.checkPref("effects", true);
        graphics.checkPref("atmosphere", true);
        graphics.checkPref("drawlight", true);
        graphics.checkPref("destroyedblocks", true);
        graphics.checkPref("blockstatus", false);
        graphics.checkPref("playerchat", true);
        graphics.checkPref("coreitems", !mobile);
        graphics.checkPref("minimap", !mobile);
        graphics.checkPref("smoothcamera", true);
        if(!mobile){
            graphics.checkPref("detach-camera", false);
        }
        graphics.checkPref("position", false);
        graphics.checkPref("fps", false);
        graphics.checkPref("playerindicators", true);
        graphics.checkPref("showpings", true);
        graphics.checkPref("showotherbuildplans", true);
        graphics.checkPref("indicators", true);
        // graphics.checkPref("showweather", true); FINISHME: Move client weather alpha to this
        graphics.checkPref("animatedwater", true);

        graphics.checkPref("bloom", true, val -> renderer.toggleBloom(val));

        graphics.checkPref("pixelate", false, val -> {
            if(val){
                Events.fire(Trigger.enablePixelation);
            }
        });

        Cons2<Boolean, Boolean> setFilters = (setNonText, setText) -> {
            ObjectSet<Texture> atlas = new ObjectSet<>(Core.atlas.getTextures());
            final boolean lText = Core.settings.getBool("lineartext");
            var fontFilter = Fonts.getTextFilter(lText);
            for(Font f : new Font[]{Fonts.def, Fonts.outline}){
                f.getRegions().each(t -> {
                    if(setText) {
                        t.texture.setFilter(fontFilter);
                    }
                    atlas.remove(t.texture);
                });
            }
            if(setNonText){
                final var filter = Core.settings.getBool("linear") ? TextureFilter.linear : TextureFilter.nearest;
                atlas.each(t -> t.setFilter(filter));
            }
        };

        graphics.checkPref("linear", !mobile, b -> {
            setFilters.get(true, false);
        });
        graphics.checkPref("lineartext", Core.settings.getBool("linear"), b -> {
            setFilters.get(false, true);
        });

        setFilters.get(true, true);

        graphics.checkPref("skipcoreanimation", false);
        graphics.checkPref("hidedisplays", false);
        graphics.checkPref("logiclocalization", true);

        if(OS.isMac){
            graphics.checkPref("macnotch", false);
        }

        if(!mobile){
            if (Core.settings.getBool("swapdiagonal", false)) Core.settings.put("swapdiagonal", false);
        }


        dev.checkPref("console", false);
        dev.checkPref("drawhitboxes", false);
        dev.checkPref("showperformance", false);

        if(!ios){
            dev.checkPref("modcrashdisable", true);
        }
    }

    public void exportData(Fi file) throws IOException{
        Seq<Fi> files = new Seq<>();
        files.add(Core.settings.getSettingsFile());
        files.addAll(customMapDirectory.list());
        files.addAll(saveDirectory.list());
        files.addAll(modDirectory.list());
        files.addAll(schematicDirectory.list());
        files.addAll(assetCacheDirectory.list()); //important for saves
        String base = Core.settings.getDataDirectory().path();

        //add directories
        for(Fi other : files.copy()){
            Fi parent = other.parent();
            while(!files.contains(parent) && !parent.equals(settings.getDataDirectory())){
                files.add(parent);
            }
        }

        try(OutputStream fos = file.write(false, 2048); ZipOutputStream zos = new ZipOutputStream(fos)){
            for(Fi add : files){
                String path = add.path().substring(base.length());
                if(add.isDirectory()) path += "/";
                //fix trailing / in path
                path = path.startsWith("/") ? path.substring(1) : path;
                zos.putNextEntry(new ZipEntry(path));
                if(!add.isDirectory()){
                    try(var stream = add.read()){
                        Streams.copy(stream, zos);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    public void importData(Fi file){
        Fi zipped = new ZipFi(file);

        Fi base = Core.settings.getDataDirectory();
        if(!zipped.child("settings.bin").exists()){
            throw new IllegalArgumentException("Not valid save data.");
        }

        //delete old saves so they don't interfere
        saveDirectory.deleteDirectory();

        //clear old assets cache
        assetCacheDirectory.deleteDirectory();

        //purge existing tmp data, keep everything else
        tmpDirectory.deleteDirectory();

        zipped.walk(f -> f.copyTo(base.child(f.path())));


        //clear old data
        settings.clear();
        //load data so it's saved on exit
        settings.load();
    }

    private void back(){
        rebuildMenu();
        prefs.clearChildren();
        prefs.add(menu);
    }

    public void visible(int index){
        prefs.clearChildren();

        Seq<Table> tables = Seq.with(game, graphics, sound, dev, client);
        categories.each(c -> tables.add(c.table));

        Table table = tables.get(index);
        //таблица могла быть построена до того, как настройку поменяли на странице глобального поиска (слайдеры берут
        //значение при сборке) или до программного разворота секции - перестроить, чтобы показывала актуальное
        if(table instanceof SettingsTable st) st.rebuild();
        prefs.add(table);
    }

    @Override
    public void addCloseButton(){
        buttons.button("@back", Icon.left, () -> {
            if(prefs.getChildren().first() != menu){
                back();
            }else{
                hide();
            }
        }).size(210f, 64f);

        keyDown(key -> {
            if(key == KeyCode.escape || key == KeyCode.back){
                if(prefs.getChildren().first() != menu){
                    back();
                }else{
                    hide();
                }
            }
        });
    }

    public interface StringProcessor{
        String get(int i);
    }

    public static class SettingsCategory{
        public String name;
        public @Nullable Drawable icon;
        public Cons<SettingsTable> builder;
        public SettingsTable table;

        public SettingsCategory(String name, Drawable icon, Cons<SettingsTable> builder){
            this.name = name;
            this.icon = icon;
            this.builder = builder;

            table = new SettingsTable();
            builder.get(table);
        }
    }

    public static class SettingsTable extends Table{
        protected Seq<Setting> list = new Seq<>();
        private static Seq<Setting> listSorted = new Seq<>();
        private String search = "";
        private final Table searchBarTable = makeSearchBar();
        private TextField searchBar;

        private static final SettingsTable tempTable = new SettingsTable();

        static {
            tempTable.canRebuild = false; // Disable the hack that detects mods adding unsupported settings
        }

        public SettingsTable(){
            left();
        }

        private Table makeSearchBar(){
            return new Table(s -> {
                s.image(Icon.zoom);
                searchBar = s.field(search, res -> {
                    search = res;
                    rebuild();
                }).growX().get();
                //развернуть/свернуть ВСЕ секции разом - только там, где секции вообще есть (Client, «Моды»:
                //там их семнадцать, открывать по одной в поисках нужного было мучением)
                s.button(Icon.downOpen, Styles.cleari, 28f, () -> setAllCollapsed(false)).size(40f).padLeft(6f).visible(this::hasCategories).tooltip("@client.settings.expandall");
                s.button(Icon.upOpen, Styles.cleari, 28f, () -> setAllCollapsed(true)).size(40f).visible(this::hasCategories).tooltip("@client.settings.collapseall");
            });
        }

        public Seq<Setting> getSettings(){
            return list;
        }

        /** Есть ли в таблице секции ({@link #category}) - только тогда имеют смысл кнопки «развернуть/свернуть всё». */
        public boolean hasCategories(){
            return list.contains(s -> s instanceof Category);
        }

        /** Сворачивает/разворачивает все секции таблицы; состояние запоминается так же, как при клике по заголовку. */
        public void setAllCollapsed(boolean collapsed){
            for(Setting s : list){
                if(s instanceof Category c) c.setCollapsed(collapsed);
            }
            rebuild(); //стрелки в заголовках рисуются при сборке
        }

        /**
         * Пустая таблица-приёмник для отрисовки настроек ВНЕ их родной вкладки (глобальный поиск по
         * всем вкладкам - {@link mindustry.client.ui.SettingsSearch}). Как и {@code tempTable}, не
         * следит за «чужими» add() и сама себя не перестраивает: Setting.add(table) просто дописывает в неё свои строки.
         */
        public static SettingsTable detached(){
            SettingsTable t = new SettingsTable();
            t.canRebuild = false;
            return t;
        }

        public void pref(Setting setting){
            list.add(setting);
            rebuild();
        }

        public SliderSetting sliderPref(String name, int def, int min, int max, StringProcessor s){
            return sliderPref(name, def, min, max, 1, s);
        }

        public SliderSetting sliderPref(String name, int def, int min, int max, int step, StringProcessor s){
            return sliderPref(name, def, min, max, step, s, null);
        }

        public SliderSetting sliderPref(String name, int def, int min, int max, StringProcessor s, Intc changed){
            return sliderPref(name, def, min, max, 1, s, changed);
        }

        public SliderSetting sliderPref(String name, int def, int min, int max, int step, StringProcessor s, Intc changed){
            SliderSetting res;
            list.add(res = new SliderSetting(name, def, min, max, step, s, changed));
            settings.defaults(name, def);
            rebuild();
            return res;
        }

        public void checkPref(String name, boolean def){
            list.add(new CheckSetting(name, def, null));
            settings.defaults(name, def);
            rebuild();
        }

        public void checkPref(String name, boolean def, Boolc changed){
            list.add(new CheckSetting(name, def, changed));
            settings.defaults(name, def);
            rebuild();
        }

        public void textPref(String name, String def){
            list.add(new TextSetting(name, def, null));
            settings.defaults(name, def);
            rebuild();
        }

        public void textPref(String name, String def, Cons<String> changed){
            list.add(new TextSetting(name, def, changed));
            settings.defaults(name, def);
            rebuild();
        }

        public void areaTextPref(String name, String def){
            list.add(new AreaTextSetting(name, def, null));
            settings.defaults(name, def);
            rebuild();
        }

        public void areaTextPref(String name, String def, Cons<String> changed){
            list.add(new AreaTextSetting(name, def, changed));
            settings.defaults(name, def);
            rebuild();
        }

        private long lastRebuild;
        /** True while in rebuild(). If the add() method is called on the settings table while this is false, we know that a mod is doing something unsupported and will disable the search bar */
        private boolean isRebuilding;
        /** Whether this table is safe to rebuild. A table is safe unless a mod has added a non Setting element. When this is false, rebuild() will always return immediately, even when called from the mod this table was created by */
        private boolean canRebuild = true;
        /** Horrible way to force a rebuild right before a mod adds a non Setting */
        private boolean forceRebuild = false;

        /** Scuffed way of detecting mods adding non Setting elements to tables */
        @Override
        public <T extends Element> Cell<T> add(T element){
            // FINISHME: Do we also want to maybe get the stacktrace and check if this is being called from Setting.add()? It would be slow but some mod probably does something weird like this somewhere. This suggestion applies to row() as well
            if(isRebuilding || !canRebuild) return super.add(element); // Normal behavior as long as we're adding stuff using the rebuild() method or once we've already disabled our optimizations
            // Force the table to be rebuilt *before* adding the first non setting as this is how vanilla would behave (there's a good chance that the last rebuild was delayed to the next frame for performance reasons)
            Log.warn(bundle.format("client.settings.search.disabled.log.add", Threads.getTrace(1)));
            forceRebuild = true;
            rebuild();
            return super.add(element);
        }

        /** Scuffed way of detecting mods trying to add rows before we have rebuilt() at least once (this happens since foo's normally delays the first rebuild to the frame after setting creation) */
        @Override
        public Table row(){
            if (hasChildren() || !canRebuild || isRebuilding) return super.row();
            // If we haven't yet seen a non setting, but they're trying to add a row, they're probably just adding it after the Reset to Defaults button which would be here normally. Trigger a rebuild so its there properly
            Log.warn(bundle.format("client.settings.search.disabled.log.row", Threads.getTrace(1)));
            forceRebuild = true;
            rebuild();
            return super.row();
        }

        public void rebuild(){ // FINISHME: Ideally, we should still be able to search for settings even if a mod has added non settings to the table. Perhaps we could have a Setting type that just wraps a runnable that adds the element being added in add()?
            if(!forceRebuild){
                if(!canRebuild) return;
                // This optimization breaks some mods that add content to the table as non Settings, it is automatically bypassed rebuilding() is blocked entirely when non Settings are detected
                if (lastRebuild == -1) return; // Can't run more than twice per frame
                if (lastRebuild == 0 || lastRebuild == Core.graphics.getFrameId()) { // First ever run and second run per frame
                    lastRebuild = -1;
                    Core.app.post(() -> {
                        lastRebuild = -2; // Allows rebuild to run
                        rebuild(); // This will set lastRebuild to the current frame
                    });
                    return;
                }
            }else{ // A rebuild was forced, this means that we can no longer rebuild this due to a mod adding a non Setting
                forceRebuild = canRebuild = false;
                if(!searchBar.isDisabled()) searchBar.addListener(Tooltip.Tooltips.getInstance().create("@client.settings.search.disabled.tooltip")); // Only on first run
                searchBar.setDisabled(true);
            }

            isRebuilding = true;
            lastRebuild = Core.graphics.getFrameId();
            boolean hasFocus = searchBar.hasKeyboard();
            clearChildren();
            // FINISHME: Inefficient
            add(searchBarTable).fillX().padBottom(4);
            row();

            if(search.trim().isEmpty()){ // No search value: Contains hacky code for client setting categories
                Cons<Category> pushCategory = cat -> { // Adds all the elements of the current category to the settings table
                    if(cat != null){
                        cat.children.clearChildren(); // Clear the settings otherwise we'll end up with multiple sets of settings
                        tempTable.getCells().each(cell -> {
                            cat.children.add(cell.get()).set(cell); // Hack to get the element of a cell, move it to another table and then copy over the properties to its new cell.
                            if(cell.isEndRow()) cat.children.row(); // Extra hack because setting the cell to the old one doesn't account for rows
                        });
                    }
                    tempTable.clearChildren();
                };
                Category lastCategory = null;
                for(Setting setting : list){
                    if(setting instanceof Category c){
                        pushCategory.get(lastCategory);
                        lastCategory = c;
                        setting.add(this);
                    }else setting.add(lastCategory == null ? this : tempTable);
                }
                pushCategory.get(lastCategory); // Handle final category for table
            }else{
                listSorted.selectFrom(list, s -> !(s instanceof Category));
                var searchLower = search.toLowerCase();
                var searchSplit = ObjectSet.with(searchLower.split(" "));
                listSorted.sort(Structs.comparingFloat(u -> { // FINISHME: Maybe if distance == length, do not include? But troublesome
                    var title = u.title == null ? "" : Strings.stripColors(u.title).toLowerCase();
//                    var desc = u.description == null ? "" : Strings.stripColors(u.description).toLowerCase();
                    var weight = title.isEmpty() /*&& desc.isEmpty()*/ ? Float.POSITIVE_INFINITY : 0f;

                    if (!title.isEmpty()) weight += ClientUtils.biasedLevenshtein(searchLower, title, true, true) / (Structs.count(title.split(" "), searchSplit::contains) + 1); // Case-sensitive as a minor optimization so that search query is only lowercased once
//                    Line below doesn't work great since a lot of the settings don't have descriptions
//                    if (!desc.isEmpty()) weight += .5f * ClientUtils.biasedLevenshtein(searchLower, desc, false, true) / (Structs.count(desc.split(" "), searchSplit::contains) + 1);

                    return weight;
                }));

                for(Setting setting : listSorted){
                    setting.add(this);
                }
            }

            button(bundle.get("settings.reset", "Reset to Defaults"), () -> {
                for(Setting setting : list){
                    if(setting.name == null || setting.title == null) continue;
                    settings.remove(setting.name);
                }
                forceRebuild = !canRebuild; // If we can't rebuild normally, we force a new rebuild through: vanilla would do the same, and it would break bad mods as it will here.
                rebuild();
            }).margin(14).width(240f).pad(6);

            if(hasFocus){
                searchBar.requestKeyboard();
            }
            isRebuilding = false;
        }

        public abstract static class Setting{
            public String name;
            public String title;
            public @Nullable String description;

            public Setting(String name){
                this.name = name;
                String fooKey = "client.setting." + name + ".name";
                if(bundle.has(fooKey)){ // Client keys are in the format client.setting.name.__ as we name all client settings this way and its cleaner. We also don't support winkey as we don't need it.
                    title = bundle.get(fooKey);
                    description = bundle.getOrNull("client.setting." + name + ".description");
                    return;
                }
                String winkey = "setting." + name + ".name.windows";
                title = OS.isWindows && bundle.has(winkey) ? bundle.get(winkey) : bundle.get("setting." + name + ".name", name);
                description = bundle.getOrNull("setting." + name + ".description");
            }

            public abstract void add(SettingsTable table);

            /**
             * Неинтерактивная строка-подзаголовок (например, название фичи мода внутри общей секции), а не
             * настоящая настройка: глобальный поиск не выдаёт её строкой, а берёт её текст как «хлебную
             * крошку» для настроек, идущих следом ({@code qol.core.LabelSetting}/{@code eui.core.LabelSetting}).
             */
            public boolean isHeader(){
                return false;
            }

            public void addDesc(Element elem){
                ui.addDescTooltip(elem, description);
            }

            //sonka: один битый ключ (settings-значение неверного типа - строка там, где ждали int/
            //bool, обычно от переиспользованного/переименованного имени настройки где-то среди
            //вшитых модов) раньше ронял ClassCastException'ом ВЕСЬ экран настроек целиком, при
            //перерисовке ЛЮБОГО SettingsTable - до этого места было не добраться, чтобы починить
            //ключ руками. Самолечение: при плохом типе - вернуть дефолт И сразу перезаписать ключ
            //валидным значением, чтобы следующая перерисовка больше не падала на нём же.
            int safeInt(String name, int def){
                try{
                    return settings.getInt(name, def);
                }catch(ClassCastException e){
                    Log.warn("[settings] key '@' had a wrong-typed value, resetting to default @", name, def);
                    settings.put(name, def);
                    return def;
                }
            }

            boolean safeBool(String name, boolean def){
                try{
                    return settings.getBool(name, def);
                }catch(ClassCastException e){
                    Log.warn("[settings] key '@' had a wrong-typed value, resetting to default @", name, def);
                    settings.put(name, def);
                    return def;
                }
            }

            String safeString(String name, String def){
                try{
                    return settings.getString(name, def);
                }catch(ClassCastException e){
                    Log.warn("[settings] key '@' had a wrong-typed value, resetting to default '@'", name, def);
                    settings.put(name, def);
                    return def;
                }
            }
        }

        public static class CheckSetting extends Setting{
            boolean def;
            Boolc changed;

            public CheckSetting(String name, boolean def, Boolc changed){
                super(name);
                this.def = def;
                this.changed = changed;
            }

            @Override
            public void add(SettingsTable table){
                Table box = Elems.check(title, () -> safeBool(name, def), value -> {
                    settings.put(name, value);
                    if(changed != null){
                        changed.get(value);
                    }
                });

                addDesc(table.add(box).minWidth(Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f))).fillX().height(45f).left().padTop(7f).get());
                table.row();
            }
        }

        public static class SliderSetting extends Setting{
            int def, min, max, step;
            StringProcessor sp;
            Intc changed;

            public SliderSetting(String name, int def, int min, int max, int step, StringProcessor s, Intc changed){
                super(name);
                this.def = def;
                this.min = min;
                this.max = max;
                this.step = step;
                this.sp = s;
                this.changed = changed;
            }

            @Override
            public void add(SettingsTable table){
                Slider slider = new Slider(min, max, step, false);

                slider.setValue(safeInt(name, def));

                Label value = new Label("", Styles.outlineLabel);
                Table content = new Table();
                content.add(title, Styles.outlineLabel).left().growX().wrap();
                content.add(value).padLeft(10f).right();
                content.margin(3f, 33f, 3f, 33f);
                content.touchable = Touchable.disabled;

                slider.changed(() -> {
                    settings.put(name, (int)slider.getValue());
                    value.setText(sp.get((int)slider.getValue()));
                    if(changed != null) changed.get((int)slider.getValue());
                });

                slider.change();

                addDesc(table.stack(slider, content).width(Math.min(Core.graphics.getWidth() / 1.2f / Scl.scl(1f), 500f)).left().padTop(4f).get());
                table.row();
            }
        }

        /** Add a section/subcategory. */
        public void category(String name){ // FINISHME: Rename this to header or something, it doesn't do the same thing as SettingsCategory
            pref(new Category(name));
        }

        // Elements are actually added below
        public static class Category extends Setting{
            protected final static ImageButton.ImageButtonStyle style = new ImageButton.ImageButtonStyle(){{
                imageDownColor = Pal.accent;
                imageOverColor = Pal.accent;
                imageUpColor = Pal.accent;
            }};
            protected Table children = new Table();
            private final Collapser collapser = new Collapser(children, settings.getBool("settingscategory-" + name + "-enabled", true));

            Category(String name){
                super(name);
                title = bundle.get("client.setting." + name + ".category");
            }

            /** Программно свернуть/развернуть секцию (без анимации - таблица всё равно перестраивается) и запомнить это. */
            public void setCollapsed(boolean collapsed){
                collapser.setCollapsed(collapsed, false);
                settings.put("settingscategory-" + name + "-enabled", collapsed);
            }

            @Override
            public boolean isHeader(){
                return true; //секция - не настройка: в результаты поиска не идёт, но даёт крошку
            }

            @Override
            public void add(SettingsTable table){
                ImageButton[] arrowButton = {null};
                table.add("").row(); // Add a cell first as .row doesn't work if there are no cells in the current row.
                final Runnable onClicked = () -> {
                    collapser.toggle();
                    settings.put("settingscategory-" + name + "-enabled", collapser.isCollapsed());
                    arrowButton[0].getStyle().imageUp = collapser.isCollapsed() ? Icon.downOpen : Icon.upOpen;
                };
                table.table(t -> {
                    t.add(title).center().growX().color(Pal.accent).get().clicked(onClicked);
                    (arrowButton[0] = t.button(Icon.downOpen, style, onClicked).size(10f).right().padRight(10f).get())
                        .getStyle().imageUp = collapser.isCollapsed() ? Icon.downOpen : Icon.upOpen;
                }).growX();
                table.row();
                table.image(Tex.whiteui, Pal.accent).growX().height(3f).padTop(4f).padBottom(4f);
                table.row();
                table.add(collapser).left();
                table.row();
            }
        }

        private void updatePref(){
            //sonka: дефолт был mindustry-antigrief/mindustry-client-v8-builds - автообновление
            //подсовывало СТОКОВЫЙ Foo's Client поверх нашей кастомной сборки. Пустой дефолт =
            //апдейтер молчит, пока не задан НАШ репозиторий релизов (через -PupdateUrl при сборке
            //или руками в этом поле настроек)
            settings.defaults("updateurl", "");
            //у старых пользователей (в т.ч. sonka) URL Foo уже СОХРАНЁН в settings.bin - дефолт его не
            //перекрывает, апдейтер каждый запуск стучался в их репозиторий (второй предохранитель
            //отбивал, но зачем стучаться). Разовая чистка именно этого значения
            if("mindustry-antigrief/mindustry-client-v8-builds".equals(settings.getString("updateurl"))) settings.put("updateurl", "");
            if (!Version.updateUrl.isEmpty()) settings.put("updateurl", Version.updateUrl); // overwrites updateurl on every boot, shouldn't be a real issue
            pref(new Setting("updateurl") {
                boolean urlChanged;

                @Override
                public void add(SettingsTable table) { // Update URL with update button FINISHME: Move this to TextPref when i decide im willing to spend 6 hours doing so

                    table.table(t -> {
                        t.button(Icon.refresh, Styles.settingTogglei, 32, () -> {
                            ui.loadfrag.show();
                            becontrol.checkUpdate(result -> {
                                ui.loadfrag.hide();
                                urlChanged = false;
                                if(!result){
                                    //sonka: "@be.noupdates" значило и "актуален", и "запрос упал" одинаково - показываем реальную причину, если есть
                                    String err = becontrol.lastError();
                                    if(err != null){
                                        ui.showErrorMessage(Core.bundle.get("be.noupdates") + "\n\n" + err);
                                    }else{
                                        ui.showInfo("@be.noupdates");
                                    }
                                } else {
                                    becontrol.showUpdateDialog();
                                }
                            });
                        }).update(u -> u.setChecked(becontrol.isUpdateAvailable() || urlChanged)).padRight(4);
                        Label label = new Label(title);
                        t.add(label).minWidth(label.getPrefWidth() / Scl.scl(1.0F) + 25.0F);
                        t.field(safeString(name, ""), text -> {
                            becontrol.setUpdateAvailable(false); // Set this to false as we don't know if this is even a valid URL.
                            urlChanged = true;
                            settings.put(name, text);
                            if(text.isEmpty()) settings.remove(name);
                        }).width(450).get().setMessageText("mindustry-antigrief/mindustry-client-v8-builds");
                    }).left().expandX().padTop(3).height(32).padBottom(3);
                    table.row();
                }
            });
        }

        public static class TextSetting extends Setting{
            String def;
            Cons<String> changed;

            public TextSetting(String name, String def, Cons<String> changed){
                super(name);
                this.def = def;
                this.changed = changed;
            }

            @Override
            public void add(SettingsTable table){
                TextField field = new TextField(safeString(name, def));
                field.setMessageText(def);
                field.changed(() -> {
                    settings.put(name, field.getText());
                    if(changed != null){
                        changed.get(field.getText());
                    }
                });

                Table prefTable = table.table().left().padTop(3f).get();
                prefTable.label(() -> title);
                prefTable.add(field).width(450);
                addDesc(prefTable);
                table.row();
            }
        }

        public static class AreaTextSetting extends TextSetting{
            public AreaTextSetting(String name, String def, Cons<String> changed){
                super(name, def, changed);
            }

            @Override
            public void add(SettingsTable table){
                TextArea area = new TextArea(safeString(name, def));
                area.setPrefRows(5);

                area.changed(() -> {
                    settings.put(name, area.getText());
                    if(changed != null){
                        changed.get(area.getText());
                    }
                });

                addDesc(table.label(() -> title).left().padTop(3f).get());
                table.row().add(area).left();
                table.row();
            }
        }
    }
}
