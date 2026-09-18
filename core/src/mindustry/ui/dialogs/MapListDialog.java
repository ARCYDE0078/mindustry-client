package mindustry.ui.dialogs;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.editor.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.maps.Map;
import mindustry.type.*;
import mindustry.ui.*;
import sonkaextras.maptags.*;

import java.util.*;

import static mindustry.Vars.*;

public abstract class MapListDialog extends BaseDialog{
    //shared across map dialogs
    private static MapViewSettings settings = Core.settings.getJson("editormapviewsettings", MapViewSettings.class, MapViewSettings::new);

    BaseDialog activeDialog;

    private String searchString;
    private Seq<Gamemode> modes = new Seq<>();
    private Seq<String> availablePlanets = new Seq<>();
    private Table mapTable = new Table();
    private TextField searchField;
    private ObjectMap<Map, Rules> rulesCache = new ObjectMap<>();

    private boolean displayType;

    private enum MapPriority{
        name(Structs.comparing(Map::name)),
        custom(Structs.comps(Structs.comparingBool(m -> !m.custom), Structs.comparingLong(m -> -m.file.lastModified()))),
        builtin(Structs.comps(Structs.comparingBool(m -> m.custom), Structs.comparingLong(m -> -m.file.lastModified()))),
        recent(Structs.comps(Structs.comparingLong(m -> -m.file.lastModified()), Structs.comparing(Map::name)));

        final Comparator<Map> comparator;

        public static final MapPriority[] all = values();

        MapPriority(Comparator<Map> comparator){
            this.comparator = comparator;
        }
    }

    public static class MapViewSettings{
        boolean
        showBuiltIn = true,
        showCustom = true,
        showModded = true,
        searchAuthor = false,
        searchDescription = false,
        searchModname = false;

        MapPriority priority = MapPriority.recent;

        Seq<String> planets = new Seq<>();

        void save(){
            Core.settings.putJson("editormapviewsettings", MapViewSettings.class, this);
        }
    }

    //sonka: теги карт (см. sonkaextras.maptags.MapTags) - в отличие от планет/режимов выбор не
    //персистится между запусками: теги - пользовательская таксономия, которая меняется чаще, чем
    //набор карт, стойкий фильтр скорее прятал бы карты неожиданно, чем помогал
    private Seq<String> selectedMapTags = new Seq<>();
    /** sonka: пересобрать постоянную строку тегов под поиском (по образцу SchematicsDialog.rebuildTags) */
    private Runnable rebuildMapTags = () -> {};
    private static final float mapTagH = 42f;

    public MapListDialog(String title, boolean displayType){
        super(title);

        this.displayType = displayType;

        buttons.remove();

        addCloseListener();

        hidden(() -> rulesCache.clear());
        shown(this::setup);
        onResize(() -> {
            if(activeDialog != null){
                activeDialog.hide();
            }
            setup();
        });
    }

    void buildButtons(){}

    abstract void showMap(Map map);

