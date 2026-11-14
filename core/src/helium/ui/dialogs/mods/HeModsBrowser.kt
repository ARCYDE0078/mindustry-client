package helium.ui.dialogs.mods

import arc.Core
import arc.func.Cons
import arc.graphics.Color
import arc.math.Interp
import arc.math.geom.Rect
import arc.scene.Group
import arc.scene.event.Touchable
import arc.scene.style.Drawable
import arc.scene.ui.Dialog
import arc.scene.ui.Image
import arc.scene.ui.ScrollPane
import arc.scene.ui.TextField
import arc.scene.ui.Tooltip
import arc.scene.ui.layout.Cell
import arc.scene.ui.layout.Scl
import arc.scene.ui.layout.Table
import arc.struct.ObjectMap
import arc.struct.ObjectSet
import arc.util.Align
import arc.util.Log
import arc.util.Scaling
import arc.util.serialization.Jval
import helium.ui.ButtonEntry
import helium.ui.HeAssets
import helium.ui.HeCollapser
import helium.ui.UIUtils
import helium.ui.UIUtils.line
import helium.ui.dialogs.mods.ModsDialogHelper.addTip
import helium.ui.dialogs.mods.ModsDialogHelper.buildModAttrIcons
import helium.ui.dialogs.mods.ModsDialogHelper.buildModAttrList
import helium.ui.dialogs.mods.ModsDialogHelper.buildStars
import helium.ui.dialogs.mods.ModsDialogHelper.buildStatus
import helium.ui.dialogs.mods.ModsDialogHelper.checkStatus
import helium.ui.dialogs.mods.ModsDialogHelper.getModList
import helium.ui.dialogs.mods.ModsDialogHelper.iconUrl
import helium.ui.dialogs.mods.ModsDialogHelper.markupTable
import helium.ui.dialogs.mods.ModsDialogHelper.shortDesc
import helium.ui.dialogs.mods.ModsDialogHelper.showDownloadAllDialog
import helium.ui.dialogs.mods.ModsDialogHelper.showDownloadModDialog
import helium.ui.dialogs.mods.ModsDialogHelper.switchBut
import helium.ui.dialogs.mods.ModsDialogHelper.tryCompareVersion
import helium.util.ModStat
import helium.util.addEventBlocker
import mindustry.Vars
import mindustry.gen.Icon
import mindustry.gen.Tex
import mindustry.graphics.Pal
import mindustry.mod.ModListing
import mindustry.ui.FileChooser
import mindustry.ui.Styles
import sonkaextras.packs.PackScan
import sonkaextras.packs.PackUi
import mindustry.ui.dialogs.BaseDialog
import kotlin.math.max
import kotlin.math.min

/**
 * Порт helium.ui.dialogs.mods.HeModsBrowser (Helium beta-1.7, EB-wilson): браузер модов карточками в
 * несколько колонок, с поиском, сортировкой по звёздам/дате, фильтром невалидных и ЛОКАЛЬНЫМИ
 * папками избранного (звёздочка на карточке; хранятся в mods/data/he/global_vars.bin - см.
 * [LocalFavoritesStore]). У каждой папки: переименовать, удалить, «установить все», экспорт JSON;
 * общий импорт JSON (при совпадении имени - оставить обе/слить/заменить).
 * Папка-зеркало звёзд GitHub-аккаунта (вход через OAuth) не перенесена - это внешний сервер и токен.
 * Листинг - ванильный mods.json (Vars.modJsonURLs), иконки - Anuken/MindustryMods/icons.
 */
class HeModsBrowser : BaseDialog(Core.bundle["mods.browser"]){
    companion object{
        private fun Table.cullTable(background: Drawable? = null, build: Cons<Table>? = null) =
            add(CullTable(background).also{ t -> build?.get(t) })
    }

    /** Дёргается после установки/обновления мода из браузера - менеджер перечитывает свой список. */
    var onInstalledChanged: Runnable? = null

    private lateinit var rebuildList: () -> Unit

    /** Карточки основного списка: перестройка при смене фильтра берёт готовые, а не создаёт сотни заново. */
    private val browserTabs = ObjectMap<ModListing, Table>()

    //избранное: у каждой папки свои клоны карточек (раскрытие/прокрутка описания независимы от основного списка)
    private val favorites = ArrayList<LocalFavorites>()
    private var favoritesLoaded = false
    private val favoriteTabs = ObjectMap<LocalFavorites, ObjectMap<ModListing, Table>>()
    private val expandedFavorites = ObjectSet<LocalFavorites>()
    /** Моды хотя бы из одной папки: звёздочка карточки читает это множество, а не обходит папки каждый кадр. */
    private val favoriteNames = ObjectSet<Name>()
    /** Открытое меню действий папки (портретная раскладка): одно на весь браузер. */
    private var shownMenu: Table? = null

