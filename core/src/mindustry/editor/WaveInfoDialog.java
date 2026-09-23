package mindustry.editor;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.TextField.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.Binding;
import mindustry.io.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.world.*;

import static mindustry.Vars.*;
import static mindustry.game.SpawnGroup.*;

public class WaveInfoDialog extends BaseDialog{
    Seq<SpawnGroup> groups = new Seq<>();
    private @Nullable SpawnGroup expandedGroup;

    private Table table;
    private int search = -1;
    private @Nullable UnitType filterType;
    private Sort sort = Sort.begin;
    private boolean reverseSort = false;
    private boolean checkedSpawns;
    private WaveGraph graph = new WaveGraph();

    public WaveInfoDialog(){
        super("@waves.title");

        shown(() -> {
            checkedSpawns = false;
            setup();
        });
        hidden(() -> state.rules.spawns = groups);

        addCloseButton();

        buttons.button("@edit.menu", Icon.edit, () -> {
            BaseDialog dialog = new BaseDialog("@edit.menu");
            dialog.addCloseButton();
            dialog.setFillParent(false);
            dialog.cont.table(Tex.button, t -> {
                var style = Styles.cleart;
                t.defaults().size(280f, 64f).pad(2f);

                t.button("@copy.clipboard", Icon.copy, style, () -> {
                    ui.showInfoFade("@waves.copied");
                    Core.app.setClipboardText(maps.writeWaves(groups));
                    dialog.hide();
                }).disabled(b -> groups == null || groups.isEmpty()).marginLeft(12f).row();

                t.button("@load.clipboard", Icon.download, style, () -> {
                    try{
                        groups = maps.readWaves(Core.app.getClipboardText());
                        buildGroups();
                    }catch(Exception e){
                        Log.err(e);
                        ui.showErrorMessage("@waves.invalid");
                    }
                    dialog.hide();
                }).disabled(Core.app.getClipboardText() == null || !Core.app.getClipboardText().startsWith("[")).marginLeft(12f).row();

                t.button("@clear", Icon.none, style, () -> ui.showConfirm("@confirm", "@settings.clear.confirm", () -> {
                    groups.clear();
                    buildGroups();
                    dialog.hide();
                })).marginLeft(12f).row();

                t.button("@settings.reset", Icon.refresh, style, () -> ui.showConfirm("@confirm", "@settings.clear.confirm", () -> {
                    groups = JsonIO.copy(waves.get());
                    buildGroups();
                    dialog.hide();
                })).marginLeft(12f);
            });

            dialog.show();
        }).size(250f, 64f);

        buttons.button("@waves.stats", Icon.list, this::showStats).width(200f);

        buttons.button(Core.bundle.get("waves.random"), Icon.refresh, () -> {
            groups.clear();
            groups = Waves.generate(1f / 10f);
            buildGroups();
        }).width(200f);
    }

    void setup(){
        groups = JsonIO.copy(state.rules.spawns.isEmpty() ? waves.get() : state.rules.spawns);
        if(groups == null) groups = new Seq<>();

        cont.clear();
        cont.stack(new Table(Tex.clear, main -> {
            main.table(s -> {
                s.image(Icon.zoom).padRight(8);
                s.field(search < 0 ? "" : (search + 1) + "", TextFieldFilter.digitsOnly, text -> {
                    search = groups.any() ? Strings.parseInt(text, 0) - 1 : -1;
                    buildGroups();
                }).growX().maxTextLength(8).get().setMessageText("@waves.search");
                s.button(Icon.units, Styles.emptyi, () -> showUnits(type -> filterType = type, true)).size(46f).tooltip("@waves.filter")
                .update(b -> b.getStyle().imageUp = filterType != null ? new TextureRegionDrawable(filterType.uiIcon) : Icon.filter);
            }).growX().pad(6f).row();

            main.pane(t -> table = t).grow().padRight(8f).scrollX(false).row();

            main.table(t -> {
                t.button("@add", () -> {
                    showUnits(type -> groups.add(expandedGroup = new SpawnGroup(type)), false);
                    buildGroups();
                }).growX().height(70f);

                t.button(Icon.filter, () -> {
                    BaseDialog dialog = new BaseDialog("@waves.sort");
                    dialog.setFillParent(false);
                    dialog.cont.table(Tex.button, f -> {
                        for(Sort s : Sort.all){
                            f.button("@waves.sort." + s, Styles.flatTogglet, () -> {
                                sort = s;
                                dialog.hide();
                                buildGroups();
                            }).size(150f, 60f).checked(s == sort);
                        }
                    }).row();
                    dialog.cont.check("@waves.sort.reverse", b -> {
                        reverseSort = b;
                        buildGroups();
                    }).padTop(4).checked(reverseSort).padBottom(8f);
                    dialog.addCloseButton();
                    dialog.show();
                }).size(64f, 70f).padLeft(6f);
            }).growX();

        }), new Label("@waves.none"){{
            visible(() -> groups.isEmpty());
            this.touchable = Touchable.disabled;
            setWrap(true);
            setAlignment(Align.center, Align.center);
        }}).width(390f).growY();

        cont.add(graph = new WaveGraph()).grow();

        buildGroups();
    }

