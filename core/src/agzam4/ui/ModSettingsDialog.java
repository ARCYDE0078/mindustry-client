package agzam4.ui;

import agzam4.AgzamMod;
import agzam4.Awt;
import agzam4.ModWork;
import agzam4.gameutils.Afk;
import agzam4.render.light.LightRenderer;
import agzam4.render.light.LightRenderer.LightTypes;
import agzam4.uiOverride.ChatGradient;
import agzam4.utils.Bungle;
import agzam4.utils.Prefs;
import arc.Core;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.scene.event.Touchable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.client.ui.ModsSettings;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.*;
import mindustry.ui.dialogs.SettingsMenuDialog.*;
import mindustry.world.Block;
import mindustry.world.meta.BuildVisibility;

/**
 * Секция "Agzam's Mod" общей вкладки настроек «Моды» (см. {@link ModsSettings}). Урезана
 * относительно оригинала: выброшены секции непортированных фич (updates/апдейт-чекер, cursors,
 * кастомный чат/консоль, мобильный UI, report-bugs) - остались unlock, калькулятор, AFK,
 * кастомный рендер и градиент чата. Ключи настроек - оригинальные.
 * <p>
 * Виджеты мода - сырые (свои чекбоксы через Prefs.settings, пикер градиента, группа кнопок
 * рендера), а сырой {@code table.add(...)} на верхнем уровне билдера секции отключил бы
 * поисковую строку ВСЕЙ общей вкладки (см. javadoc {@link ModsSettings}). Поэтому весь блок
 * завёрнут в один tracked-{@code Setting}: внутри {@code Setting.add(...)} сырые add'ы легальны
 * (rebuild() их ожидает), таблица пересобирается при каждом rebuild - состояние виджеты и так
 * читают из Prefs/статики при построении.
 */
public class ModSettingsDialog {

	static TextureRegion colorBox;

	static void buildContent(Table table) {
		table.defaults().left();

		if(colorBox == null) colorBox = AgzamMod.sprite("color-box");

		category(table, "unlock");

		table.check(Bungle.settings("unlock-content"), false, b -> {
			if(b) showHiddenContent();
			else hideHiddenContent();
		}).colspan(4).pad(10).padBottom(4).left().row();

		table.check(Bungle.settings("unlock-blocks"), false, b -> {
			if(b) unlockBlocksContent();
			else lockBlocksContent();
		}).colspan(4).pad(10).padBottom(4).left().row();

		category(table, "calculations");
		addCheck(table, "show-blocks-tooltip");
		addCheck(table, "selection-calculations");
		addCheck(table, "buildplans-calculations");
		addCheck(table, "show-units-health");

		category(table, "movement");
		addCheck(table, "slow-movement");

		category(table, "afk");

		try {
			Afk.afkAvalible = true;
			if(Awt.avalible && !Vars.mobile) {
				table.field(Afk.getCustomAfk(), t -> {
					Core.settings.put("agzam4mod.afk-start", t);
				}).tooltip(Bungle.afk("automessage-start-tooltip")).width(Core.scene.getWidth()/2f).row();
				addCheck(table, "afk.afk-ping");
				table.labelWrap(() -> Strings.format(Bungle.afk("default-names"), Afk.baseName(), Afk.ruName())).growX().colspan(4).pad(10).padBottom(4).row();

				table.labelWrap(() -> Strings.format(Bungle.afk("custom-names"), Afk.baseName(), Afk.ruName())).growX().colspan(4).pad(10).padBottom(4).row();
				table.area(Afk.names(), s -> Afk.names(s)).growX().colspan(4).pad(10).padBottom(4).minHeight(250f).row();
			} else {
				Afk.afkAvalible = false;
				table.add(Bungle.afk("err")).color(Color.red).colspan(4).pad(10).padBottom(4).row();
			}
		} catch (Throwable e) {
			Afk.afkAvalible = false;
			table.add(Bungle.afk("err")).color(Color.red).colspan(4).pad(10).padBottom(4).row();
		}

		category(table, "custom-render");

		table.table(Tex.button, tg -> {
			tg.margin(10f);
			var group = new ButtonGroup<>();
			var style = Styles.flatTogglet;

			tg.button(Bungle.settings("custom-render.disabled"), style, () -> {
				LightRenderer.set(null);
			}).growX().fillX().group(group).checked(LightRenderer.type == null).height(35f).row();

			for (var type : LightTypes.values()) {
				tg.button(Bungle.settings("custom-render." + type.kebab()), style, () -> {
					LightRenderer.set(type);
				}).growX().fillX().group(group).checked(LightRenderer.type == type).height(35f).row();
			}
		}).fillX().pad(6).colspan(20).padTop(0).padBottom(10).row();

		sliderInt(table, "custom-render.opacity", 50, 0, 100, i -> {
			LightRenderer.opacity = i/100f;
			return i + "%";
		});

		createMessagesGradientPicker(table);

		table.row();
	}