    private var search = ""
    private var orderDate = false
    private var reverse = false
    private var hideInvalid = true
    /** sonka: вкладка «Текстурпаки» - эвристика по описанию (PackScan.guess) или проверенный состав репозитория. */
    private var packsOnly = false
    /** Подфильтр внутри вкладки: маска PackScan.TEXTURES/MUSIC/SOUNDS. */
    private var packMask = PackScan.TEXTURES or PackScan.MUSIC or PackScan.SOUNDS

    init{
        HeAssets.ensure()
        addCloseListener() //ESC/Back
        shown(::rebuild)
        resized(::rebuild)
    }

    /** Папки читаем один раз за жизнь окна: объекты папок ключи кэшей карточек и состояния раскрытия. */
    private fun ensureFavorites(){
        if(favoritesLoaded) return

        favoritesLoaded = true
        favorites.addAll(LocalFavoritesStore.loadAll())
    }

    private fun refreshFavoriteNames(){
        favoriteNames.clear()
        favorites.forEach{ folder -> folder.modList.forEach{ favoriteNames.add(it) } }
    }

    /** Состав карточек изменился (установка/обновление/проверка) - готовые карточки устарели. */
    private fun invalidateTabs(){
        browserTabs.clear()
        favoriteTabs.clear()
    }

    fun rebuild(){
        ensureFavorites()

        cont.clearChildren()
        cont.table{ main ->
            main.top()
            main.table{ top ->
                top.image(Icon.zoom).size(64f).scaling(Scaling.fit)
                top.field(""){
                    search = it.lowercase()
                    rebuildList()
                }.growX()
                top.button(Icon.list, Styles.emptyi, 32f){
                    orderDate = !orderDate
                    rebuildList()
                }.update{ b -> b.style.imageUp = (if(orderDate) Icon.list else Icon.star) }
                    .size(48f).get()
                    .addListener(Tooltip{ tip ->
                        tip.label{ if(orderDate) "@mods.browser.sortdate" else "@mods.browser.sortstars" }.left()
                    })
                top.button(Icon.list, Styles.emptyi, 32f){
                    reverse = !reverse
                    rebuildList()
                }.update{ b -> b.style.imageUp = (if(reverse) Icon.upOpen else Icon.downOpen) }
                    .size(48f).get()
                    .addListener(Tooltip{ tip ->
                        tip.label{ if(reverse) "@misc.reverse" else "@misc.sequence" }.left()
                    })
                top.check(Core.bundle["dialog.mods.hideInvalid"], hideInvalid){
                    hideInvalid = it
                    rebuildList()
                }
                top.check(Core.bundle["client.sonka.packs.tab"], packsOnly){
                    packsOnly = it
                    rebuildList()
                }.padLeft(12f)
                //подфильтр текстуры/музыка/звуки, виден только во вкладке паков
                fun sub(icon: arc.scene.style.Drawable, bit: Int, tip: String){
                    top.button(icon, Styles.clearTogglei, 24f){
                        packMask = packMask xor bit
                        rebuildList()
                    }.size(40f).padLeft(4f).checked{ (packMask and bit) != 0 }.visible{ packsOnly }
                        .get().addListener(Tooltip{ t -> t.background(Styles.black6).margin(6f).add(tip) })
                }
                sub(Icon.imageSmall, PackScan.TEXTURES, Core.bundle["client.sonka.packs.sub.textures"])
                sub(Icon.musicSmall, PackScan.MUSIC, Core.bundle["client.sonka.packs.sub.music"])
                sub(Icon.playSmall, PackScan.SOUNDS, Core.bundle["client.sonka.packs.sub.sounds"])
            }.growX().padLeft(40f).padRight(40f)
            main.row()
            main.line(Pal.accent, true, 4f).padTop(4f)
            main.row()
            main.add(ScrollPane(CullTable{ list ->
                list.top().defaults().fill()

                val n = max((Core.graphics.width/Scl.scl(540f)).toInt(), 1)

                rebuildList = {
                    val folderColumns = ObjectMap<LocalFavorites, Array<Table>>()
                    var normCols: Array<Table>? = null

                    refreshFavoriteNames()

                    list.clearChildren()
                    buildFavoritesSection(list, n, folderColumns)

                    list.row()
                    list.add(" " + Core.bundle["dialog.mods.mods"]).color(Pal.accent).padLeft(26f)
                    list.row()
                    list.line(Pal.accent, true, 4f).pad(6f).padLeft(20f).padRight(20f)
                    list.row()
                    list.cullTable{ norm ->
                        normCols = Array(n){
                            norm.cullTable(HeAssets.grayUIAlpha){ it.top().defaults().growX().fillY() }
                                .width(min(540f, (Core.graphics.width - 80f)/Scl.scl())).fillY().pad(6f).get()
                        }
                    }
                    getModList(
                        errHandler = { e ->
                            Log.err(e)
                            list.clearChildren()
                            list.image(HeAssets.networkError).size(48f).pad(6f).color(Color.red)
                            list.add(Core.bundle["dialog.mods.checkFailed"], Styles.outlineLabel)
                        }
                    ){ ls ->
                        val visible = ls.values()
                            .filter{
                                search.isBlank()
                                || it.name.lowercase().contains(search)
                                || it.internalName.lowercase().contains(search)
                            }
                            .filter{ !hideInvalid || ModStat.run{ it.checkStatus().isValid() } }
                            .filter{ !packsOnly || packMatches(it) }
                            .let{ l ->
                                if(reverse){
                                    if(orderDate) l.reversed()
                                    else l.sortedBy{ it.stars }
                                }else{
                                    if(orderDate) l
                                    else l.sortedBy{ -it.stars }
                                }
                            }

                        //с beta-1.7 избранный мод остаётся и в общем списке; в папках - отдельные карточки-клоны
                        var normI = 0
                        visible.forEach{ m ->
                            normCols!![normI++%n].add(buildModTab(m)).growX().fillY().pad(4f).minWidth(0f).row()
                        }

                        folderColumns.forEach{ entry ->
                            var index = 0
                            visible.filter{ entry.key.contains(it) }.forEach{ m ->
                                entry.value[index++%n].add(buildModTab(m, entry.key))
                                    .growX().fillY().pad(4f).minWidth(0f).row()
                            }
                        }
                    }

                    //порядок отрисовки обратный: выпадающее меню папки (портрет) не должно прятаться под строками ниже
                    list.children.reverse()
                }

                rebuildList()
            }, Styles.smallPane)).growY().fillX().scrollX(false)
            main.row()
            main.line(Color.gray, true, 4f).padTop(6f).padBottom(6f)
            main.row()
            main.table{ bot ->
                bot.defaults().width(242f).height(62f).pad(6f)
                bot.button(Core.bundle["back"], Icon.leftOpen, Styles.grayt, 46f)
                { hide() }
                bot.button(Core.bundle["dialog.mods.refresh"], Icon.refresh, Styles.grayt, 46f){
                    ModsDialogHelper.resetModListCache()
                    invalidateTabs()

                    rebuildList()
                }
                if(Core.graphics.isPortrait) bot.row()
                //экспорт теперь у каждой папки отдельно, общая здесь только загрузка
                bot.button(Core.bundle["dialog.mods.importFav"], Icon.download, Styles.grayt, 46f){
                    importFavorites()
                }
            }.growX().fillY()
        }.grow()
    }

