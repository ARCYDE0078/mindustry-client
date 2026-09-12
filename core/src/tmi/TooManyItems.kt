package tmi

import arc.Core
import arc.Events
import arc.util.Log
import arc.util.Time
import mindustry.Vars
import mindustry.game.EventType.ClientLoadEvent
import tmi.recipe.RecipeItemManager
import tmi.recipe.RecipesManager
import tmi.recipe.parser.*
import tmi.ui.Cursor
import tmi.ui.EntryAssigner
import tmi.ui.TmiUI
import tmi.util.KeyBinds
import tmi.util.TmiAssets

/**
 * Порт мода "Too Many Items" (EB-wilson, v3.3, Kotlin) как нативный пакет клиента - браузер
 * рецептов и производственных цепочек в духе NEI/JEI: для каждого предмета/жидкости/юнита/
 * блока - чем производится, где используется, что делает фабрика (парсеры для всех ванильных
 * типов блоков: крафтеры, дрели, насосы, генераторы, реакторы, фабрики юнитов, реконструкторы,
 * турели с боеприпасами, стоимость постройки), плюс «калькулятор схем» - редактор графа
 * рецептов с балансировкой количеств, автосоединением, статистикой и экспортом в PNG/текст.
 * Чужие моды могут добавлять рецепты через recipes.json / recipeEntry / recipeScript (ModAPI
 * сохранён, включая JS-мост TMI в общий Rhino-scope).
 * <p>
 * ИНВЕНТАРИЗАЦИЯ: agzam4 IndustryCalculator считает баланс ПОСТРОЕННОГО по выделению - другой
 * сценарий, TMI - справочник рецептов и планировщик ещё не построенного; у mi2u рецептов нет.
 * Пересечений нет - порт почти целиком. SKIP: пакет util.ui (Behavior/ElementBuilder/Modifier/
 * ObservableVar - мини-фреймворк декларативного UI, нигде в моде не используется и тянет
 * kotlin-reflect, которого в core нет), пакет ui-xxx.png-рамки (не используются кодом), base64-картинки
 * внутри calculator-help.md (3 МБ; вырезаны при копировании, текст оставлен).
 * <p>
 * Зависимости EB-wilson: из UniverseKit мод берёт ТРИ вещи - markdown-рендер (заменён клиентским
 * StupidMarkupParser, как в helium-порте), reflection-хелпер accessField (заменён arc.util.
 * Reflect) и больше ничего; helium-овские ScreenSampler/коллапсеры TMI не нужны, так что ничего
 * из core/src/helium не дублируется. pinyin4j (группировка иероглифов по пиньиню в сортировке
 * по имени) не вендорится - не-латинские имена падают в группу «#».
 * <p>
 * Адаптация по образцу остальных вшитых пакетов: без extends Mod, конструктор зовётся из
 * mindustry.client.Main.init(); self-disable guard по id мода "tmi" (иначе две кнопки TMI в
 * базе данных и два плавающих входа). Парсеры регистрируются сразу (им нужен только класс-
 * лист), всё остальное - по ClientLoadEvent: {@code init()} мода (курсоры, бинды, ModAPI, сбор
 * рецептов по всему контенту, постройка диалогов) и через кадр - входы (кнопка в базе данных,
 * кнопка в ContentInfoDialog, плавающая кнопка) + afterInit ModAPI. Настройки - секция «Too Many
 * Items» вкладки «Моды» (оригинальные ключи tmi_*), хоткей tmi_hot_key в категории «tmi» меню
 * управления. Спрайты - core/assets/tmi/ui/ (пакуются в Core.atlas, см. TmiAssets.loadSprites),
 * документы - core/assets/tmi/documents/.
 * <p>
 * 2026-09-12: синк с upstream master (после тега 3.2 - fix для Mindustry 159 + мелкие правки).
 * Портировано: баги ModAPI.readJsonAPI (return вместо continue рвал загрузку рецептов всех
 * следующих модов; recipeInfos вместо it/recipeInfo - поля предмета/craftTime из recipes.json
 * молча брались с "неправильного" уровня JSON и всегда уходили в дефолт), RecipeItem.compareTo
 * (сравнение по typeTag вместо typeID), и новая фича RecipeGraph.requiredMods - калькулятор
 * пишет в .shd список модов-источников рецептов графа и при загрузке кидает понятную
 * MissingModException вместо мусора в интерфейсе, если нужного мода сейчас нет. НЕ портировано:
 * upstream заодно переименовал RecipeItem.ownMod -> mod и поменял состав RecipeGraph.write/read
 * (Vars.platform.showFileChooser -> FileChooser.FileChooserParams, Seq.groupBy/flatMap ->
 * ObjectMap/Seq) - у нас это уже своё: tmi.util.showFileChooser и .asIterable() перед groupBy/
 * flatMap (см. комментарии на местах) решают ту же проблему совместимости с движком этого
 * форка, так что оставлено как есть. Также не тронут порядок/состав Recipe.flattenID (upstream
 * убрал amount из ID и поменял местами materials/productions без объяснения в коммите - слишком
 * рискованно трогать вслепую, т.к. flattenID - это ключ поиска рецепта при загрузке .shd).
 */
