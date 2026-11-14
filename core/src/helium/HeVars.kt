package helium

import arc.Core
import arc.Settings
import arc.files.Fi
import arc.util.Log
import arc.util.Strings

/**
 * Общее состояние вшитого порта мода "Helium" (EB-wilson, beta-1.7, Kotlin).
 * Портированы только выбранные sonka фичи: gauss-blur фона UI, быстрая палитра
 * блоков в панели размещения и переработанный менеджер/браузер модов; контуры
 * радиусов атаки/эффектов и стаки щитов сознательно НЕ портированы.
 *
 * Ключи настроек - оригинальные имена полей конфига мода (HeConfig.kt) с префиксом
 * "he-" (сам мод хранил конфиг в собственном hjson, так что наследовать значения
 * неоткуда - префикс защищает от коллизий в общем settings.bin).
 */
object HeVars{
    /** false = внешний мод "he" установлен как обычный мод - вшитая копия молчит (guard в [HeliumMod]). */
    @JvmStatic var active = false

    /** true после ClientLoadEvent-инициализации (ассеты/стили готовы). */
    @JvmStatic var loaded = false

    const val ENABLE_BLUR = "he-enableblur"
    const val BLUR_LEVEL = "he-blurlevel"
    const val BLUR_SCL = "he-blurscl"
    /** хранится в четвертях: значение*0.25 = blurSpace мода (слайдеры форка целочисленные) */
    const val BLUR_SPACE = "he-blurspace"

    const val ENABLE_PLACEMENT = "he-enablebetterplacement"
    const val BLOCK_COLUMNS = "he-blockcolumns"
    /** не настройка, а запоминаемое состояние кнопки-стрелки: свёрнута ли сетка блоков */
    const val PLACEMENT_FOLD = "he-placementfold"

    const val ENABLE_MODS_DIALOG = "he-enablebettermodsdialog"

    @JvmStatic val blurEnabled: Boolean get() = active && loaded && Core.settings.getBool(ENABLE_BLUR, true)
    @JvmStatic val placementEnabled: Boolean get() = active && loaded && Core.settings.getBool(ENABLE_PLACEMENT, true)
    @JvmStatic val modsDialogEnabled: Boolean get() = active && Core.settings.getBool(ENABLE_MODS_DIALOG, true)

    @JvmStatic val blockColumns: Int get() = if(active && loaded) Core.settings.getInt(BLOCK_COLUMNS, 4) else 4

    /** Папка данных как у настоящего мода: mods/data/he/ - сохранённые sonka страницы палитры и избранное подхватятся. */
    val dataDirectory: Fi by lazy { Core.settings.dataDirectory.child("mods").child("data").child("he") }

    /**
     * Отдельное хранилище данных мода (global_vars.bin) - точная копия He.genGlobal() из мода:
     * слоты быстрой палитры (per-сейв) и избранные моды браузера живут тут, а не в общем
     * settings.bin. Файл ТОТ ЖЕ, что у настоящего мода - данные времён мода наследуются.
     */
    val global: Settings by lazy {
        object : Settings(){
            override fun getSettingsFile(): Fi = dataDirectory.child("global_vars.bin")
            override fun getBackupFolder(): Fi = dataDirectory.child("global_backups")
            override fun getBackupSettingsFile(): Fi = dataDirectory.child("global_vars.bin.bak")

            @Synchronized
            override fun load(){
                try{
                    loadValues()
                }catch(error: Throwable){
                    //не роняем клиент из-за битого файла данных мода - как в оригинале
                    Log.err("[helium] Error in load: " + Strings.getStackTrace(error))
                    hasErrored = true
                }
                loaded = true
            }

            @Synchronized
            override fun forceSave(){
                if(!loaded) return
                try{
                    saveValues()
                }catch(error: Throwable){
                    Log.err("[helium] Error in forceSave to " + settingsFile + ":\n" + Strings.getStackTrace(error))
                    hasErrored = true
                }
                modified = false
            }
        }.also{
            it.setAutosave(true)
            it.setDataDirectory(dataDirectory)
            it.load()
        }
    }
}