    void buildGroups(){
        table.clear();
        table.top();
        table.margin(10f);

        if(groups != null){
            groups.sort(Structs.comps(Structs.comparingFloat(sort.sort), Structs.comparingFloat(sort.secondary)));
            if(reverseSort) groups.reverse();

            for(SpawnGroup group : groups){
                if(group.effect == StatusEffects.none) group.effect = null;
                if((search >= 0 && group.getSpawned(search) <= 0) || (filterType != null && group.type != filterType)) continue;

                table.table(Tex.button, t -> {
                    t.margin(0).defaults().pad(3).padLeft(5f).growX().left();
                    t.button(b -> {
                        b.left();
                        b.image(group.type.uiIcon).size(32f).padRight(3).scaling(Scaling.fit);
                        b.add(group.type.localizedName).ellipsis(true).width(110f).left().color(Pal.accent);

                        b.add().growX();

                        b.label(() -> (group.begin + 1) + "").color(Color.lightGray).minWidth(45f).labelAlign(Align.left).left();

                        b.button(Icon.copySmall, Styles.emptyi, () -> {
                            groups.insert(groups.indexOf(group) + 1, expandedGroup = group.copy());
                            buildGroups();
                        }).pad(-6).size(46f).tooltip("@editor.copy");

                        Seq<StatusEffect> effs = group.effects();
                        b.button(!effs.isEmpty() ?
                            new TextureRegionDrawable(effs.first().uiIcon) :
                            Icon.logicSmall,
                        Styles.emptyi, () -> showEffects(group)).pad(-6).size(46f).scaling(Scaling.fit).tooltip(!effs.isEmpty() ? effs.map(e -> e.localizedName).toString(", ") : "@none");

                        b.button(Icon.unitsSmall, Styles.emptyi, () -> showUnits(type -> group.type = type, false)).pad(-6).size(46f).tooltip("@stat.unittype");
                        b.button(Icon.cancel, Styles.emptyi, () -> {
                            groups.remove(group);
                            if(expandedGroup == group) expandedGroup = null;
                            table.getCell(t).pad(0f);
                            t.remove();
                            buildGroups();
                        }).pad(-6).size(46f).padRight(-12f).tooltip("@waves.remove");
                        b.clicked(KeyCode.mouseMiddle, () -> {
                            groups.insert(groups.indexOf(group) + 1, expandedGroup = group.copy());
                            buildGroups();
                        });
                    }, () -> {
                        expandedGroup = expandedGroup == group ? null : group;
                        buildGroups();
                    }).height(46f).pad(-6f).padBottom(0f).row();

                    if(expandedGroup == group){
                        t.table(spawns -> {
                            spawns.field("" + (group.begin + 1), TextFieldFilter.digitsOnly, text -> {
                                if(Strings.canParsePositiveInt(text)){
                                    group.begin = Strings.parseInt(text) - 1;
                                    updateWaves();
                                }
                            }).width(100f);
                            spawns.add("@waves.to").padLeft(4).padRight(4);
                            spawns.field(group.end == never ? "" : (group.end + 1) + "", TextFieldFilter.digitsOnly, text -> {
                                if(Strings.canParsePositiveInt(text)){
                                    group.end = Strings.parseInt(text) - 1;
                                    updateWaves();
                                }else if(text.isEmpty()){
                                    group.end = never;
                                    updateWaves();
                                }
                            }).width(100f).get().setMessageText("∞");
                        }).row();

                        t.table(p -> {
                            p.add("@waves.every").padRight(4);
                            p.field(group.spacing + "", TextFieldFilter.digitsOnly, text -> {
                                if(Strings.canParsePositiveInt(text) && Strings.parseInt(text) > 0){
                                    group.spacing = Strings.parseInt(text);
                                    updateWaves();
                                }
                            }).width(100f);
                            p.add("@waves.waves").padLeft(4);
                        }).row();

                        t.table(a -> {
                            a.field(group.unitAmount + "", TextFieldFilter.digitsOnly, text -> {
                                if(Strings.canParsePositiveInt(text)){
                                    group.unitAmount = Strings.parseInt(text);
                                    updateWaves();
                                }
                            }).width(80f);

                            a.add(" + ");
                            a.field(Strings.fixed(Math.max((Mathf.zero(group.unitScaling) ? 0 : 1f / group.unitScaling), 0), 2), TextFieldFilter.floatsOnly, text -> {
                                if(Strings.canParsePositiveFloat(text)){
                                    group.unitScaling = 1f / Strings.parseFloat(text);
                                    updateWaves();
                                }
                            }).width(80f);
                            a.add("@waves.perspawn").padLeft(4);
                        }).row();

                        t.table(a -> {
                            a.field(group.max + "", TextFieldFilter.digitsOnly, text -> {
                                if(Strings.canParsePositiveInt(text)){
                                    group.max = Strings.parseInt(text);
                                    updateWaves();
                                }
                            }).width(80f);

                            a.add("@waves.max").padLeft(5);
                        }).row();

                        t.table(a -> {
                            a.field((int)group.shields + "", TextFieldFilter.digitsOnly, text -> {
                                if(Strings.canParsePositiveInt(text)){
                                    group.shields = Strings.parseInt(text);
                                    updateWaves();
                                }
                            }).width(80f);

                            a.add(" + ");
                            a.field((int)group.shieldScaling + "", TextFieldFilter.digitsOnly, text -> {
                                if(Strings.canParsePositiveInt(text)){
                                    group.shieldScaling = Strings.parseInt(text);
                                    updateWaves();
                                }
                            }).width(80f);
                            a.add("@waves.shields").padLeft(4);
                        }).row();

                        t.check("@waves.guardian", b -> {
                            Seq<StatusEffect> list = group.effects();
                            if(b) list.addUnique(StatusEffects.boss); else list.remove(StatusEffects.boss);
                            group.setEffects(list);
                            buildGroups();
                        }).padTop(4).update(b -> b.setChecked(group.hasEffect(StatusEffects.boss))).padBottom(8f).row();

                        //предмет в трюме юнита при спавне (юнит вмещает один тип предмета)
                        t.table(a -> {
                            a.add("@waves.items").padRight(8);
                            a.button(b -> {
                                if(group.items != null){
                                    b.image(group.items.item.uiIcon).size(iconSmall).scaling(Scaling.fit);
                                }else{
                                    b.image(Icon.none).size(iconSmall).scaling(Scaling.fit);
                                }
                            }, Styles.squarei, () -> showItems(group)).size(38f).tooltip(group.items != null ? group.items.item.localizedName : "@none");
                            if(group.items != null){
                                a.field(group.items.amount + "", TextFieldFilter.digitsOnly, text -> {
                                    if(Strings.canParsePositiveInt(text) && group.items != null){
                                        group.items.amount = Strings.parseInt(text);
                                    }
                                }).width(80f).padLeft(6f);
                            }
                        }).padTop(4).row();

                        //груз (юниты внутри юнита, как у токсопидов), только для юнитов с вместимостью
                        if(group.type.payloadCapacity > 0){
                            t.table(a -> {
                                a.left();
                                a.add("@waves.payloads").padRight(8);
                                if(group.payloads != null){
                                    for(int pi = 0; pi < group.payloads.size; pi++){
                                        int index = pi;
                                        UnitType pt = group.payloads.get(pi);
                                        if(pt == null) continue;
                                        a.button(b -> b.image(pt.uiIcon).size(iconSmall).scaling(Scaling.fit), Styles.emptyi, () -> {
                                            group.payloads.remove(index);
                                            if(group.payloads.isEmpty()) group.payloads = null;
                                            buildGroups();
                                        }).size(32f).tooltip(pt.localizedName);
                                    }
                                }
                                a.button(Icon.add, Styles.emptyi, () -> showUnits(type -> {
                                    if(type == null) return;
                                    if(group.payloads == null) group.payloads = new Seq<>();
                                    group.payloads.add(type);
                                }, false)).size(32f).tooltip("@waves.payloads.add");
                            }).padTop(4).growX().row();
                        }

                        t.table(a -> {
                            a.add("@waves.team").padRight(8);

                            a.button(b -> b.image(Tex.whiteui).size(iconSmall).update(i -> i.setColor(group.team == null ? Color.clear : group.team.color)), Styles.squarei,
                            () -> MapObjectivesDialog.showTeamSelect(true, team -> group.team = team)).size(38f);
                        }).padTop(0).row();

                        t.table(a -> {
                            a.add("@waves.spawn").padRight(8);

                            a.button("", () -> {
                                if(!checkedSpawns){
                                    //recalculate waves when changed
                                    Vars.spawner.reset();
                                    checkedSpawns = true;
                                }

                                BaseDialog dialog = new BaseDialog("@waves.spawn.select");
                                dialog.cont.pane(p -> {
                                    p.background(Tex.button).margin(10f);
                                    int i = 0;
                                    int cols = 4;
                                    int max = 20;

                                    if(spawner.getSpawns().size >= max){
                                        p.add(Core.bundle.format("waves.spawn.first", max)).colspan(cols).padBottom(4).row();
                                    }

                                    for(Tile spawn : Seq.<Tile>withArrays(spawner.getSpawns(), spawner.getCoreSpawns())){
                                        p.button(spawn.x + ", " + spawn.y, Styles.flatTogglet, () -> {
                                            group.spawn = Point2.pack(spawn.x, spawn.y);
                                            dialog.hide();
                                        }).size(110f, 45f).checked(spawn.pos() == group.spawn);

                                        if(++i % cols == 0){
                                            p.row();
                                        }

                                        //only display first 20 spawns, you don't need to see more.
                                        if(i >= 20){
                                            break;
                                        }
                                    }

                                    if(spawner.getSpawns().isEmpty()){
                                        p.add("@waves.spawn.none");
                                    }else{
                                        p.button("@waves.spawn.all", Styles.flatTogglet, () -> {
                                            group.spawn = -1;
                                            dialog.hide();
                                        }).size(110f, 45f).checked(-1 == group.spawn);
                                    }
                                }).grow();
                                dialog.setFillParent(false);
                                dialog.addCloseButton();
                                dialog.show();
                            }).width(160f).height(36f).get().getLabel().setText(() -> group.spawn == -1 ? "@waves.spawn.all" : Point2.x(group.spawn) + ", " + Point2.y(group.spawn));

                        }).padBottom(8f).row();
                    }
                }).width(340f).pad(8);

                table.row();
            }

            if(table.getChildren().isEmpty() && groups.any()){
                table.add("@none.found");
            }
        }else{
            table.add("@editor.default");
        }

        updateWaves();
    }