class TooManyItems {
  companion object {
    @JvmField
    var recipesManager: RecipesManager = RecipesManager()
    @JvmField
    var itemsManager: RecipeItemManager = RecipeItemManager()
    @JvmField
    var api: ModAPI = ModAPI()
    @JvmField
    val binds = KeyBinds()

    /** Guard пройден и ClientLoadEvent отработал (строка в FeaturesDialog). */
    @JvmStatic
    var enabled = false
      private set
  }

  init {
    if (Vars.mods.locateMod("tmi") != null) {
      Log.info("[tmi] External Too Many Items mod is also loaded - baked-in copy is standing down.")
    } else {
      ConsumerParser.registerVanillaConsumeParser()
      registerDefaultParser()

      Events.on(ClientLoadEvent::class.java) {
        try {
          init()
          enabled = true
        } catch (e: Throwable) {
          Log.err("[tmi] Failed to initialize Too Many Items", e)
          return@on
        }

        Time.runTask(0f) {
          try {
            EntryAssigner.assign()
            registerSettings()
            api.afterInit()
          } catch (e: Throwable) {
            Log.err("[tmi] Failed to attach Too Many Items entries", e)
          }
        }
      }
    }
  }

  private fun registerDefaultParser() {
    //Parser for the vanilla game factory blocks
    recipesManager.registerParser(GenericCrafterParser())
    recipesManager.registerParser(UnitFactoryParser())
    recipesManager.registerParser(ReconstructorParser())
    recipesManager.registerParser(UnitAssemblerParser())
    recipesManager.registerParser(ConstructorParser())
    recipesManager.registerParser(PumpParser())
    recipesManager.registerParser(SolidPumpParser())
    recipesManager.registerParser(FrackerParser())
    recipesManager.registerParser(DrillParser())
    recipesManager.registerParser(BeamDrillParser())
    recipesManager.registerParser(SeparatorParser())
    recipesManager.registerParser(GeneratorParser())
    recipesManager.registerParser(ConsumeGeneratorParser())
    recipesManager.registerParser(ImpactReactorParser())
    recipesManager.registerParser(HeatGeneratorParser())
    recipesManager.registerParser(ThermalGeneratorParser())
    recipesManager.registerParser(VariableReactorParser())
    recipesManager.registerParser(HeatCrafterParser())
    recipesManager.registerParser(HeatProducerParser())
    recipesManager.registerParser(AttributeCrafterParser())
    recipesManager.registerParser(WallCrafterParser())
    recipesManager.registerParser(ItemTurretParser())
    recipesManager.registerParser(LiquidTurretParser())
    recipesManager.registerParser(ContinuousTurretParser())
    recipesManager.registerParser(ContinuousLiquidTurretParser())
    recipesManager.registerParser(PowerTurretParser())
    recipesManager.registerParser(BuildingParser())
  }

  /** Тело Mod.init() оригинала - у мода оно шло после загрузки контента, здесь то же место: ClientLoadEvent. */
  private fun init() {
    TmiAssets.loadSprites()
    Cursor.init()
    binds.load()
    api.init()

    recipesManager.init()

    TmiUI.init()
  }

  private fun registerSettings() {
    mindustry.client.ui.ModsSettings.section("modsec-tmi") { t ->
      t.checkPref("tmi_button", true)
      t.checkPref("tmi_items_pane", false)
      t.sliderPref("tmi_gridSize", 150, 50, 300, 10) { i -> i.toString() }
      t.checkPref("tmi_enable_preview", false)
    }
  }
}
