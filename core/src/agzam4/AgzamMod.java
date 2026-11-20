package agzam4;

import agzam4.ModWork.KeyBinds;
import agzam4.gameutils.Afk;
import agzam4.gameutils.UnitsVisibility;
import agzam4.industry.IndustryCalculator;
import agzam4.render.light.LightRenderer;
import agzam4.ui.ModSettingsDialog;
import agzam4.utils.PlayerUtils;
import agzam4.utils.ProcessorGenerator;
import agzam4.utils.UnitSpawner;
import arc.Core;
import arc.Events;
import arc.graphics.Texture;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.Trigger;
import mindustry.input.Binding;
import mindustry.world.Tile;

/**
 * Порт мода "Agzam's Mod" (Agzam4, v155.4.a, обновлён до v160.7.b) как нативный пакет клиента - оркестратор по образцу
 * {@link qol.QolSuiteMod}/{@link mi2u.MI2UMod}: конструктор вызывается из mindustry.client.Main.init()
 * ДО Events.fire(ClientLoadEvent), вся инициализация - внутри ClientLoadEvent (контент и UI готовы).
 * <p>
 * ВАЖНО: сюда портирован только реальный остаток мода. НЕ портировано, потому что уже есть
 * в клиенте в другом виде (планка дублей - см. инвентаризацию в отчёте порта):
 * <ul>
 * <li>FireRange (радиусы турелей) - нативные show_turret_ranges/HUD-тумблеры + mi2u TurretZone;</li>
 * <li>CursorTracker (курсоры игроков) - eui PlayerTracker + mi2u enPlayerCursor;</li>
 * <li>DamageNumbers (хп/дпс юнитов) - mi2u/eui хп-бары + mi2u HoverTopTable с DPS;</li>
 * <li>WaveViewer (прогноз волн по точкам спавна) - mi2u WaveInfoMindow (per-spawn) + enSpawnZone;</li>
 * <li>PlayerAI (авто-добыча/стройка + freecam) - нативные MinePath/BuildPath + mi2u FullAI;</li>
 * <li>CustomChat/ConsoleFragment и серверные подсказки команд - конфликт с сильно переписанным
 *     чатом клиента (автодополнение, подпись, клиентские команды); из кастомного чата переехал
 *     только градиент сообщений ({@link agzam4.uiOverride.ChatGradient}, хук в ChatFragment);</li>
 * <li>MobileUI (экранная панель кнопок) - мобильная обвязка, десктопному клиенту не нужна;</li>
 * <li>встроенный редактор карт (ui/mapeditor) - недописанный, в моде был доступен только
 *     из debug-режима; UpdateInfo/Debug/ObjectInspector - обвязка мода.</li>
 * </ul>
 * Ключи настроек и бандла - оригинальные ("agzam4mod.settings.*"), сохранённые настройки sonka
 * подхватываются без миграции. Бинды мода зарегистрированы как нативные KeyBind (категория
 * "agzam4mod" в меню управления): U - утилиты, G - выделение калькулятора, Q - снять выделение,
 * H - скрыть юнитов, Alt - замедленное движение. Дефолты совпадают с оригиналом мода и потому
 * пересекаются с некоторыми биндами клиента (U=run_js, G=select_all_units, Q=clear_building,
 * H=select_all_unit_factories) - ровно как при совместном запуске мода с этим клиентом;
 * при желании перебиндить можно любую из сторон.
 */
public class AgzamMod {

	public static final String name = "agzam4mod";

	public AgzamMod() {
		//self-disable: настоящий Agzam's Mod установлен как обычный мод - не дублируемся.
		if(Vars.mods.locateMod(name) != null) {
			Log.info("[agzam4] External Agzam's Mod is also loaded - baked-in copy is standing down.");
			return;
		}

		Events.on(ClientLoadEvent.class, e -> {
			try {
				init();
			} catch (Throwable t) {
				Log.err("[agzam4] failed to initialize", t);
			}
		});
	}

	void init() {
		loadSprites();

		ModWork.init();
		//вшитая копия регистрирует бинды позже, чем Vars.loadSettings() прогоняет
		//KeyBind.all[].load() - сохранённые значения подтягиваем сами (паттерн mi2u.MBinding)
		KeyBinds.load();

		try {
			try {
				Awt.avalible = Awt.avalible();
			} catch (Error err) {}
		} catch (Throwable t) {}

		Afk.init();
		LightRenderer.init();
		IndustryCalculator.init();
		PlayerUtils.build();

		ModSettingsDialog.addSection();

		Events.run(Trigger.update, () -> {
			if(Vars.world != null && Core.input.keyTap(Binding.select) && !Core.scene.hasMouse()) {
				Tile selected = Vars.world.tileWorld(Core.input.mouseWorldX(), Core.input.mouseWorldY());
				if(selected != null) {
					Events.fire(new agzam4.events.SceneTileTap(selected));
				}
			}

			if(ModWork.acceptKey()) {
				if(ModWork.keyJustDown(KeyBinds.hideUnits)) UnitsVisibility.toggle();
				if(ModWork.keyJustDown(KeyBinds.openUtils)) PlayerUtils.show();
			}

			IndustryCalculator.update();
			UnitSpawner.update();

			if(Vars.player.unit() != null) {
				if(ModWork.settingDef("slow-movement", true) && ModWork.keyDown(KeyBinds.slowMovement) && ModWork.acceptKey()) {
					Vars.player.unit().vel.scl(.5f);
				}
			}
		});

		Events.run(Trigger.uiDrawBegin, () -> {
			IndustryCalculator.drawUi();
			//сброс масштабов шрифтов после мировых надписей (паттерн оригинала)
			agzam4.render.Text.font(mindustry.ui.Fonts.outline);
			agzam4.render.Text.size();
			agzam4.render.Text.font(mindustry.ui.Fonts.def);
			agzam4.render.Text.size();
		});

		Events.run(Trigger.drawOver, () -> {
			ProcessorGenerator.draw();
			UnitSpawner.draw();
			IndustryCalculator.draw();
			Draw.reset();
		});
	}

	/**
	 * Регистрирует спрайты мода в атласе под оригинальными именами "agzam4mod-*".
	 * У обычного мода это делает спрайт-пакер Mods; вшитая копия несёт png в core/assets/agzam4/
	 * (тот же паттерн, что mi2u).
	 */
	private static void loadSprites() {
		for(String spriteName : new String[]{"units", "terrain", "color-box", "circle-shadow"}) {
			String region = name + "-" + spriteName;
			if(Core.atlas.has(region)) continue;
			Texture tex = new Texture(Core.files.internal("agzam4/" + spriteName + ".png"));
			tex.setFilter(Texture.TextureFilter.linear);
			Core.atlas.addRegion(region, new TextureRegion(tex));
		}
	}

	public static TextureRegion sprite(String spriteName) {
		return Core.atlas.find(name + "-" + spriteName);
	}
}
