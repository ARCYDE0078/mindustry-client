package helium.ui.dialogs.mods

import arc.Core
import arc.files.Fi
import arc.func.Cons
import arc.func.Func
import arc.graphics.Color
import arc.graphics.g2d.Draw
import arc.math.Mathf
import arc.scene.Element
import arc.scene.style.Drawable
import arc.scene.ui.Button
import arc.scene.ui.layout.Cell
import arc.scene.ui.layout.Table
import arc.struct.OrderedMap
import arc.struct.Seq
import arc.util.Log
import arc.util.Scaling
import arc.util.Strings
import arc.util.Time
import arc.util.serialization.Json
import arc.util.serialization.Jval
import helium.util.set
import helium.ui.ButtonEntry
import helium.ui.HeAssets
import helium.ui.UIUtils
import helium.util.CLIENT_ONLY
import helium.util.DEPRECATED
import helium.util.Downloader
import helium.util.ImageCache
import helium.util.JAR_MOD
import helium.util.JS_MOD
import helium.util.ModStat
import helium.util.UNSUPPORTED
import helium.util.VersionCompareHelper
import helium.util.toStoreSize
import mindustry.Vars
import mindustry.core.Version
import mindustry.ctype.UnlockableContent
import mindustry.gen.Icon
import mindustry.mod.ModListing
import mindustry.mod.Mods
import mindustry.ui.Bar
import mindustry.ui.Styles
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.Semaphore

/**
 * Порт helium.ui.dialogs.mods.ModsDialogHelper (Helium, EB-wilson): общие кирпичики
 * менеджера и браузера модов - иконки/списки статусов, ссылки на GitHub, сравнение версий,
 * кэшируемый листинг модов и диалог скачивания.
 *
 * Отличия от оригинала:
 * <ul>
 * <li>класс листинга - ванильный [mindustry.mod.ModListing] (наш браузер и апдейт-чекер форка
 *     парсят тот же mods.json Anuken/MindustryMods из Vars.modJsonURLs; свой список модов
 *     EB-wilson'а с полями subtitle/hidden не используется, поэтому clientOnly для записей
 *     браузера неизвестен и его иконка там не показывается);</li>
 * <li>иконки модов берутся с Anuken/MindustryMods/icons (мод для диалога скачивания ходил в
 *     собственное зеркало EB-wilson'а).</li>
 * </ul>
 */
object ModsDialogHelper{
    /** Кэш иконок листинга: одна текстура на URL, пока открыт браузер (перестройка карточек не качает заново). */
    val icons = ImageCache()

    @Volatile
    var modList: OrderedMap<Name, ModListing>? = null
        private set

    val switchBut: Button.ButtonStyle by lazy{
        Button.ButtonStyle().also{
            HeAssets.ensure()
            it.up = Styles.none
            it.over = HeAssets.grayUIAlpha
            it.down = HeAssets.grayUI
            it.checked = HeAssets.grayUI
        }
    }

    /** Короткое описание с обрезкой - в ванильном ModListing нет subtitle, берём description. */
    fun ModListing.shortDesc(): String = Strings.truncate(
        if(description == null || description.length > Vars.maxModSubtitleLength) "" else description,
        Vars.maxModSubtitleLength, "..."
    )

    /** Порт ModListing.checkStatus() мода; clientOnly из листинга не узнать - бит ставится всегда,
     *  чтобы иконка "работает на сервере" в браузере не врала. */
    fun ModListing.checkStatus(): Int{
        var res = CLIENT_ONLY

        if(hasJava) res = res or JAR_MOD
        if(hasScripts) res = res or JS_MOD

        if(minMajor() < (if(hasJava) Vars.minJavaModGameVersion else Vars.minModGameVersion)) res = res or DEPRECATED
        if(!Version.isAtLeast(minGameVersion ?: "0")) res = res or UNSUPPORTED

        return res
    }

    private fun ModListing.minMajor(): Int{
        val ver = minGameVersion ?: "0"
        val dot = ver.indexOf(".")
        return if(dot != -1) Strings.parseInt(ver.take(dot), 0)
        else Strings.parseInt(ver, 0)
    }

    fun iconUrl(repo: String): String =
        "https://raw.githubusercontent.com/Anuken/MindustryMods/master/icons/" + repo.replace("/", "_")