    private fun buildFavoritesSection(
        list: Table,
        n: Int,
        folderColumns: ObjectMap<LocalFavorites, Array<Table>>,
    ){
        list.add(" " + Core.bundle["dialog.mods.favorites"]).color(Pal.accent).padLeft(26f)
        list.row()
        list.line(Pal.accent, true, 4f).pad(6f).padLeft(20f).padRight(20f)
        list.row()

        if(favorites.isEmpty()){
            list.table{ tab ->
                tab.image(Icon.box).size(46f).color(Pal.accent)
                tab.add(Core.bundle["dialog.mods.noFavorites"]).pad(36f).padLeft(12f)
            }.fill()
            list.row()
            return
        }

        favorites.forEach{ folder ->
            folderColumns.put(folder, addFavoritesRow(list, folder, n))
        }
    }

    /** Заголовок папки со сворачиваемым телом; возвращает колонки тела, куда потом раскладываются карточки. */
    private fun addFavoritesRow(list: Table, folder: LocalFavorites, n: Int): Array<Table>{
        var coll: HeCollapser? = null
        var arrowCell: Cell<Image>? = null
        var columns: Array<Table>? = null

        val expanded = expandedFavorites.contains(folder)

        list.button({ t ->
            arrowCell = t.image(if(expanded) Icon.downOpen else Icon.rightOpen).size(28f).pad(4f)

            t.add(folder.name).color(Pal.accent).pad(4f).growX()
                .labelAlign(Align.left).wrap(true).ellipsis(true)

            t.table{ actions ->
                actions.defaults().size(44f).pad(2f)

                if(Core.graphics.isPortrait){
                    //на узком экране четыре иконки съедают всю ширину заголовка - прячем действия в меню
                    val buttons = Table(HeAssets.grayUI)

                    buttons.margin(4f)
                    buttons.left().defaults().grow().minWidth(220f).left()

                    buttons.button(Core.bundle["dialog.mods.renameFav"], Icon.edit, Styles.cleart, 32f){
                        renameFavorites(folder)
                    }.margin(8f).get().labelCell.padLeft(6f).labelAlign(Align.left)
                    buttons.row()

                    buttons.button(Core.bundle["dialog.mods.deleteFav"], Icon.trash, Styles.cleart, 32f){
                        confirmDeleteFavorites(folder)
                    }.margin(8f).get().labelCell.padLeft(6f).labelAlign(Align.left)
                    buttons.row()

                    buttons.button(Core.bundle["dialog.mods.installAll"], Icon.download, Styles.cleart, 32f){
                        installAllFavorites(folder)
                    }.margin(8f).get().labelCell.padLeft(6f).labelAlign(Align.left)
                    buttons.row()

                    buttons.button(Core.bundle["dialog.mods.exportFav"], Icon.export, Styles.cleart, 32f){
                        exportFavorites(folder)
                    }.margin(8f).get().labelCell.padLeft(6f).labelAlign(Align.left)
                    buttons.row()

                    val menu = actions.button(Icon.menu, Styles.clearNonei, 32f){}.get()
                    menu.clicked{
                        if(shownMenu != buttons){
                            shownMenu = buttons
                            buttons.pack()
                            menu.addChild(buttons)
                        }else shownMenu = null
                    }

                    buttons.update{
                        if(shownMenu != buttons) buttons.remove()
                        else{
                            val p = buttons.parent ?: return@update
                            buttons.setPosition(0f, p.height, Align.topRight)
                        }
                    }
                }else{
                    actions.button(Icon.edit, Styles.clearNonei, 32f){ renameFavorites(folder) }
                        .addTip(Core.bundle["dialog.mods.renameFav"])

                    actions.button(Icon.trash, Styles.clearNonei, 32f){ confirmDeleteFavorites(folder) }
                        .addTip(Core.bundle["dialog.mods.deleteFav"])

                    actions.button(Icon.download, Styles.clearNonei, 32f){ installAllFavorites(folder) }
                        .addTip(Core.bundle["dialog.mods.installAll"])

                    actions.button(Icon.export, Styles.clearNonei, 32f){ exportFavorites(folder) }
                        .addTip(Core.bundle["dialog.mods.exportFav"])
                }

                //клик по иконке действия не должен сворачивать/разворачивать папку (кнопка-родитель)
                actions.touchable = Touchable.enabled
                actions.addEventBlocker()
            }.pad(4f).right()
        }, Styles.grayt){
            coll?.toggle()

            if(coll?.collapse == false) expandedFavorites.add(folder)
            else expandedFavorites.remove(folder)
        }.growX().fillY().padTop(4f).padLeft(20f).padRight(20f)

        list.row()

        val collapser = HeCollapser(collX = false, collY = true, collapsed = !expanded){ col ->
            col.top()

            columns = Array(n){
                col.cullTable(HeAssets.grayUIAlpha){
                    it.top().defaults().growX().fillY()
                }.width(min(540f, (Core.graphics.width - 80f)/Scl.scl())).fillY().pad(6f).get()
            }
        }.setDuration(0.3f, Interp.pow3Out)

        coll = collapser
        arrowCell?.update{ image ->
            image.drawable = if(collapser.collapse) Icon.rightOpen else Icon.downOpen
        }

        list.add(collapser).growX().fillY().padLeft(20f).padRight(20f)
        list.row()

        return columns ?: Array(n){ Table() }
    }