    void setup(){
        availablePlanets = content.planets().select(p -> p.accessible).map(p -> p.name);
        availablePlanets.add(Planets.sun.name);

        makeButtonOverlay();

        buttons.clearChildren();

        searchString = null;

        if(Core.graphics.isPortrait() && displayType){
            buttons.button("@back", Icon.left, this::hide).size(210f * 2f, 64f).colspan(2);
            buttons.row();
        }else{
            buttons.button("@back", Icon.left, this::hide).size(210f, 64f);
        }

        buildButtons();

        cont.clear();

        rebuildMaps();

        ScrollPane pane = new ScrollPane(mapTable);
        pane.setFadeScrollBars(false);
        pane.setScrollingDisabledX(true);

        Table search = new Table();
        search.image(Icon.zoom);
        searchField = search.field("", t -> {
            searchString = t.length() > 0 ? t.toLowerCase() : null;
            rebuildMaps();
        }).maxTextLength(50).growX().get();
        searchField.setMessageText("@editor.search");
        search.button(Icon.filter, Styles.emptyi, this::showMapFilters).tooltip("@editor.filters");

        cont.add(search).growX();
        cont.row();

        //sonka: постоянная строка тегов карт под поиском - по образцу SchematicsDialog (там теги
        //видны всегда, не спрятаны в оверлей фильтров); первая версия прятала их в showMapFilters(),
        //sonka попросил вынести на видное место, как у схем
        cont.table(in -> {
            in.left();
            in.add("@client.sonka.maptags.filter").padRight(4);

            in.pane(Styles.noBarPane, t -> {
                rebuildMapTags = () -> {
                    t.clearChildren();
                    t.left();

                    ObjectMap<String, Integer> counts = MapTags.allTags();
                    Seq<String> known = counts.keys().toSeq();
                    known.sort();

                    t.defaults().pad(2).height(mapTagH);
                    if(known.isEmpty()){
                        t.add("@client.sonka.maptags.none").color(Color.lightGray).padRight(6f);
                    }else{
                        for(String tag : known){
                            t.button(tag, Styles.togglet, () -> {
                                if(selectedMapTags.contains(tag)) selectedMapTags.remove(tag);
                                else selectedMapTags.add(tag);
                                rebuildMaps();
                            }).checked(selectedMapTags.contains(tag)).with(c -> c.getLabel().setWrap(false));
                        }
                    }
                };
                rebuildMapTags.run();
            }).fillX().height(mapTagH).scrollY(false);

            in.button(Icon.pencilSmall, Styles.emptyi, mapTagH * 0.4f, () -> {
                MapTagsDialog dialog = new MapTagsDialog();
                //переименование/удаление тега могло затронуть выбранные фильтры - вычищаем то,
                //чего больше не существует, иначе фильтр молча продолжит требовать удалённый тег
                dialog.onChange = () -> {
                    ObjectMap<String, Integer> fresh = MapTags.allTags();
                    selectedMapTags.removeAll(s -> !fresh.containsKey(s));
                    rebuildMapTags.run();
                    rebuildMaps();
                };
                dialog.show();
            }).size(mapTagH).tooltip("@client.sonka.maptags.manage");
        }).height(mapTagH).fillX().padBottom(4);
        cont.row();

        cont.add(pane).padLeft(28f).uniformX().grow().padBottom(64f);
    }

    void rebuildMaps(){
        mapTable.clear();

        mapTable.marginRight(12f);

        int maxwidth = Math.max((int)(Core.graphics.getWidth() / Scl.scl(230)), 1);
        float mapsize = 200f;
        boolean noMapsShown = true;

        int i = 0;

        Seq<Map> mapList = new Seq<>();
        Seq<String> activePlanetFilters = settings.planets.select(p -> availablePlanets.contains(p));

        if(settings.showCustom) mapList.addAll(maps.customMaps());
        if(settings.showBuiltIn) mapList.addAll(maps.defaultMaps());
        if(settings.showModded) mapList.addAll(maps.moddedMaps());

        mapList.distinct();
        mapList.sort(settings.priority.comparator);

        for(Map map : mapList){

            boolean invalid = false;
            for(Gamemode mode : modes){
                invalid |= !mode.valid(map);
            }

            // Only filter through active planets.
            if(!activePlanetFilters.isEmpty()){
                Rules rules = rulesCache.get(map, map::rules);
                if(!activePlanetFilters.contains(rules.planet.name)){
                    continue;
                }
            }

            if(invalid || (searchString != null
            && !map.plainName().toLowerCase().contains(searchString)
            && (!settings.searchAuthor || !map.plainAuthor().toLowerCase().contains(searchString))
            && (!settings.searchDescription || !map.plainDescription().toLowerCase().contains(searchString))
            && (!settings.searchModname || !(map.mod == null ? "" : Strings.stripColors(map.mod.meta.displayName).toLowerCase()).contains(searchString)))){
                continue;
            }

            //sonka: фильтр по тегам карт (MapTags) - по образцу SchematicsDialog.rebuildPane
            if(selectedMapTags.any() && !MapTags.getTags(map).containsAll(selectedMapTags)){
                continue;
            }

            noMapsShown = false;

            if(i % maxwidth == 0){
                mapTable.row();
            }

            //sonka: клик по карточке = showMap(map), НО клик по вложенной кнопке тегов не должен
            //его триггерить - тот же приём (сохранённая ссылка на кнопку + childrenPressed),
            //что и у SchematicsDialog.rebuildPane, иначе тег-кнопка ещё и открывала бы карту
            Button[] sel = {null};
            TextButton button = mapTable.button("", Styles.grayt, () -> {
                if(sel[0].childrenPressed()) return;
                showMap(map);
            }).width(mapsize).bottom().pad(8).get();
            sel[0] = button;
            button.clearChildren();
            button.margin(9);
            button.bottom();

            //TODO hide in editor?
            button.table(t -> {
                t.left();
                for(Gamemode mode : Gamemode.all){
                    TextureRegionDrawable icon = Vars.ui.getIcon("mode" + Strings.capitalize(mode.name()) + "Small");
                    if(mode.valid(map) && Core.atlas.isFound(icon.getRegion())){
                        t.image(icon).size(16f).pad(4f);
                    }
                }
                if(t.getChildren().size == 0){
                    t.add().size(16f).pad(4f);
                }

                //sonka: тег-кнопка карты (MapTags) - правый край верхней строки карточки, по
                //аналогии с кнопкой "тег-редактора" у схем
                t.add().growX();
                t.button(Icon.pencilSmall, Styles.emptyi, 12f, () -> new MapTagEditDialog(map){{
                    //новый тег на карточке мог быть первым использованием этого имени - строка тегов
                    //под поиском должна узнать о нём сразу, не дожидаясь переоткрытия диалога
                    onChange = () -> { rebuildMaps(); rebuildMapTags.run(); };
                }}.show()).size(20f).tooltip("@client.sonka.maptags.edit");
            }).left().growX().row();

            button.add(map.name()).width(mapsize - 18f).center().get().setEllipsis(true);
            button.row();
            button.image().growX().pad(4).color(Pal.gray);
            button.row();
            button.stack(new Image(map.safeTexture()).setScaling(Scaling.fit), new BorderImage(map.safeTexture()).setScaling(Scaling.fit)).size(mapsize - 20f);

            if(displayType){
                button.row();
                button.add(map.custom ? "@custom" : map.workshop ? "@workshop" : map.mod != null ? "[lightgray]" + map.mod.meta.displayName : "@builtin").color(Color.gray).padTop(3);
            }

            i++;
        }

        if(noMapsShown){
            mapTable.add("@maps.none");
        }
    }