    fun buildDescSelector(
        details: Table,
        get: () -> Int,
        set: (Int) -> Unit,
        contents: List<UnlockableContent>,
        /** sonka: необязательная 4-я вкладка (индекс 3) - «Замены» ресурс-пака (sonkaextras.packs) */
        extra: String? = null,
    ){
        details.table{ switch ->
            switch.left().defaults().center()
            switch.button({ it.add(Core.bundle["dialog.mods.description"], 0.85f) }, switchBut){ set(0) }
                .margin(12f).checked{ get() == 0 }.disabled{ t -> t.isChecked }
            switch.button({ it.add(Core.bundle["dialog.mods.rawText"], 0.85f) }, switchBut){ set(1) }
                .margin(12f).checked{ get() == 1 }.disabled{ t -> t.isChecked }
            if(contents.any()){
                switch.button({ it.add(Core.bundle["dialog.mods.contents"], 0.85f) }, switchBut){ set(2) }
                    .margin(12f).checked{ get() == 2 }.disabled{ t -> t.isChecked }
            }
            if(extra != null){
                switch.button({ it.add(extra, 0.85f) }, switchBut){ set(3) }
                    .margin(12f).checked{ get() == 3 }.disabled{ t -> t.isChecked }
            }
        }.grow().padBottom(0f)
    }

    fun buildErrorIcons(status: Table, stat: Int){
        ModStat.apply{
            if(stat.isLibMissing()) status.image(Icon.layersSmall).scaling(Scaling.fit).color(Color.crimson)
                .addTip(Core.bundle["dialog.mods.libMissing"])
            else if(stat.isLibIncomplete()) status.image(Icon.warningSmall).scaling(Scaling.fit)
                .color(Color.crimson)
                .addTip(Core.bundle["dialog.mods.libIncomplete"])
            else if(stat.isLibCircleDepending()) status.image(Icon.refresh).scaling(Scaling.fit)
                .color(Color.crimson)
                .addTip(Core.bundle["dialog.mods.libCircleDepending"])

            if(stat.isError()) status.image(Icon.cancelSmall).scaling(Scaling.fit).color(Color.crimson)
                .addTip(Core.bundle["dialog.mods.error"])
            if(stat.isBlackListed()) status.image(Icon.infoCircle).scaling(Scaling.fit).color(Color.crimson)
                .addTip(Core.bundle["dialog.mods.blackListed"])
        }
    }

    fun buildLinkButton(link: Table, mod: Mods.LoadedMod){
        link.left().image(Icon.githubSmall).scaling(Scaling.fit).size(24f).color(Color.lightGray)
        val linkButton = link.button("...", Styles.nonet){}
            .padLeft(4f).padRight(50f).wrapLabel(true)
            .growX().left().align(arc.util.Align.left).height(30f).disabled(true).get()

        linkButton.label.setAlignment(arc.util.Align.left)
        linkButton.label.setFontScale(0.9f)

        fun apply(repo: String?){
            if(repo == null){
                linkButton.isDisabled = true
                linkButton.setText(Core.bundle["dialog.mods.noGithubRepo"])
            }else{
                val url = "https://github.com/$repo"
                linkButton.isDisabled = false
                linkButton.setText(url)
                linkButton.clicked{ Core.app.openURI(url) }
            }
        }

        //у форка репозиторий часто лежит прямо в meta мода - без сети
        val metaRepo = mod.getRepo()
        if(metaRepo != null){
            apply(metaRepo)
            return
        }

        getModList(
            errHandler = {
                linkButton.isDisabled = true
                linkButton.setText(Core.bundle["dialog.mods.checkFailed"])
            }
        ){ list ->
            apply(list[Name(mod)]?.repo)
        }
    }