    private fun confirmDeleteFavorites(folder: LocalFavorites){
        UIUtils.showConfirm(
            Core.bundle["dialog.mods.deleteFav"],
            Core.bundle.format("dialog.mods.confirmDeleteFav", folder.name)
        ){
            favorites.remove(folder)
            LocalFavoritesStore.delete(folder.name)

            favoriteTabs.remove(folder)
            expandedFavorites.remove(folder)

            rebuildList()
        }
    }

    /** Имя занято другой папкой ([exclude] - сама редактируемая папка, её прежнее имя не конфликт). */
    private fun isFavoritesNameTaken(name: String, exclude: LocalFavorites? = null): Boolean{
        if(name == exclude?.name) return false

        return favorites.any{ it !== exclude && it.name == name }
    }

    private fun isFavoritesNameUnavailable(name: String, exclude: LocalFavorites? = null): Boolean{
        val trimmed = name.trim()

        return trimmed.isEmpty() || isFavoritesNameTaken(trimmed, exclude)
    }

    private fun showFavoritesNameDialog(
        title: String,
        initial: String = "",
        exclude: LocalFavorites? = null,
        onConfirm: (String) -> Unit,
    ){
        var name = initial

        UIUtils.showPane(
            title,
            UIUtils.cancelBut,
            ButtonEntry(
                Core.bundle["confirm"],
                Icon.ok,
                disabled = { isFavoritesNameUnavailable(name, exclude) }
            ){ d ->
                if(!isFavoritesNameUnavailable(name, exclude)){
                    onConfirm(name.trim())
                    d.hide()
                }
            }
        ){ t ->
            t.add(Core.bundle["dialog.mods.favName"]).growX().left().labelAlign(Align.left)
            t.row()
            t.table{ row ->
                val field = row.field(initial){ name = it }.growX().fillY().get()
                row.button(Icon.paste, Styles.clearNonei, 32f){
                    field.text = Core.app.clipboardText
                    name = field.text
                }.size(48f)
            }.grow().minWidth(420f)
            t.row()
            t.add(Core.bundle["dialog.mods.favNameUnavailable"])
                .color(Color.crimson).left().padTop(4f)
                .visible{ isFavoritesNameTaken(name.trim(), exclude) }
        }
    }

