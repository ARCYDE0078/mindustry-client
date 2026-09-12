package mindustrytool;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.func.Prov;
import arc.scene.style.Drawable;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Reflect;
import mindustry.Vars;
import mindustry.client.ui.ModsSettings;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.net.Packet;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import mindustrytool.features.Feature;
import mindustrytool.features.FeatureManager;
import mindustrytool.features.auth.AuthService;
import mindustrytool.features.background.BackgroundFeature;
import mindustrytool.features.browser.map.MapBrowserFeature;
import mindustrytool.features.browser.schematic.SchematicBrowserFeature;
import mindustrytool.features.chat.global.ChatFeature;
import mindustrytool.features.chat.translation.ChatTranslationFeature;
import mindustrytool.features.music.MusicFeature;
import mindustrytool.features.music.dto.MusicRegisterEvent;
import mindustrytool.features.playerconnect.PlayerConnectFeature;
import mindustrytool.features.quickaccess.QuickAccessFeature;
import mindustrytool.features.settings.FeatureSettingDialog;
import mindustrytool.features.smartdrill.SmartDrillFeature;
import mindustrytool.features.smartupgrade.SmartUpgradeFeature;
import mindustrytool.features.time.TimeControlFeature;
import mindustrytool.services.TapListener;

/**
 * Порт мода Mindustry Tool (Sharlotte/MindustryVN, v4.59.1-v8) как вшитый пакет.
 * Оркестратор вместо mindustrytool.Main (extends Mod): создаётся из
 * mindustry.client.Main.kt ПОСЛЕ SchemeSizeMod, в конструкторе только вешает
 * ClientLoadEvent — весь UI существует лишь после него.
 *
 * Что НЕ поехало из мода и почему (инвентаризация против клиента):
 *  - Autoplay             — у клиента родная Navigation (Mine/Build/Repair/AssistPath) + mi2u FullAI;
 *  - God Mode             — то же самое умеет scheme.tools.admins (Internal + SlashJs) с хоткеями;
 *  - HealthBar            — mi2u enUnitHpBar/enBlockHpBar, eui HealthShieldBar;
 *  - Pathfinding display  — родной превью путей волн (Client.kt, spawntime/traveltime) + mi2u enUnitPath;
 *  - Range display        — родные Turret Ranges (хоткеи + HUD) + mi2u range zones;
 *  - Team resources       — родной CoreItemsDisplay + mi2u CoreInfo + qol resourceforecast/resourcesviewer;
 *  - Toggle rendering     — mi2u disableUnit/Bullet/… + родные hidingUnits/hidingBlocks;
 *  - Wave preview         — mi2u WaveInfoMindow + scheme WaveApproachingDialog;
 *  - Item visualizer      — mi2u enDistributionReveal (в моде и так закомментирован);
 *  - Pretty chat          — в моде закомментирован, у клиента свой богатый ChatFragment;
 *  - Progress display     — mi2u уже рисует прогресс фабрик/реконструкторов;
 *  - maxSchematicSize=4000 — у форка родные 1024 + слайдер mi2u;
 *  - Updater/CrashReport/Changelog/дев-вкладка — инфраструктура стороннего мода;
 *  - ServerService        — молча дописывал сервера mindustry-tool.com в пользовательский список серверов;
 *  - SaveSync             — облачная синхронизация сохранений: апстрим удаляет файлы на сервере ДО
 *                           заливки новых и синхронизирует settings между устройствами — риск потери
 *                           данных, не тащим без переработки.
 */
public class MindustryToolMod {
    public static Fi imageDir = Vars.dataDirectory.child("mindustry-tool-caches");
    public static Fi mapsDir = Vars.dataDirectory.child("mindustry-tool-maps");
    public static Fi schematicDir = Vars.dataDirectory.child("mindustry-tool-schematics");
    public static Fi backgroundsDir = Vars.dataDirectory.child("mindustry-tool-backgrounds");
    public static Fi musicsDir = Vars.dataDirectory.child("mindustry-tool-musics");

    private static ObjectMap<Class<?>, Prov<? extends Packet>> packetReplacements = new ObjectMap<>();

    public static FeatureSettingDialog featureSettingDialog;

    public static void registerPacketPlacement(Class<?> clazz, Prov<? extends Packet> prov) {
        packetReplacements.put(clazz, prov);
    }

    public MindustryToolMod() {
        // self-disable: настоящий мод установлен — вшитая копия молчит
        if (Vars.mods.locateMod("mindustry-tool") != null) {
            Log.info("[mindustrytool] External Mindustry Tool mod is also loaded - baked-in copy is standing down.");
            return;
        }

        Events.on(ClientLoadEvent.class, e -> {
            try {
                MdtKeybinds.load();

                featureSettingDialog = new FeatureSettingDialog();

                addCustomButtons();
                setup();
                buildSettingsCategory();
            } catch (Throwable err) {
                Log.err("[mindustrytool] init failed", err);
            }
        });
    }