    fun buildModAttrIcons(status: Table, stat: Int){
        ModStat.apply{
            if(stat.isJAR()) status.image(HeAssets.java).scaling(Scaling.fit).color(mindustry.graphics.Pal.reactorPurple)
                .addTip(Core.bundle["dialog.mods.jarMod"])
            if(stat.isJS()) status.image(HeAssets.javascript).scaling(Scaling.fit).color(mindustry.graphics.Pal.accent)
                .addTip(Core.bundle["dialog.mods.jsMod"])
            if(!stat.isClientOnly()) status.image(Icon.hostSmall).scaling(Scaling.fit).color(mindustry.graphics.Pal.techBlue)
                .addTip(Core.bundle["dialog.mods.hostMod"])

            if(stat.isDeprecated()) status.image(Icon.warningSmall).scaling(Scaling.fit).color(Color.crimson)
                .addTip(
                    Core.bundle.format(
                        "dialog.mods.deprecated",
                        if(stat.isJAR()) Vars.minJavaModGameVersion else Vars.minModGameVersion
                    )
                )
            else if(stat.isUnsupported()) status.image(Icon.warningSmall).scaling(Scaling.fit).color(Color.crimson)
                .addTip(Core.bundle["dialog.mods.unsupported"])
        }
    }

    fun buildModBasicStatus(status: Table, stat: Int){
        ModStat.apply{
            if(stat.isValid()){
                buildStatus(status, Icon.okSmall, Core.bundle["dialog.mods.modStatCorrect"], mindustry.graphics.Pal.heal)
            }else{
                buildStatus(status, Icon.cancelSmall, Core.bundle["dialog.mods.modStatError"], Color.crimson)
            }
        }
    }

    fun buildModAttrList(status: Table, stat: Int){
        ModStat.apply{
            if(stat.isJAR()){
                buildStatus(status, HeAssets.java, Core.bundle["dialog.mods.jarMod"], mindustry.graphics.Pal.reactorPurple)
            }
            if(stat.isJS()){
                buildStatus(status, HeAssets.javascript, Core.bundle["dialog.mods.jsMod"], mindustry.graphics.Pal.accent)
            }
            if(!stat.isClientOnly()){
                buildStatus(status, Icon.hostSmall, Core.bundle["dialog.mods.hostMod"], mindustry.graphics.Pal.techBlue)
            }
        }
    }

    fun buildModErrList(status: Table, stat: Int){
        ModStat.apply{
            if(stat.isDeprecated()){
                buildStatus(
                    status,
                    Icon.warningSmall,
                    Core.bundle.format(
                        "dialog.mods.deprecated",
                        if(stat.isJAR()) Vars.minJavaModGameVersion else Vars.minModGameVersion
                    ),
                    Color.crimson
                )
            }else if(stat.isUnsupported()){
                buildStatus(status, Icon.warningSmall, Core.bundle["dialog.mods.unsupported"], Color.crimson)
            }

            if(stat.isLibMissing()){
                buildStatus(status, Icon.layersSmall, Core.bundle["dialog.mods.libMissing"], Color.crimson)
            }else if(stat.isLibIncomplete()){
                buildStatus(status, Icon.warningSmall, Core.bundle["dialog.mods.libIncomplete"], Color.crimson)
            }else if(stat.isLibCircleDepending()){
                buildStatus(status, Icon.rotateSmall, Core.bundle["dialog.mods.libCircleDepending"], Color.crimson)
            }

            if(stat.isError()){
                buildStatus(status, Icon.cancelSmall, Core.bundle["dialog.mods.error"], Color.crimson)
            }
            if(stat.isBlackListed()){
                buildStatus(status, Icon.infoCircleSmall, Core.bundle["dialog.mods.blackListed"], Color.crimson)
            }
        }
    }

    fun buildStars(stars: Table, modInfo: ModListing){
        stars.add(object : Element(){
            override fun draw(){
                validate()
                Draw.color(Color.darkGray)
                Icon.starSmall.draw(
                    x - width*0.2f, y - height*0.2f,
                    0f, 0f, width, height,
                    1.4f, 1.4f, 0f
                )
                Draw.color(Color.white)
                Icon.starSmall.draw(x, y, width, height)
            }
        }).size(60f).pad(-16f)
        stars.add(modInfo.stars.toString(), Styles.outlineLabel, 0.85f)
            .bottom().padBottom(4f).padLeft(-2f)
    }

    fun buildStatus(status: Table, icon: Drawable, information: String, color: Color){
        status.image(icon).scaling(Scaling.fit).color(color).size(26f).pad(4f)
        status.add(information, 0.85f).color(color)
        status.row()
    }

    fun <T : Element> Cell<T>.addTip(tipText: String): Cell<T>{
        tooltip{ t ->
            t.table(HeAssets.padGrayUIAlpha){ tip ->
                tip.add(tipText, Styles.outlineLabel)
            }
        }

        return this
    }