    private fun renameFavorites(folder: LocalFavorites){
        showFavoritesNameDialog(
            Core.bundle["dialog.mods.renameFav"],
            initial = folder.name,
            exclude = folder
        ){ name -> applyFavoritesRename(folder, name) }
    }

    private fun applyFavoritesRename(folder: LocalFavorites, name: String){
        if(folder.name == name || isFavoritesNameTaken(name, folder)) return

        val renamed = LocalFavorites(name)
        renamed.replaceAll(folder.modList)
        //запись остаётся на прежнем месте в хранилище, а не уезжает в конец (оригинал удалял старую и добавлял новую)
        LocalFavoritesStore.replace(folder.name, renamed)

        val index = favorites.indexOf(folder)
        if(index >= 0) favorites[index] = renamed
        else favorites.add(renamed)

        if(expandedFavorites.remove(folder)) expandedFavorites.add(renamed)
        favoriteTabs.remove(folder)

        rebuildList()
    }

    private fun exportFavorites(folder: LocalFavorites){
        if(folder.isEmpty){
            UIUtils.showTip(
                null,
                Core.bundle["dialog.mods.noFavorites"]
            )
            return
        }

        val serial = folder.toSerial()

        UIUtils.showPane(
            Core.bundle["dialog.mods.exportFav"],
            UIUtils.closeBut,
            ButtonEntry(Core.bundle["misc.copy"], Icon.copy){
                Vars.ui.showInfoFade(Core.bundle["infos.copied"])
                Core.app.clipboardText = serial
            },
            ButtonEntry(Core.bundle["misc.save"], Icon.file){
                //file chooser форка переписан на builder-паттерн (mindustry.ui.FileChooser)
                FileChooser.save("json").submit{ f ->
                    f.writeString(serial, false)
                }
            }
        ){ t ->
            t.add(folder.name).growX().pad(6f).left()
                .labelAlign(Align.left).color(Pal.accent)
            t.row()
            t.add(Core.bundle["dialog.mods.favoritesText"]).growX().pad(6f).left()
                .labelAlign(Align.left).color(Color.lightGray)
            t.row()
            t.table(HeAssets.darkGrayUIAlpha){ l ->
                l.left().top().add(serial).pad(6f).wrap().growX()
            }.margin(12f).minWidth(420f).growX()
        }
    }

    private fun installAllFavorites(folder: LocalFavorites){
        if(folder.isEmpty){
            UIUtils.showTip(null, Core.bundle["dialog.mods.noFavorites"])
            return
        }

        getModList(
            errHandler = { e ->
                Log.err(e)
                UIUtils.showException(e, Core.bundle["dialog.mods.checkFailed"])
            }
        ){ ls ->
            showDownloadAllDialog(ls.values().filter{ folder.contains(it) }){
                invalidateTabs()
                onInstalledChanged?.run()
                rebuildList()
            }
        }
    }

    private fun importFavorites(){
        var favName = ""
        var favText = ""
        var nameField: TextField? = null

        UIUtils.showPane(
            Core.bundle["dialog.mods.importFav"],
            UIUtils.cancelBut,
            ButtonEntry(Core.bundle["confirm"], Icon.ok){ d ->
                importFavorites(d, favName, favText)
            }
        ){ t ->
            t.add(Core.bundle["dialog.mods.favName"]).growX().left().labelAlign(Align.left)
            t.row()
            t.table{ row ->
                nameField = row.field(""){ favName = it }.growX().fillY().get()
                row.button(Icon.paste, Styles.clearNonei, 32f){
                    nameField?.also{
                        it.text = Core.app.clipboardText
                        favName = it.text
                    }
                }.size(48f)
            }.grow().minWidth(420f)
            t.row()
            t.add(Core.bundle["dialog.mods.inputFavText"]).growX().left().padTop(12f)
                .labelAlign(Align.left).color(Color.lightGray)
            t.row()
            t.table{ row ->
                val area = row.area(""){ text ->
                    favText = text

                    //имя берём из самого текста, пока пользователь своё не ввёл
                    if(favName.isBlank()){
                        parseFavorites(text)?.name?.trim()?.takeIf{ it.isNotEmpty() }?.also{ name ->
                            nameField?.text = name
                            favName = name
                        }
                    }
                }.grow().get()

                row.button(Icon.paste, Styles.clearNonei, 32f){
                    area.text = Core.app.clipboardText
                    favText = area.text

                    if(favName.isBlank()){
                        parseFavorites(favText)?.name?.trim()?.takeIf{ it.isNotEmpty() }?.also{ name ->
                            nameField?.text = name
                            favName = name
                        }
                    }
                }.size(48f)
            }.grow().minWidth(420f).maxHeight(320f)
        }
    }