    void showUnits(Cons<UnitType> cons, boolean reset){
        BaseDialog dialog = new BaseDialog(reset ? "@waves.filter" : "");
        dialog.cont.pane(p -> {
            p.defaults().pad(2).fillX();
            if(reset){
                p.button(t -> {
                    t.left();
                    t.image(Icon.none).size(8 * 4).scaling(Scaling.fit).padRight(2f);
                    t.add("@settings.resetKey");
                }, () -> {
                    cons.get(null);
                    dialog.hide();
                    buildGroups();
                }).margin(12f);
            }
            int i = reset ? 1 : 0;
            for(UnitType type : content.units()){
                if(type.internal) continue;
                p.button(t -> {
                    t.left();
                    t.image(type.uiIcon).size(8 * 4).scaling(Scaling.fit).padRight(2f);
                    t.add(type.localizedName);
                }, () -> {
                    cons.get(type);
                    dialog.hide();
                    buildGroups();
                }).margin(12f);
                if(++i % 3 == 0) p.row();
            }
        }).growX().scrollX(false);
        dialog.addCloseButton();
        dialog.show();
    }

    /** Полная статистика одной волны: юниты, груз, предметы, статусы, ХП и щиты. */
    void showStats(){
        BaseDialog dialog = new BaseDialog("@waves.stats");
        int[] wave = {Math.max(search, 0)};
        Table out = new Table();

        Runnable rebuild = () -> {
            out.clear();
            out.top().left().defaults().left();

            int w = wave[0];
            int spawnPoints = Math.max(spawner.countSpawns(), 1);
            ObjectIntMap<UnitType> unitCount = new ObjectIntMap<>(), payloadCount = new ObjectIntMap<>();
            ObjectIntMap<Item> itemCount = new ObjectIntMap<>();
            float hp = 0f, payloadHp = 0f, shield = 0f;
            int units = 0, payloadUnits = 0;
            Seq<SpawnGroup> active = new Seq<>();

            for(SpawnGroup g : groups){
                int n = g.getSpawned(w);
                if(n <= 0) continue;
                active.add(g);
                //группа без выбранной точки спавнится на каждой точке
                int mult = g.spawn == -1 ? spawnPoints : 1;
                int total = n * mult;

                units += total;
                unitCount.increment(g.type, 0, total);
                hp += g.type.health * total;
                shield += g.getShield(w) * total;

                if(g.items != null && g.items.amount > 0) itemCount.increment(g.items.item, 0, g.items.amount * total);

                if(g.payloads != null && g.type.payloadCapacity > 0){
                    for(UnitType pt : g.payloads){
                        if(pt == null) continue;
                        payloadUnits += total;
                        payloadCount.increment(pt, 0, total);
                        payloadHp += pt.health * total;
                    }
                }
            }

            out.add(Core.bundle.format("waves.stats.wave", w + 1, spawnPoints)).color(Pal.accent).padBottom(6f).row();

            if(active.isEmpty()){
                out.add("@waves.stats.empty").color(Color.lightGray).row();
                return;
            }

            out.add(Core.bundle.format("waves.stats.units", units)).row();
            out.table(t -> {
                t.left();
                for(var e : unitCount){
                    t.image(e.key.uiIcon).size(iconSmall).scaling(Scaling.fit).padLeft(2f);
                    t.add("x" + e.value).padLeft(2f).padRight(6f);
                }
            }).row();

            out.add(Core.bundle.format("waves.stats.payloads", payloadUnits)).padTop(6f).row();
            if(payloadUnits > 0){
                out.table(t -> {
                    t.left();
                    for(var e : payloadCount){
                        t.image(e.key.uiIcon).size(iconSmall).scaling(Scaling.fit).padLeft(2f);
                        t.add("x" + e.value).padLeft(2f).padRight(6f);
                    }
                }).row();
            }

            out.add("@waves.stats.items").padTop(6f).row();
            if(itemCount.isEmpty()){
                out.add("@none").color(Color.lightGray).row();
            }else{
                out.table(t -> {
                    t.left();
                    for(var e : itemCount){
                        t.image(e.key.uiIcon).size(iconSmall).scaling(Scaling.fit).padLeft(2f);
                        t.add("x" + e.value).padLeft(2f).padRight(6f);
                    }
                }).row();
            }

            out.image().color(Pal.gray).height(3f).growX().pad(8f, 0f, 8f, 0f).row();
            out.add(Core.bundle.format("waves.stats.hp", mindustry.core.UI.formatAmount((long)hp))).row();
            out.add(Core.bundle.format("waves.stats.hp.payload", mindustry.core.UI.formatAmount((long)payloadHp))).row();
            out.add(Core.bundle.format("waves.stats.shield", mindustry.core.UI.formatAmount((long)shield))).row();
            out.add(Core.bundle.format("waves.stats.total", mindustry.core.UI.formatAmount((long)(hp + payloadHp + shield)))).color(Pal.accent).row();
            out.image().color(Pal.gray).height(3f).growX().pad(8f, 0f, 8f, 0f).row();

            //по каждой группе: что несёт каждый юнит, статус, щит
            for(SpawnGroup g : active){
                int n = g.getSpawned(w) * (g.spawn == -1 ? spawnPoints : 1);
                out.table(Tex.button, t -> {
                    t.margin(6f).left().defaults().left();
                    t.table(h -> {
                        h.left();
                        h.image(g.type.uiIcon).size(32f).scaling(Scaling.fit).padRight(4f);
                        h.add("x" + n + " " + g.type.localizedName).color(Pal.accent);
                    }).row();
                    t.add(Core.bundle.format("waves.stats.unit", (int)g.type.health, (int)g.getShield(w), (int)g.type.armor)).row();
                    t.add(Core.bundle.get("waves.stats.status") + " " + (g.effects().isEmpty() ? Core.bundle.get("none") : g.effects().map(e -> e.localizedName).toString(", "))).row();
                    if(g.items != null && g.items.amount > 0){
                        t.add(Core.bundle.format("waves.stats.carry", g.items.item.localizedName, g.items.amount, g.items.amount * n)).row();
                    }
                    if(g.payloads != null && g.payloads.any() && g.type.payloadCapacity > 0){
                        ObjectIntMap<UnitType> per = new ObjectIntMap<>();
                        for(UnitType pt : g.payloads) if(pt != null) per.increment(pt, 0, 1);
                        t.table(pl -> {
                            pl.left();
                            pl.add("@waves.payloads").padRight(4f);
                            for(var e : per){
                                pl.image(e.key.uiIcon).size(iconSmall).scaling(Scaling.fit);
                                pl.add("x" + e.value + " (" + e.value * n + ")").padLeft(2f).padRight(6f);
                            }
                        }).row();
                    }
                }).growX().padBottom(4f).row();
            }
        };

        dialog.cont.table(t -> {
            t.add("@waves.stats.wavenum").padRight(8f);
            t.field((wave[0] + 1) + "", TextFieldFilter.digitsOnly, text -> {
                if(Strings.canParsePositiveInt(text)){
                    wave[0] = Math.max(Strings.parseInt(text) - 1, 0);
                    rebuild.run();
                }
            }).width(100f);
        }).padBottom(6f).row();
        dialog.cont.pane(out).grow().minWidth(420f).scrollX(false);

        rebuild.run();
        dialog.addCloseButton();
        dialog.show();
    }