    /** GitHub-markdown описаний через клиентский StupidMarkupParser (замена universe.ui.markdown UniverseKit). */
    fun markupTable(text: String): Table{
        return try{
            mindustry.client.ui.StupidMarkupParser.format(text)
        }catch(e: Throwable){
            Table().also{ t -> t.add(text).wrap().growX().labelAlign(arc.util.Align.topLeft) }
        }
    }

    fun resetModListCache(){
        modList = null
    }

    private val listLock = Any()
    private var listFetch: CompletableFuture<OrderedMap<Name, ModListing>>? = null

    fun getModList(
        index: Int = 0,
        refresh: Boolean = false,
        errHandler: Cons<Throwable>? = null,
        listener: Cons<OrderedMap<Name, ModListing>>,
    ){
        if(index >= Vars.modJsonURLs.size) return
        if(refresh) resetModListCache()

        modList?.also{
            listener.get(it)
            return
        }

        fetchModList(index).whenComplete{ list, err ->
            Core.app.post{
                if(err != null) errHandler?.get(err.cause ?: err)
                else listener.get(list)
            }
        }
    }

    /**
     * Один сетевой запрос на всех: пока список качается, повторные getModList (браузер + кнопка
     * GitHub в менеджере + обновление чекера) подцепляются к тому же запросу, а не выстраиваются в
     * очередь под замком, как в beta-1.6.
     */
    private fun fetchModList(index: Int): CompletableFuture<OrderedMap<Name, ModListing>> = synchronized(listLock){
        listFetch ?: CompletableFuture.supplyAsync({ loadModList(index).also{ modList = it } }, Downloader.exec).also{ pending ->
            listFetch = pending
            pending.whenComplete{ _, _ -> synchronized(listLock){ if(listFetch === pending) listFetch = null } }
        }
    }

    /** Перебор зеркал mods.json из [Vars.modJsonURLs] - первое ответившее выигрывает. */
    private fun loadModList(index: Int): OrderedMap<Name, ModListing>{
        var last: Throwable? = null

        for(i in index until Vars.modJsonURLs.size){
            try{
                return parseModList(Downloader.getString(Vars.modJsonURLs[i], maxRetry = 0))
            }catch(e: InterruptedException){
                throw e
            }catch(e: Throwable){
                last = e
            }
        }

        throw last ?: IllegalStateException("no available mod list url")
    }

    private fun parseModList(json: String): OrderedMap<Name, ModListing>{
        val result = OrderedMap<Name, ModListing>()
        @Suppress("UNCHECKED_CAST")
        val list = Json().fromJson(Seq::class.java, ModListing::class.java, json) as Seq<ModListing>
        val d = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        val parser = Func<String, Date>{ text ->
            try{
                d.parse(text)
            }catch(_: Exception){
                Date()
            }
        }

        list.sortComparing{ m -> parser.get(m!!.lastUpdated) }.reverse()
        list.forEach{ result[Name(it)] = it }

        return result
    }

    fun setupContentsList(
        desc: Table,
        contents: List<UnlockableContent>,
    ){
        val n = (desc.width/arc.scene.ui.layout.Scl.scl(50f)).toInt().coerceAtLeast(1)
        contents.forEachIndexed{ i, c ->
            if(i > 0 && i%n == 0) desc.row()

            desc.button(arc.scene.style.TextureRegionDrawable(c.uiIcon), Styles.flati, Vars.iconMed){
                Vars.ui.content.show(c)
            }.size(50f).with{ im ->
                val click = im.clickListener
                im.update{
                    im.image.color.lerp(
                        if(!click.isOver) Color.lightGray else Color.white,
                        0.4f*Time.delta
                    )
                }
            }.tooltip(c.localizedName)
        }
    }

    /**
     * Сравнение версий установленного мода и листинга (beta-1.7: [VersionCompareHelper] вместо
     * регулярки beta-1.6, путавшей теги и числа). Неизвестная версия считается «новее» - как раньше,
     * чтобы мод без версии не превращался в вечное «есть обновление».
     */
    fun tryCompareVersion(aVer: String?, bVer: String?): Int =
        if(aVer == null || bVer == null) 1 else VersionCompareHelper.tryCompareVersion(aVer, bVer)