    private fun importFavorites(dialog: Dialog, name: String, text: String){
        val parsed = parseFavorites(text)
        val legacyRepos = if(parsed == null) parseLegacyRepos(text) else null

        if(parsed == null && legacyRepos == null){
            UIUtils.showTip(null, Core.bundle["dialog.mods.favInvalidText"])
            return
        }

        val favName = name.trim().ifBlank{ parsed?.name?.trim().orEmpty() }
        if(favName.isBlank()){
            UIUtils.showTip(null, Core.bundle["dialog.mods.favEmptyName"])
            return
        }

        if(parsed != null){
            dialog.hide()
            createImportedFavorites(favName, parsed.mods)
            return
        }

        //формат beta-1.6 («author/repo;» через точку с запятой): имена модов восстанавливаем по листингу
        getModList(
            errHandler = { e ->
                Log.err(e)
                UIUtils.showException(e, Core.bundle["dialog.mods.checkFailed"])
            }
        ){ ls ->
            val mods = ls.values().filter{ legacyRepos!!.contains(it.repo) }.map{ Name(it) }

            if(mods.isEmpty()){
                UIUtils.showTip(null, Core.bundle["dialog.mods.favInvalidText"])
                return@getModList
            }

            dialog.hide()
            createImportedFavorites(favName, mods)
        }
    }

    private fun createImportedFavorites(name: String, mods: List<Name>){
        val existing = favorites.firstOrNull{ it.name == name }
        if(existing == null){
            createLocalFavorites(name, mods)
            rebuildList()
            return
        }

        UIUtils.showPane(
            Core.bundle["dialog.mods.importFav"],
            ButtonEntry(Core.bundle["dialog.mods.favKeepBoth"], Icon.copy){ d ->
                createLocalFavorites(uniqueFavoritesName(name), mods)
                d.hide()
                rebuildList()
            },
            ButtonEntry(Core.bundle["dialog.mods.favMerge"], Icon.add){ d ->
                val merged = ArrayList<Name>(existing.modList)
                mods.forEach{ mod -> if(merged.none{ it == mod }) merged.add(mod) }

                existing.replaceAll(merged)
                d.hide()
                rebuildList()
            }.row(),
            ButtonEntry(Core.bundle["dialog.mods.favReplace"], Icon.ok){ d ->
                existing.replaceAll(mods)
                d.hide()
                rebuildList()
            },
            UIUtils.cancelBut
        ){ t ->
            t.add(Core.bundle.format("dialog.mods.favExists", name)).growX().wrap(true)
        }
    }

    private fun uniqueFavoritesName(base: String): String{
        var index = 2

        while(favorites.any{ it.name == "$base ($index)" }) index++

        return "$base ($index)"
    }

    private fun createLocalFavorites(name: String, mods: Collection<Name>): LocalFavorites{
        val folder = LocalFavorites(name)
        folder.replaceAll(mods)
        favorites.add(folder)

        return folder
    }

    /** Звёздочка основной карточки: выбрать папки (галочки), при необходимости создать новую. */
    private fun showAddToFavoritesDialog(mod: ModListing){
        val checked = ObjectMap<LocalFavorites, Boolean>()
        favorites.forEach{ checked.put(it, it.contains(mod)) }

        fun rebuildContent(t: Table){
            t.clearChildren()
            t.top().left().defaults().left().growX()

            if(favorites.isEmpty()){
                t.add(Core.bundle["dialog.mods.noFavorites"]).color(Color.lightGray).pad(6f).row()
            }

            favorites.forEach{ folder ->
                t.button({ it.left().add(folder.name) }, Styles.underlineb){
                    checked.put(folder, !checked.get(folder, false))
                }.margin(10f).marginLeft(14f).pad(6f).update{
                    it.isChecked = checked.get(folder, false)
                }

                t.row()
            }

            t.row()
            t.button(Core.bundle["dialog.mods.newFav"], Icon.add, Styles.flatt){
                showFavoritesNameDialog(Core.bundle["dialog.mods.newFav"]){ name ->
                    checked.put(createLocalFavorites(name, emptyList()), true)
                    rebuildContent(t)
                }
            }.pad(6f).left().margin(6f)
            t.row()
        }

        UIUtils.showPane(
            Core.bundle["dialog.mods.addToFav"],
            UIUtils.cancelBut,
            ButtonEntry(Core.bundle["confirm"], Icon.ok){ d ->
                applyFavoritesSelection(mod, checked)
                rebuildList()
                d.hide()
            }
        ){ t -> rebuildContent(t) }
    }

    private fun applyFavoritesSelection(mod: ModListing, checked: ObjectMap<LocalFavorites, Boolean>){
        val name = Name(mod)

        favorites.forEach{ folder ->
            val wanted = checked.get(folder, folder.contains(mod))

            when{
                wanted && !folder.contains(mod) -> folder.addMod(name)
                !wanted && folder.contains(mod) -> folder.removeMod(name)
            }
        }
    }