    private void setup() {
        imageDir.mkdirs();
        mapsDir.mkdirs();
        backgroundsDir.mkdirs();
        musicsDir.mkdirs();
        schematicDir.mkdirs();

        checkDirVersion(imageDir, 1);
        checkDirVersion(mapsDir, 1);
        checkDirVersion(schematicDir, 1);

        AuthService.getInstance().init();
        TapListener.getInstance().init();

        FeatureManager.getInstance().register(//
                new MapBrowserFeature(), //
                new SchematicBrowserFeature(), //
                new PlayerConnectFeature(), //
                new QuickAccessFeature(), //
                new ChatFeature(), //
                new ChatTranslationFeature(), //
                new SmartDrillFeature(), //
                new SmartUpgradeFeature(), //
                new BackgroundFeature(), //
                new MusicFeature(), //
                new TimeControlFeature());

        initFeatures();

        Events.fire(new MusicRegisterEvent());
        Events.fire(new MdtInitEvent());
    }

    private void initFeatures() {
        FeatureManager.getInstance().init();

        // Подмена пакетов (перевод чата) — тем же механизмом, что у мода: список
        // packetProvs приватный и статический в mindustry.net.Net, Reflect его достаёт.
        Seq<Prov<? extends Packet>> packetProvs = Reflect.get(Vars.net, "packetProvs");

        packetProvs.replace(packet -> {
            Class<?> clazz = packet.get().getClass();
            if (packetReplacements.containsKey(clazz)) {
                Log.info("Replace packet @ to @", clazz.getSimpleName(),
                        packetReplacements.get(clazz).get().getClass().getSimpleName());
                return packetReplacements.remove(clazz);
            }

            return packet;
        });

        for (Class<?> clazz : packetReplacements.keys()) {
            Log.info("Packet @ not found", clazz.getSimpleName());
        }
    }

    private void addCustomButtons() {
        Core.app.post(() -> {
            try {
                Vars.ui.menufrag.addButton("Mindustry Tool", Utils.icons("mod.png"), () -> featureSettingDialog.show());
            } catch (Exception err) {
                Log.err(err);
            }
        });
    }

    /**
     * Секция "Mindustry Tool" общей вкладки «Моды» (см. {@link ModsSettings}) — по образцу
     * остальных вшитых пакетов. Кнопка открывает главный диалог мода, чекбоксы фич идут через
     * FeatureManager.applyEnabled, чтобы срабатывали onEnable/onDisable.
     */
    private void buildSettingsCategory() {
        ModsSettings.section("modsec-mindustrytool", table -> {
            table.pref(new ButtonSetting("mindustrytool-open",
                    Core.bundle.get("mindustrytool.settings.open", "Open Mindustry Tool"),
                    () -> featureSettingDialog.show()));

            for (Feature feature : FeatureManager.getInstance().getFeatures()) {
                table.pref(new FeatureToggleSetting(feature));
            }
        });
    }

    /** Как qol.core.ButtonSetting: через pref(), иначе категория теряет поисковую строку. */
    static class ButtonSetting extends SettingsTable.Setting {
        final Runnable action;

        ButtonSetting(String name, String title, Runnable action) {
            super(name);
            this.title = title;
            this.action = action;
        }

        @Override
        public void add(SettingsTable table) {
            table.button(title, action).growX().height(50f).pad(4f);
            table.row();
        }
    }

    /** Чекбокс фичи с заголовком из метаданных (ключи настроек — оригинальные, из мода). */
    static class FeatureToggleSetting extends SettingsTable.Setting {
        final Feature feature;

        FeatureToggleSetting(Feature feature) {
            super(feature.getSettingKey());
            this.feature = feature;
            this.title = Utils.getString(feature.getMetadata().name());
            this.description = Utils.getString(feature.getMetadata().description());
        }

        @Override
        public void add(SettingsTable table) {
            var check = table.check(title, feature.isEnabled(), checked -> {
                Core.settings.put(name, checked);
                FeatureManager.getInstance().applyEnabled(feature, checked);
            }).left().padTop(4f).get();
            check.left();
            addDesc(check);
            table.row();
        }
    }

    private int readDirVersion(Fi dir) {
        try {
            Fi versionFile = dir.child("version.txt");
            if (versionFile.exists()) {
                return Integer.parseInt(versionFile.readString());
            } else {
                return -1;
            }
        } catch (Exception err) {
            return 0;
        }
    }

    private void writeDirVersion(Fi dir, int version) {
        try {
            dir.emptyDirectory(false);
            Fi versionFile = dir.child("version.txt");
            versionFile.writeString(version + "");
        } catch (Exception err) {
            Log.err("[mindustrytool] failed to write dir version", err);
        }
    }

    private void checkDirVersion(Fi dir, int expectedVersion) {
        try {
            int version = readDirVersion(dir);
            if (version == -1) {
                dir.mkdirs();
            }

            if (version != expectedVersion) {
                writeDirVersion(dir, expectedVersion);
            }
        } catch (Exception err) {
            Log.err("Check dir version failed", err);
        }
    }
}