	public static void sliderInt(Table table, String name, int def, int min, int max, StringProcessor s) {

		Slider slider = new Slider(min, max, 1, false);

		slider.setValue(Prefs.settings.integer(name, def));

		Label value = new Label("", Styles.outlineLabel);
		Table content = new Table();
		content.add(Bungle.settings(name), Styles.outlineLabel).left().growX().wrap();
		content.add(value).padLeft(10f).right();
		content.margin(3f, 33f, 3f, 33f);
		content.touchable = Touchable.disabled;

		slider.changed(() -> {
			Prefs.settings.put(name, (int)slider.getValue());
			value.setText(s.get((int)slider.getValue()));
		});

		slider.change();

		var stack = table.stack(slider, content);

		stack.fillX().pad(6).colspan(4).padTop(0).padBottom(10).row();

		Vars.ui.addDescTooltip(stack.get(), Bungle.settingsTooltip(name));

		table.row();
	}

	private static void addCheck(Table table, String settings) {
		check(table, settings, null);
	}

	private static void rebuildMessagesColors(Table table) {
		table.clearChildren();
		for (int i = 0; i < ChatGradient.messageColors.size; i++) {
			final int id = i;
			final Color color = ChatGradient.messageColors.get(id);
			Table row = table.row();
			row.table(new TextureRegionDrawable(colorBox).tint(color)).size(50, 50);
			row.button("" + Iconc.pick, Styles.grayt, () -> {
				Vars.ui.picker.show(color, false, c -> {
					color.set(c);
					rebuildMessagesColors(table);
					ChatGradient.save();
				});
			}).size(50, 50);
			row.button("" + Iconc.cancel, Styles.grayt, () -> {
				ChatGradient.messageColors.remove(id);
				rebuildMessagesColors(table);
				ChatGradient.save();
			}).size(50, 50);
		}
	}

	private static void createMessagesGradientPicker(Table mainTable) {
		mainTable.label(() -> Bungle.settings("ui.messages-gradient")).growX().colspan(4).pad(10).padBottom(10).row();

		Table colorsTable = mainTable.table().get();

		rebuildMessagesColors(colorsTable);
		mainTable.row();
		mainTable.button(Iconc.add + "", () -> {
			Vars.ui.picker.show(ChatGradient.messageColors.size == 0 ? Color.sky : ChatGradient.messageColors.peek(), false, color -> {
				ChatGradient.messageColors.add(new Color(color));
				rebuildMessagesColors(colorsTable);
				ChatGradient.save();
			});
		}).growX().pad(20).padBottom(4);

		mainTable.row();

		mainTable.label(() -> Bungle.settings("ui.messages-gradient-trigger")).growX().colspan(4).pad(10).padBottom(10).row();
		mainTable.field(ChatGradient.colorTrigger, t -> {
			ChatGradient.colorTrigger = t;
			Prefs.settings.put("messages-gradient-trigger", t);
		}).row();
	}

	private static void check(Table table, String settings, Cons<Boolean> listener) {
		check(table, settings, true, listener);
	}

	private static void check(Table table, String settings, boolean def, Cons<Boolean> listener) {
		String tooltip = Bungle.settingsTooltip(settings);
		var cell = table.check(Bungle.settings(settings), ModWork.settingDef(settings, def), b -> {
			Prefs.settings.put(settings, b);
			if(listener != null) listener.get(b);
		}).colspan(4).pad(10).padBottom(4).left();
		cell.row();
		if(Vars.mobile) {
			Vars.ui.addDescTooltip(cell.get(), tooltip);
		} else {
			cell.tooltip(tooltip);
		}
	}

	private static void category(Table table, String category) {
		table.add(Bungle.category(category)).color(Pal.accent).colspan(4).pad(10).padBottom(4).row();
		table.image().color(Pal.accent).fillX().height(3).pad(6).colspan(4).padTop(0).padBottom(10).row();
	}

	private static ObjectSet<UnlockableContent> hiddenContent = new ObjectSet<UnlockableContent>();

	private static void hideHiddenContent() {
		Vars.content.units().each(c -> c.hidden = hiddenContent.remove(c));
		Vars.content.items().each(c -> c.hidden = hiddenContent.remove(c));
		Vars.content.liquids().each(c -> c.hidden = hiddenContent.remove(c));
	}

	private static void showHiddenContent() {
		Vars.content.units().each(u -> {
			if(u.hidden) hiddenContent.add(u);
			u.hidden = false;
		});
		Vars.content.items().each(i -> {
			if(i.hidden) hiddenContent.add(i);
			i.hidden = false;
		});
		Vars.content.liquids().each(l -> {
			if(l.hidden) hiddenContent.add(l);
			l.hidden = false;
		});
	}

	private static ObjectMap<Block, BuildVisibility> blocksBuildVisibility = new ObjectMap<>();

	private static void lockBlocksContent() {
		Vars.content.blocks().each(b -> {
			var v = blocksBuildVisibility.remove(b);
			if(v == null) return;
			b.buildVisibility = v;
		});
	}

	private static void unlockBlocksContent() {
		Vars.content.blocks().each(b -> {
			blocksBuildVisibility.put(b, b.buildVisibility);
			b.buildVisibility = BuildVisibility.shown;
		});
	}

	public static void addSection() {
		ModsSettings.section("modsec-agzam4", st -> st.pref(new SettingsTable.Setting("agzam4-settings"){
			{
				//title участвует только в ранжировании поиска - пусть весь блок находится по имени мода
				title = Bungle.settings("name");
			}

			@Override
			public void add(SettingsTable t){
				Table inner = new Table();
				buildContent(inner);
				t.add(inner).left();
				t.row();
			}
		}));
	}
}