    private class ParsedFavorites(val name: String?, val mods: List<Name>)

    /** Разбор JSON экспорта: объект {name, mods:[...]} или голый массив [{author, name}]; не JSON - null. */
    private fun parseFavorites(text: String): ParsedFavorites?{
        val raw = text.trim()
        if(raw.isBlank()) return null

        try{
            val json = Jval.read(raw)
            if(json.isArray) return ParsedFavorites(null, LocalFavoritesStore.parseMods(json))

            if(json.isObject){
                val mods = json.get("mods") ?: return null
                if(!mods.isArray) return null

                return ParsedFavorites(json.getString("name"), LocalFavoritesStore.parseMods(mods))
            }
        }catch(_: Exception){
            //не JSON - пробуем как плоский список репозиториев
        }

        return null
    }

    /** Экспорт beta-1.6: «author/repo;author/repo;...». null - текст на него не похож. */
    private fun parseLegacyRepos(text: String): Set<String>?{
        val repos = text.split(";").map{ it.trim() }.filter{ it.isNotBlank() }

        return if(repos.isNotEmpty() && repos.all{ it.contains('/') && !it.contains(' ') }) repos.toSet() else null
    }

    /**
     * Попадает ли мод во вкладку паков: проверенный состав репозитория (кэш) имеет приоритет над
     * догадкой по описанию; подфильтр сверяется с реальными категориями у проверенных и с маской
     * ключевых слов у остальных.
     */
    private fun packMatches(mod: ModListing): Boolean{
        val checked = PackScan.cachedRemote(mod)
        if(checked != null){
            if(!checked.isPack()) return false
            var mask = 0
            if(checked.textures()) mask = mask or PackScan.TEXTURES
            if(checked.music.size > 0 || checked.newMusic > 0) mask = mask or PackScan.MUSIC
            if(checked.sounds.size > 0 || checked.newSounds > 0) mask = mask or PackScan.SOUNDS
            if(mask == 0) mask = PackScan.TEXTURES //прочие подмены (шейдеры и т.п.) - показываем вместе с текстурами
            return (mask and packMask) != 0
        }
        return (PackScan.guess(mod) and packMask) != 0
    }