    void showItems(SpawnGroup group){
        BaseDialog dialog = new BaseDialog("");
        dialog.cont.pane(p -> {
            p.defaults().pad(2).fillX();
            p.button(t -> {
                t.left();
                t.image(Icon.none).size(8 * 4).scaling(Scaling.fit).padRight(2f);
                t.add("@settings.resetKey");
            }, () -> {
                group.items = null;
                dialog.hide();
                buildGroups();
            }).margin(12f);
            int i = 1;
            //все предметы, включая скрытые
            for(Item item : content.items()){
                p.button(t -> {
                    t.left();
                    t.image(item.uiIcon).size(8 * 4).scaling(Scaling.fit).padRight(2f);
                    t.add(item.localizedName);
                }, () -> {
                    group.items = new ItemStack(item, group.items != null ? group.items.amount : Math.max(group.type.itemCapacity, 1));
                    dialog.hide();
                    buildGroups();
                }).margin(12f);
                if(++i % 3 == 0) p.row();
            }
        }).growX().scrollX(false);
        dialog.addCloseButton();
        dialog.show();
    }

    /** Мультивыбор: клик включает/выключает эффект, диалог остаётся открытым. */
    void showEffects(SpawnGroup group){
        BaseDialog dialog = new BaseDialog("");
        dialog.hidden(this::buildGroups);
        dialog.cont.pane(p -> {
            p.defaults().pad(2).fillX();
            p.button(t -> {
                t.left();
                t.image(Icon.none).size(8 * 4).scaling(Scaling.fit).padRight(2f);
                t.add("@settings.resetKey");
            }, () -> {
                group.setEffects(new Seq<>());
                dialog.hide();
            }).margin(12f);
            int i = 1;
            for(StatusEffect effect : content.statusEffects()){
                if(effect == StatusEffects.none) continue;
                p.button(t -> {
                    t.left();
                    t.image(effect.uiIcon).size(8 * 4).scaling(Scaling.fit).padRight(2f);
                    t.add(effect.localizedName);
                }, Styles.flatTogglet, () -> {
                    Seq<StatusEffect> list = group.effects();
                    if(!list.remove(effect)) list.add(effect);
                    group.setEffects(list);
                }).margin(12f).update(b -> b.setChecked(group.hasEffect(effect)));
                if(++i % 3 == 0) p.row();
            }
        }).growX().scrollX(false);
        dialog.addCloseButton();
        dialog.show();
    }

    enum Sort{
        begin(g -> g.begin, g -> g.type.id),
        health(g -> g.type.health),
        type(g -> g.type.id);

        static final Sort[] all = values();

        final Floatf<SpawnGroup> sort, secondary;

        Sort(Floatf<SpawnGroup> sort){
            this(sort, g -> g.begin);
        }

        Sort(Floatf<SpawnGroup> sort, Floatf<SpawnGroup> secondary){
            this.sort = sort;
            this.secondary = secondary;
        }
    }

    void updateWaves(){
        graph.groups = groups;
        graph.rebuild();
    }
}