    void showMapFilters(){
        activeDialog = new BaseDialog("@editor.filters");
        activeDialog.addCloseButton();
        activeDialog.hidden(settings::save);
        activeDialog.cont.table(menu -> {
            menu.table(tab -> {
                // Gamemodes
                tab.table(t -> {
                    t.add("@editor.filters.mode").padBottom(6f).row();
                    t.table(Tex.button, left -> {
                        for(Gamemode mode : Gamemode.all){
                            TextureRegionDrawable icon = ui.getIcon("mode" + Strings.capitalize(mode.name()));
                            if(Core.atlas.isFound(icon.getRegion())){
                                left.button(icon, Styles.emptyTogglei, () -> {
                                    if(modes.contains(mode)){
                                        modes.remove(mode);
                                    }else{
                                        modes.add(mode);
                                    }
                                    rebuildMaps();
                                }).left().size(60f).checked(modes.contains(mode)).tooltip("@mode." + mode.name() + ".name");
                            }
                        }
                    });
                }).expandX().pad(5f);
                // Priorities
                tab.table(t -> {
                    t.add("@editor.filters.priorities").padBottom(6f).row();
                    t.table(Tex.button, right -> {
                        TextureRegionDrawable[] icons = {Icon.fileText, Icon.players, Icon.hammer, Icon.play};

                        for(int i = 0; i < MapPriority.all.length; i++){
                            var prio = MapPriority.all[i];
                            right.button(icons[i], Styles.emptyTogglei, () -> {
                                settings.priority = prio;
                                rebuildMaps();
                            }).size(60f).checked(b -> settings.priority == prio).tooltip("@editor.filters.prioritize." + prio.name(), true);
                        }
                    });
                }).expandX().pad(5f);
                // Planet selection dialog similar to the tech tree selection menu
                tab.table(t -> {
                    t.add("").padBottom(6f).row();
                    t.table(Tex.button, but -> {
                        ImageButton pButton = but.button(Icon.planet, Styles.emptyTogglei, () -> {
                            new BaseDialog("@editor.filters.planetselect"){{ cont.pane(t -> {
                                t.table(Tex.button, in -> {
                                    in.defaults().width(300f).height(60f);

                                    in.button("@rules.anyenv", Icon.planet, Styles.flatTogglet, iconMed, () -> {
                                        if(settings.planets.contains(Planets.sun.name)){
                                            settings.planets.remove(Planets.sun.name);
                                        }else{
                                            settings.planets.add(Planets.sun.name);
                                        }
                                        rebuildMaps();
                                    }).marginLeft(12f).checked(settings.planets.contains(Planets.sun.name)).row();

                                    for(Planet planet : content.planets().select(p -> p.accessible)){
                                        // Get the planet's custom icon. Defaults to the default colored planet icon
                                        TextureRegion foundIcon = Core.atlas.find(planet.name + "-ui", planet.name);
                                        TextureRegionDrawable picon = Core.atlas.isFound(foundIcon) ? new TextureRegionDrawable(foundIcon) : ((TextureRegionDrawable)Icon.planet.tint(planet.iconColor));

                                        in.button(planet.localizedName, picon, Styles.flatTogglet, iconMed, () -> {
                                            if(settings.planets.contains(planet.name)){
                                                settings.planets.remove(planet.name);
                                            }else{
                                                settings.planets.add(planet.name);
                                            }
                                            rebuildMaps();
                                        }).marginLeft(12f).checked(settings.planets.contains(planet.name)).row();
                                    }
                                });
                            });
                                addCloseButton();
                            }}.show();
                        }).size(60f).tooltip("@editor.filters.planetselect").checked(b -> settings.planets.find(p -> availablePlanets.contains(p)) != null)
                        .get();
                        pButton.addListener(new ClickListener(KeyCode.mouseRight){
                            @Override
                            public void clicked(InputEvent event, float x, float y) {
                                if(mobile) return;
                                settings.planets.removeAll(p -> availablePlanets.contains(p));
                                rebuildMaps();
                            }
                        });
                        pButton.addListener(new ElementGestureListener(){
                            @Override
                            public boolean longPress(Element e, float x, float y){
                                if(!mobile) return false;
                                settings.planets.removeAll(p -> availablePlanets.contains(p));
                                rebuildMaps();
                                return true;
                            }
                        });
                    });
                }).expandX().pad(5f);
            }).padBottom(10f);
            menu.row();

            menu.add("@editor.filters.type").width(120f).left().row();
            menu.table(Tex.button, t -> {
                t.button("@custom", Styles.flatTogglet, () -> {
                    settings.showCustom = !settings.showCustom;
                    rebuildMaps();
                }).size(150f, 60f).checked(settings.showCustom);
                t.button("@builtin", Styles.flatTogglet, () -> {
                    settings.showBuiltIn = !settings.showBuiltIn;
                    rebuildMaps();
                }).size(150f, 60f).checked(settings.showBuiltIn);
                t.button("@modded", Styles.flatTogglet, () -> {
                    settings.showModded = !settings.showModded;
                    rebuildMaps();
                }).size(150f, 60f).checked(settings.showModded);
            }).padBottom(10f);
            menu.row();
            menu.add("@editor.filters.search").width(120f).left().row();
            menu.table(Tex.button, t -> {
                t.button("@editor.filters.author", Styles.flatTogglet, () -> {
                    settings.searchAuthor = !settings.searchAuthor;
                    rebuildMaps();
                }).size(150f, 60f).checked(settings.searchAuthor);
                t.button("@editor.filters.description", Styles.flatTogglet, () -> {
                    settings.searchDescription = !settings.searchDescription;
                    rebuildMaps();
                }).size(150f, 60f).checked(settings.searchDescription);
                t.button("@editor.filters.modname", Styles.flatTogglet, () -> {
                    settings.searchModname = !settings.searchModname;
                    rebuildMaps();
                }).size(150f, 60f).checked(settings.searchModname);
            });
            menu.row();
            //sonka: строка тегов карт переехала на постоянное место под поиском (build()) - здесь
            //больше не нужна, sonka попросил видимость как у схем, а не спрятанной в этот оверлей
        });

        activeDialog.show();
    }

    @Override
    public Dialog show(){
        super.show();

        if(Core.app.isDesktop() && searchField != null){
            Core.scene.setKeyboardFocus(searchField);
        }

        return this;
    }
}