    /** Диалог скачивания одного мода: содержимое - одна [ModDownloadBar], запускаемая кнопкой «Скачать». */
    fun showDownloadModDialog(modInfo: ModListing, callback: Runnable){
        val bar = ModDownloadBar(modInfo)
        val title = Core.bundle[if(bar.isUpdate) "dialog.mods.updateMod" else "dialog.mods.downloadMod"]

        val dialog = UIUtils.showPane(
            title,
            ButtonEntry(
                Core.bundle["cancel"],
                Icon.cancel
            ){
                bar.cancel()
                it.hide()
            },
            ButtonEntry(
                Core.bundle["misc.download"],
                Icon.download,
                disabled = { bar.downloading || bar.complete }
            ){
                bar.start()
            }
        ){ t -> bar.buildContent(t, withButton = false) }

        //закрыли окно (в т.ч. Esc) посреди скачивания - не оставляем фоновую загрузку
        dialog.hidden{ if(!bar.complete) bar.cancel() }

        bar.onInstalled = {
            callback.run()

            dialog.hide()
            UIUtils.showPane(
                title,
                ButtonEntry(Core.bundle["confirm"], Icon.ok){ d ->
                    callback.run()
                    d.hide()
                }
            ){ t -> bar.buildContent(t, withButton = false) }
        }
    }

    /**
     * Диалог «Установить все» для избранного: по строке [ModDownloadBar] на каждый мод, который
     * вообще можно поставить (устаревшие/неподдерживаемые версии игры отфильтрованы).
     */
    fun showDownloadAllDialog(mods: List<ModListing>, callback: Runnable){
        val installable = mods.filter{ ModStat.run{ it.checkStatus().isValid() } }

        if(installable.isEmpty()){
            UIUtils.showTip(null, Core.bundle["dialog.mods.installAllEmpty"])
            return
        }

        //callback перестраивает весь браузер (сотни карточек), поэтому зовём его ОДИН раз при закрытии окна,
        //а не после каждого из N модов, как делает оригинал; окно модальное - раньше список всё равно не видно
        var anyInstalled = false
        val bars = installable.map{ mod ->
            ModDownloadBar(mod).also{ it.onInstalled = { anyInstalled = true } }
        }

        val dialog = UIUtils.showPane(
            Core.bundle["dialog.mods.installAll"],
            ButtonEntry(
                Core.bundle["cancel"],
                Icon.cancel
            ){
                bars.forEach{ it.cancel() }
                it.hide()
            },
            ButtonEntry(
                Core.bundle["dialog.mods.downloadAll"],
                Icon.download
            ){
                bars.forEach{ bar ->
                    if(!bar.isCurrent && !bar.downloading && !bar.complete) bar.start()
                }
            }
        ){ t ->
            bars.forEach{ bar ->
                bar.buildContent(t)
                t.row()
            }
        }

        dialog.hidden{
            bars.forEach{ if(!it.complete) it.cancel() }
            if(anyInstalled) callback.run()
        }
    }
}

/** Куда и во что качать мод: [url] архива и расширение итогового файла [suffix] (".jar" / ".zip"). */
class DownloadTarget(val url: String, val suffix: String)

/** Выбор артефакта релиза: dexed-jar > любой jar > zip > zipball исходников. */
internal fun resolveAssetTarget(json: Jval): DownloadTarget{
    val assets = json.get("assets").asArray()

    val dexed = assets.find{ it.getString("name").startsWith("dexed") && it.getString("name").endsWith(".jar") }
    val jar = dexed ?: assets.find{ it.getString("name").endsWith(".jar") }
    val archive = jar ?: assets.find{ it.getString("name").endsWith(".zip") }
    val suffix = if(dexed == null && jar == null) ".zip" else ".jar"

    return DownloadTarget(archive?.getString("browser_download_url") ?: json.getString("zipball_url"), suffix)
}

/**
 * Строка скачивания одного мода (порт ModDownloadBar, Helium beta-1.7): карточка с иконкой,
 * версиями, прогресс-баром и кнопкой. Одну и ту же карточку используют диалог одного мода и
 * диалог «Установить все» (там их столбик).
 *
 * Отличия от оригинала: потоки вместо корутин (отмена - [Future.cancel] с interrupt) и стратегия
 * выбора архива sonka - по языку репозитория (см. [resolveDownloadTarget]).
 */