    /**
     * Карточка мода. [owner] == null - карточка общего списка (звёздочка открывает выбор папок);
     * иначе клон внутри папки [owner] (звёздочка убирает мод из этой папки).
     */
    private fun buildModTab(mod: ModListing, owner: LocalFavorites? = null): Table{
        val cache = if(owner == null) browserTabs
        else favoriteTabs.get(owner) ?: ObjectMap<ModListing, Table>().also{ favoriteTabs.put(owner, it) }

        cache[mod]?.also{ return it }

        val modName = Name(mod)
        val res = Table()
        val stat = mod.checkStatus()
        var coll: HeCollapser? = null
        var setupContent = { _: Int -> }

        cache.put(mod, res)

        //одна текстура на URL для всех карточек (общий список + клоны в папках)
        val image = ModsDialogHelper.icons.resolve(iconUrl(mod.repo))
        val loaded = Vars.mods.getMod(mod.internalName)

        res.button(
            { top ->
                top.table(Tex.buttonSelect){ icon ->
                    icon.stack(
                        Image(image).setScaling(Scaling.fit),
                        Table{ stars ->
                            stars.bottom().left()
                            buildStars(stars, mod)
                        }
                    ).size(80f)
                }.pad(10f).margin(4f).size(88f)
                top.stack(
                    Table{ info ->
                        info.left().top().margin(12f).marginLeft(6f).defaults().left()
                        info.add(mod.name).color(Pal.accent).growX().labelAlign(Align.left).padRight(160f).wrap()
                        info.row()
                        info.add(mod.version ?: "", 0.8f).color(Color.lightGray).growX().padRight(50f).wrap()
                        info.row()
                        info.add(mod.shortDesc()).growY().growX().padRight(50f).wrap()
                    },
                    Table{ over ->
                        over.right()

                        over.table{ status ->
                            status.top().defaults().size(26f).pad(4f)

                            loaded?.also{ l ->
                                if(tryCompareVersion(l.meta.version, mod.version) < 0){
                                    status.image(Icon.starSmall).scaling(Scaling.fit).color(HeAssets.lightBlue)
                                        .addTip(Core.bundle["dialog.mods.newVersion"])
                                }else{
                                    status.image(Icon.okSmall).scaling(Scaling.fit).color(Pal.heal)
                                        .addTip(Core.bundle["dialog.mods.installed"])
                                }
                            }

                            buildModAttrIcons(status, stat)
                            PackUi.browserBadges(status, mod)
                        }.fill().pad(4f)

                        over.table{ side ->
                            side.line(Color.darkGray, false, 3f)
                            side.table{ buttons2 ->
                                buttons2.defaults().size(48f)
                                buttons2.button(Icon.star, Styles.clearNonei, 24f){
                                    if(owner != null){
                                        owner.removeMod(modName)
                                        rebuildList()
                                    }else showAddToFavoritesDialog(mod)
                                }.update{ b ->
                                    b.image.setScale(0.9f)
                                    b.style.imageUpColor = if(owner != null || favoriteNames.contains(modName)) Pal.accent else Color.white
                                }

                                buttons2.row()
                                buttons2.button(Icon.downloadSmall, Styles.clearNonei, 48f){
                                    showDownloadModDialog(mod){
                                        invalidateTabs()
                                        onInstalledChanged?.run()
                                        rebuildList()
                                    }
                                }
                                buttons2.row()

                                buttons2.addEventBlocker()
                            }.fill()
                        }.fill()
                    }
                ).grow()
            }, Styles.grayt){
            coll!!.toggle()
            if(!coll!!.collapse){
                setupContent(0)
            }
        }.growX().fillY()

        res.row()
        coll = res.add(HeCollapser(collX = false, collY = true, collapsed = true, Styles.grayPanel){ col ->
            col.table{ details ->
                details.left().defaults().growX().pad(4f).padLeft(12f).padRight(12f)

                details.add(Core.bundle.format("dialog.mods.author", mod.author ?: "???"))
                    .growX().padRight(50f).wrap().color(Pal.accent).labelAlign(Align.left)
                details.row()
                details.table{ link ->
                    link.left().image(Icon.githubSmall).scaling(Scaling.fit).size(24f).color(Color.lightGray)
                    val linkButton = link.button("...", Styles.nonet){}
                        .padLeft(4f).wrapLabel(true)
                        .growX().left().align(Align.left).height(30f).disabled(true).get()

                    linkButton.label.setAlignment(Align.left)
                    linkButton.label.setFontScale(0.9f)

                    val url = "https://github.com/${mod.repo}"
                    linkButton.isDisabled = false
                    linkButton.setText(url)
                    linkButton.clicked{ Core.app.openURI(url) }
                }
                details.row()
                details.table{ status ->
                    status.left().defaults().left()

                    loaded?.also{ l ->
                        if(tryCompareVersion(l.meta.version, mod.version) < 0){
                            buildStatus(status, Icon.starSmall, Core.bundle["dialog.mods.newVersion"], HeAssets.lightBlue)
                        }else{
                            buildStatus(status, Icon.okSmall, Core.bundle["dialog.mods.installed"], Pal.heal)
                        }
                    }

                    buildModAttrList(status, stat)
                }
                details.row()
                //sonka: состав репозитория (текстуры/музыка/звуки) - из кэша или по кнопке проверки;
                //после проверки карточка пересобирается, чтобы бейджи стали «проверенными»
                details.table{ packT ->
                    PackUi.remoteCheck(packT, mod){
                        Core.app.post{
                            //проверка меняет бейджи у ВСЕХ копий карточки: и в общем списке, и в папках
                            browserTabs.remove(mod)
                            favoriteTabs.values().forEach{ it.remove(mod) }
                            rebuildList()
                        }
                    }
                }
                details.row()
                details.line(Color.gray, true, 4f).pad(6f).padLeft(-6f).padRight(-6f)
                details.row()

                var current = -1
                details.table{ switch ->
                    switch.left().defaults().center()
                    switch.button({ it.add(Core.bundle["dialog.mods.description"], 0.85f) }, switchBut){ setupContent(0) }
                        .margin(12f).checked{ current == 0 }.disabled{ t -> t.isChecked }
                    switch.button({ it.add(Core.bundle["dialog.mods.rawText"], 0.85f) }, switchBut){ setupContent(1) }
                        .margin(12f).checked{ current == 1 }.disabled{ t -> t.isChecked }
                }.grow().padBottom(0f)
                details.row()
                details.table(HeAssets.grayUI){ desc ->
                    desc.defaults().grow()
                    setupContent = a@{ i ->
                        if(i == current) return@a

                        desc.clearChildren()
                        current = i

                        when(i){
                            0 -> desc.add(markupTable(mod.description ?: ""))
                            1 -> desc.add(mod.description ?: "").wrap()
                        }
                    }
                }.grow().margin(12f).padTop(0f)
            }.grow()
        }.also{ it.setDuration(0.3f, Interp.pow3Out) }).growX().fillY().colspan(2).get()

        return res
    }

    private class CullTable : Table{
        constructor(background: Drawable?) : super(background)
        constructor(build: Cons<Table>) : super(build)
        constructor(background: Drawable?, build: Cons<Table>) : super(background, build)

        override fun drawChildren(){
            cullingArea?.also{ widgetAreaBounds ->
                children.forEach{ widget ->
                    if(widget is Group){
                        val set = widget.cullingArea ?: Rect()
                        set.set(widgetAreaBounds)
                        set.x -= widget.x
                        set.y -= widget.y
                        widget.setCullingArea(set)
                    }
                }
            }
            super.drawChildren()
        }
    }
}
