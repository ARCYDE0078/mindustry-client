package mindustry.client

import arc.Core
import mindustry.Vars
import mindustry.gen.Icon
import mindustry.ui.dialogs.BaseDialog

/**
 * Выбор вшитых компонентов клиента на загрузке. Каждый пакет в [Main.init] инстанцируется только если
 * [enabled] true; пропуск конструктора эквивалентен уже существующему self-disable пути (guard
 * `locateMod(...) != null -> return`): конструкторы лишь вешают Events.on-слушатели, так что
 * непроинстанцированный пакет просто не существует для игры (и не тратит время старта/память).
 * Состояние читается ОДИН раз на старте, поэтому изменения применяются после перезапуска.
 *
 * Не входят (всегда включены): scheme и sonkaextras - слишком глубоко вшиты в ядро (DesktopInput,
 * MapListDialog, PlanetDialog, UI и ещё ~15 файлов), их отключение ломало бы ядро, а не пакет.
 */
object ComponentBoot {
    /** Настройка: id отключённых компонентов через запятую. Пусто = включено всё (дефолт безопасен). */
    private const val KEY = "boot-disabled-components"

    /** name/desc - ключи бандла; desc == null = у компонента нет однострочного описания в бандле. */
    class Component(val id: String, val nameKey: String, val descKey: String?, val nameLiteral: String? = null)

    /** Порядок = порядок инстанцирования в Main.init. */
    val components = listOf(
        Component("qol", "client.setting.modsec-qol.category", "client.features.mod.qol.desc"),
        Component("eui", "client.setting.modsec-eui.category", "client.features.mod.eui.desc"),
        Component("campaignutils", "client.setting.modsec-campaignutils.category", "client.features.mod.campaignutils.desc"),
        Component("qolc", "client.setting.modsec-qolc.category", "client.features.mod.qolc.desc"),
        Component("mi2u", "client.features.mod.mi2u.name", "client.features.mod.mi2u.desc"),
        Component("agzam4", "client.setting.modsec-agzam4.category", null),
        Component("mindustrytool", "client.setting.modsec-mindustrytool.category", "client.features.mod.mindustrytool.desc"),
        Component("helium", "client.setting.modsec-helium.category", "client.features.mod.helium.desc"),
        Component("extraeditor", "client.setting.modsec-extraeditor.category", "client.features.mod.extraeditor.desc"),
        Component("newconsole", "client.setting.modsec-newconsole.category", "client.features.mod.newconsole.desc"),
        Component("patcheditor", "client.features.mod.patcheditor.name", "client.features.mod.patcheditor.desc"),
        Component("mu", "client.setting.modsec-mu.category", "client.features.mod.mu.desc"),
        Component("testing", "client.setting.modsec-testing.category", "client.features.mod.testing.desc"),
        Component("tmi", "client.setting.modsec-tmi.category", "client.features.mod.tmi.desc"),
        Component("mobilepause", "client.setting.modsec-mobilepause.category", null),
    )

    private val disabled: Set<String> by lazy {
        Core.settings.getString(KEY, "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /** Включён ли компонент на ЭТОМ запуске (снимок настройки на момент первого обращения). */
    @JvmStatic fun enabled(id: String) = id !in disabled

    private fun stored(): MutableSet<String> =
        Core.settings.getString(KEY, "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()

    /** Диалог-чеклист: чекбоксы пишут настройку сразу, эффект - после перезапуска игры. */
    fun showDialog() {
        val dialog = BaseDialog("@client.boot.title")
        val pending = stored()
        dialog.cont.add("@client.boot.hint").width(560f).wrap().left().padBottom(10f).row()
        dialog.cont.pane { list ->
            list.left().defaults().left().padBottom(6f)
            for (c in components) {
                val name = c.nameLiteral ?: Core.bundle[c.nameKey]
                val desc = c.descKey?.let { Core.bundle[it] } ?: ""
                list.check(name, c.id !in pending) { on ->
                    if (on) pending.remove(c.id) else pending.add(c.id)
                    Core.settings.put(KEY, pending.joinToString(","))
                }.left().row()
                if (desc.isNotEmpty()) list.add(desc).width(520f).wrap().left().padLeft(36f).color(arc.graphics.Color.lightGray).row()
            }
        }.growY().width(600f).get().setScrollingDisabled(true, false)
        dialog.cont.row()
        dialog.cont.add("@client.boot.restart").color(arc.graphics.Color.scarlet).padTop(8f)
        dialog.addCloseButton()
        dialog.buttons.button("@client.boot.reset", Icon.refresh) {
            pending.clear()
            Core.settings.put(KEY, "")
            dialog.hide()
            showDialog()
        }
        dialog.show()
    }
}