class ModDownloadBar(
    val modInfo: ModListing,
){
    companion object{
        /**
         * «Установить все» не должна выстрелить десятком одновременных запросов к api.github.com:
         * без токена лимит 60 запросов в час, а на мод уходит два (репозиторий + релиз).
         */
        private val apiGate = Semaphore(3)
    }

    var onInstalled: () -> Unit = {}

    @Volatile
    var progress = 0f
        private set

    @Volatile
    var complete = false
        private set

    @Volatile
    var failed = false
        private set

    @Volatile
    var downloading = false
        private set

    @Volatile
    private var cancelled = false

    private var task: Future<*>? = null

    private val loaded: Mods.LoadedMod? get() = Vars.mods.getMod(modInfo.internalName)

    val isCurrent: Boolean get() = loaded?.meta?.version == modInfo.version
    val isUpdate: Boolean get() = loaded?.let{
        it.meta.version != modInfo.version && ModsDialogHelper.tryCompareVersion(it.meta.version, modInfo.version) < 0
    } == true

    fun start(){
        if(downloading || complete) return

        downloading = true
        failed = false
        cancelled = false
        progress = 0f

        task = Downloader.exec.submit{ run() }
    }

    fun cancel(){
        cancelled = true
        task?.cancel(true)
        task = null
        downloading = false
    }

    private fun run(){
        try{
            val target = try{
                apiGate.acquire()
                try{
                    resolveDownloadTarget()
                }finally{
                    apiGate.release()
                }
            }catch(e: InterruptedException){
                return
            }catch(e: Throwable){
                fail(e, "dialog.mods.checkFailed")
                return
            }

            val file = Vars.modDirectory.child("tmp").child(modInfo.internalName + target.suffix)

            try{
                Downloader.downloadToFile(target.url, file, { p -> progress = p })
            }catch(e: InterruptedException){
                file.delete()
                return
            }catch(e: Throwable){
                file.delete()
                fail(e, "dialog.mods.downloadFailed")
                return
            }

            //импорт трогает загрузчик модов и UI - только в основном потоке; отмена в последний момент не ставит мод
            Core.app.post{
                if(cancelled) file.delete() else install(file)
            }
        }catch(e: Throwable){
            //страховка: что бы ни случилось в фоне, кнопка не должна остаться «вечно качает»
            downloading = false
            Log.err(e)
        }
    }

    /**
     * sonka: узнаём язык репозитория ПЕРЕД выбором стратегии скачивания - ровно как ваниль-браузер
     * (ModsDialog.githubImport/githubImportJavaMod/githubImportBranch). Порт Helium раньше ВСЕГДА
     * бил в /releases/latest - для script-модов (JS, не JVM-язык) это 404, если автор не публиковал
     * GitHub Release (частый случай, см. aazamitsu/anime-units-display - ни одного релиза и ни одного
     * тега), и установка ЛЮБОГО такого мода падала с "Failed to check for updates"-подобной ошибкой.
     * Для не-JVM берём архив ветки по умолчанию; редирект api.github.com -> codeload Arc проходит сам
     * (followRedirects включён по умолчанию), поэтому ретраи и прогресс общие с обычной загрузкой.
     */
    private fun resolveDownloadTarget(): DownloadTarget{
        val repoJson = Jval.read(Downloader.getString(Vars.ghApi + "/repos/" + modInfo.repo))
        val language = repoJson.getString("language", "<none>")
        val isJvm = language == "Java" || language == "Kotlin" || language == "Groovy" || language == "Scala"

        if(!isJvm){
            val defaultBranch = repoJson.getString("default_branch")
            return DownloadTarget(Vars.ghApi + "/repos/" + modInfo.repo + "/zipball/" + defaultBranch, ".zip")
        }

        return resolveAssetTarget(Jval.read(Downloader.getString(Vars.ghApi + "/repos/" + modInfo.repo + "/releases/latest")))
    }

    fun buildContent(content: Table, withButton: Boolean = true){
        val icon = ModsDialogHelper.icons.resolve(ModsDialogHelper.iconUrl(modInfo.repo))

        content.table(HeAssets.darkGrayUIAlpha){ cont ->
            cont.table(mindustry.gen.Tex.buttonSelect){ t ->
                t.image(icon).scaling(Scaling.fit).size(80f)
            }.pad(10f).margin(4f).size(88f)

            cont.stack(
                Table{ info -> buildInfo(info) },
                Table{ info -> buildStatus(info) },
            ).pad(12f).padLeft(4f).growX().fillY().minWidth(420f)

            if(withButton){
                cont.table{ buttons ->
                    buttons.defaults().size(48f).pad(4f)

                    buttons.button(Icon.downloadSmall, Styles.clearNonei, 48f){ start() }
                        .disabled{ downloading || complete }
                        .update{ b ->
                            b.image.setScale(0.9f)
                            b.style.imageUpColor = when{
                                complete -> mindustry.graphics.Pal.heal
                                failed -> Color.crimson
                                else -> Color.white
                            }
                        }
                }.pad(8f)
            }
        }.margin(6f).growX().fillY()
    }

    private fun buildInfo(info: Table){
        info.left().top().defaults().left().pad(3f)
        info.add(modInfo.name).color(mindustry.graphics.Pal.accent)
        info.row()

        val installed = loaded
        when{
            installed == null -> info.add(modInfo.version)
            installed.meta.version == modInfo.version ->
                info.add("[lightgray]${installed.meta.version}  " + Core.bundle["dialog.mods.installed"])
            isUpdate ->
                info.add("[lightgray]${installed.meta.version}  >>>  [accent]${modInfo.version}")
            else ->
                info.add("[lightgray]${installed.meta.version}  >>>  ${modInfo.version}" + Core.bundle["dialog.mods.reinstall"])
        }

        info.row()
        info.table{ bar ->
            bar.add(
                Bar(
                    {
                        when{
                            failed -> Core.bundle["dialog.mods.downloadFailed"]
                            complete -> Core.bundle["dialog.mods.downloadComplete"]
                            else -> Core.bundle.format(
                                "dialog.mods.downloading",
                                if(progress < 0) (-progress).toStoreSize()
                                else "${Mathf.round(progress*100)}%"
                            )
                        }
                    },
                    { if(failed) Color.crimson else mindustry.graphics.Pal.accent },
                    { if(progress < 0) 1f else progress }
                )
            ).growX().pad(6f).height(22f).visible{ downloading || complete || failed }
        }.grow()
    }

    private fun buildStatus(info: Table){
        info.top().right().defaults().right().top()
        info.table{ status ->
            status.top().right().defaults().size(26f).pad(4f)
            ModsDialogHelper.buildModAttrIcons(status, ModsDialogHelper.run{ modInfo.checkStatus() })
        }.fill()
        info.row()
        info.table{ stars ->
            stars.bottom().right()
            ModsDialogHelper.buildStars(stars, modInfo)
        }
    }

    private fun fail(error: Throwable, messageKey: String){
        downloading = false
        failed = true

        Log.err(error)
        Core.app.post{ UIUtils.showException(error, Core.bundle[messageKey]) }
    }

    private fun install(file: Fi){
        try{
            if(isUpdate) loaded?.also{ Vars.mods.removeMod(it) }

            Vars.mods.importMod(file)
            file.delete()

            complete = true
            downloading = false
            onInstalled()
        }catch(e: Exception){
            Log.err(e)
            downloading = false
            failed = true
            UIUtils.showException(e, Core.bundle["dialog.mods.downloadFailed"])
        }
    }
}

/**
 * Ключ мода "автор-имя" (порт Name из ModsDialogHelper мода): нечувствителен к регистру,
 * автор "*" (неизвестен) матчится с любым - для сопоставления установленных модов с листингом.
 */
class Name(
    author: String,
    name: String,
){
    val author = author.lowercase()
    val name = name.lowercase()

    private val hash = this.name.hashCode()

    constructor(loaded: Mods.LoadedMod) : this(loaded.meta.author ?: "*", loaded.name)
    constructor(loaded: ModListing) : this(loaded.author ?: "*", loaded.internalName)

    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean{
        if(this === other) return true
        if(other !is Name) return false

        if(author != "*" && other.author != "*" && author != other.author) return false
        if(name != other.name) return false

        return true
    }

    override fun toString() = "$author-$name"
}
