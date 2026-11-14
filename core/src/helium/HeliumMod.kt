package helium

import arc.Core
import arc.Events
import arc.util.Log
import arc.util.Strings
import helium.graphics.ScreenSampler
import helium.graphics.UiBlur
import mindustry.Vars
import mindustry.client.ui.ModsSettings
import mindustry.game.EventType

/**
 * Порт выбранных фич мода "Helium" (EB-wilson, beta-1.7, Kotlin) как нативный пакет клиента.
 * Оригинальный оркестратор {@code helium.He} + главный класс {@code helium.Helium extends Mod}
 * свёрнуты в этот класс по образцу QolSuiteMod/MI2UMod: конструктор вызывается из
 * mindustry.client.Main.init() ДО Events.fire(ClientLoadEvent), поэтому здесь только слушатели.
 *
 * Что портировано (выбор sonka, 2026-08-19):
 * <ul>
 * <li>gauss-blur фона UI за диалогами (пакет helium.graphics, шейдеры he*.frag) - через подмену
 *     Styles.defaultDialog.stageBackground, с ленивым захватом экрана (см. ScreenSampler);</li>
 * <li>быстрая палитра блоков панели размещения - интегрирована в НАШ PlacementFragment
 *     (см. helium.ui.HeQuickInv), а не заменой фрагмента как в моде: у форка слишком много
 *     якорей (inputTable, blockCatTable, PanelScale, scheme-док) и своих фич палитры;</li>
 * <li>переработанный менеджер/браузер модов (пакет helium.ui.dialogs.mods) - показывается вместо
 *     ванильного ModsDialog, с кнопкой возврата на ванильный.</li>
 * </ul>
 * НЕ портировано (решение sonka): контуры радиусов атаки/эффектов ("есть в ми2"), стаки щитов,
 * а также entity-info панели, modpacker и roulette-элементы UniverseKit.
 *
 * Из UniverseKit завендорено точечно: ScreenSampler (перепись ScreenSamplerJ мода),
 * HeCollapser (свой класс мода), минимальная замена AttachableDialog; markdown-рендер README
 * модов заменён на клиентский StupidMarkupParser.
 *
 * Ключи настроек - оригинальные имена из HeConfig мода с префиксом "he-", секция «Helium» в
 * общей вкладке «Моды». Self-disable guard: при установленном настоящем моде "he" вшитая
 * копия полностью молчит.
 */
class HeliumMod{
    companion object{
        /** Инстанс Helium-менеджера модов (null до ClientLoadEvent или при self-disable). */
        @JvmStatic var heModsDialog: helium.ui.dialogs.mods.HeModsDialog? = null
            private set
    }

    init{
        if(Vars.mods.locateMod("he") != null){
            Log.info("[helium] External Helium mod is also loaded - baked-in copy is standing down.")
        }else{
            HeVars.active = true

            //бинды региструются сразу (до первого открытия диалога управления) + подтяжка сохранённых
            HeBinds.load()

            ScreenSampler.setup()

            Events.on(EventType.ClientLoadEvent::class.java){
                try{
                    helium.ui.HeAssets.ensure()
                    UiBlur.load()

                    //менеджер/браузер модов: подменяет ванильный ModsDialog при включённой настройке
                    heModsDialog = helium.ui.dialogs.mods.HeModsDialog()
                    heModsDialog!!.browser.onInstalledChanged = Runnable{ heModsDialog!!.rebuildMods() }

                    registerSettings()
                    HeVars.loaded = true
                }catch(t: Throwable){
                    Log.err("[helium] failed to initialize", t)
                }
            }

            Events.run(EventType.Trigger.update){
                if(HeVars.loaded){
                    UiBlur.update()
                    //autosave данных мода (слоты палитры/избранное) - как global.autosave() в He.update()
                    HeVars.global.autosave()
                }
            }
        }
    }

    private fun registerSettings(){
        ModsSettings.section("modsec-helium"){ t ->
            t.checkPref(HeVars.ENABLE_BLUR, true)
            t.sliderPref(HeVars.BLUR_LEVEL, 2, 1, 8, 1){ "$it" }
            t.sliderPref(HeVars.BLUR_SCL, 2, 1, 8, 1){ "1/$it" }
            t.sliderPref(HeVars.BLUR_SPACE, 5, 2, 32, 1){ "x" + Strings.fixed(it*0.25f, 2) }
            t.checkPref(HeVars.ENABLE_PLACEMENT, true)
            t.sliderPref(HeVars.BLOCK_COLUMNS, 4, 4, 8, 1){ "$it" }
            t.checkPref(HeVars.ENABLE_MODS_DIALOG, true)
        }
    }
}
