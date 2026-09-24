package mindustry.client

import arc.*
import arc.graphics.*
import arc.math.geom.*
import arc.struct.*
import arc.util.*
import mindustry.*
import mindustry.client.communication.*
import mindustry.client.crypto.*
import mindustry.client.navigation.*
import mindustry.client.utils.*
import mindustry.core.*
import mindustry.entities.units.*
import mindustry.game.*
import mindustry.game.Teams.*
import mindustry.gen.*
import mindustry.input.*
import mindustry.ui.fragments.*
import agzam4.AgzamMod
import eui.EUIMod
import mi2u.MI2UMod
import qol.QolSuiteMod
import qolc.QolControlMod
import sectorstats.CampaignUtilsMod
import scheme.SchemeSizeMod
import mindustrytool.MindustryToolMod
import mobilepause.MobilePauseMod
import java.nio.file.Files
import java.security.cert.*
import java.util.Timer
import java.util.concurrent.*
import kotlin.concurrent.*
import kotlin.math.*
import kotlin.random.Random

object Main : ApplicationListener {
    private lateinit var communicationSystem: SwitchableCommunicationSystem
    private lateinit var communicationClient: Packets.CommunicationClient
    private var dispatchedBuildPlans = mutableListOf<BuildPlan>()
    private val buildPlanInterval = Interval()
    val tlsPeers = CopyOnWriteArrayList<Pair<Packets.CommunicationClient, TlsCommunicationSystem>>()
    lateinit var keyStorage: KeyStorage
    lateinit var signatures: Signatures
    val ntp = NTP()
    private var planSendTime = 0L
    private var isSendingPlans = false

    /** Run on client load. */
    override fun init() {
        val start = Time.nanos()

        // sonka's qol-suite, baked in as native code rather than a loadable mod - this is where
        // TileRecords.init() used to sit before the antigrief cut, i.e. the earliest point that
        // reliably runs before mods.eachClass(Mod::init) and Events.fire(ClientLoadEvent) further
        // down the ApplicationCore.update() listener-init loop (see ClientLauncher.update()).
        // QolSuiteMod's constructor only registers Events.on(...) listeners, so it must be
        // instantiated before ClientLoadEvent actually fires.
        if (ComponentBoot.enabled("qol")) QolSuiteMod()

        // sonka's Extended UI++, baked in the same way and for the same reason - see EUIMod's own
        // javadoc for the settings-category/self-disable-guard rationale.
        if (ComponentBoot.enabled("eui")) EUIMod()

        // sonka's "Campaign Utils" (sector production stats + no-landing sector preview), baked in the
        // same way and for the same reason - see CampaignUtilsMod's own javadoc.
        if (ComponentBoot.enabled("campaignutils")) CampaignUtilsMod()

        // Port of the third-party "QoL Control" mod's REMAINDER (most of it already exists as
        // qol-suite/EUI/native features) - see QolControlMod's javadoc for the full inventory.
        if (ComponentBoot.enabled("qolc")) QolControlMod()

        // Port of the third-party "MI2-Utilities Java" mod (BlackDeluxeCat, v1.15.2) - Mindow2
        // window framework, zone/distribution rendering, Core Info, FullAI, Logic Helper etc.
        // See MI2UMod's javadoc for the adaptation notes and the self-disable guard.
        if (ComponentBoot.enabled("mi2u")) MI2UMod()

        // Port of the third-party "Agzam's Mod" (Agzam4, v155.4.a) REMAINDER - industry calculator,
        // processor/display generators, unit spawner, AFK auto-reply, custom light render, unlocks,
        // chat gradient. See AgzamMod's javadoc for the full skip-list (most of the mod already
        // exists natively or via qol/eui/mi2u).
        if (ComponentBoot.enabled("agzam4")) AgzamMod()

        // Port of the third-party "Scheme Size Port" mod (00SunRay00/RE2b2m22 v2.2.0) REMAINDER -
        // admin tools (unit spawn/team switch/effects/items/teleport), Rule Setter, building tools
        // (fill/square/circle/replace/chain-remove/power-connect), schematic layers, renderer
        // extras (x-ray/grid/ruler), wave-approaching alert. See SchemeSizeMod's javadoc for the
        // full skip-list (schematic limit, CLaJ, image parser etc. already exist natively).
        SchemeSizeMod()

        // Port of the third-party "Mindustry Tool" mod (Sharlotte/MindustryVN v4.58.6-v8) - the
        // mindustry-tool.com integrations: content browser (schematics+maps with tag/planet/sort
        // filters), site login, global cross-server chat with translation (their API/Gemini/DeepL),
        // Player Connect relay rooms, plus smart drill/smart upgrade, quick-access HUD, custom menu
        // background, custom music and a time control bar. See MindustryToolMod's javadoc for the
        // full skip-list (autoplay, god mode and every visualizer already exist natively or via
        // mi2u/eui/scheme/qol).
        if (ComponentBoot.enabled("mindustrytool")) MindustryToolMod()

        // sonka: уведомление о незапитанном начале протянутой цепочки труб/конвейеров
        // (sonkaextras.ChainWarn - см. его javadoc). Как и моды выше, init() только вешает
        // слушатели и должен отработать до фаера ClientLoadEvent.
        sonkaextras.ChainWarn.init()

        // sonka: рестарт сектора / автоснапшот на старте волны для кнопки «Повторить волну»
        // (sonkaextras.CampaignRetry - см. его javadoc)
        sonkaextras.CampaignRetry.init()

        // sonka: метка над юнитом с ником последнего управлявшего им игрока (sonkaextras.LastController)
        sonkaextras.LastController.init()

        // sonka: «что нового» после обновления - заметки релизов custom-b(M..N] с GitHub
        // (sonkaextras.WhatsNew, правила показа в его javadoc); тоже только вешает слушатель
        sonkaextras.WhatsNew.init()

        // sonka: единый список хоткеев всех пакетов по F2 (sonkaextras.hotkeys.HotkeysDialog)
        sonkaextras.hotkeys.HotkeysDialog.init()

        // sonka: профили кампании - только стартовая сверка маркера активного профиля с settings
        // (sonkaextras.campaign.CampaignProfiles, раздел «Маркер» в javadoc); сам диалог - по кнопкам
        sonkaextras.campaign.CampaignProfiles.init()

        // Port of the third-party "Helium" mod (EB-wilson, beta-1.6) SELECTION - gauss-blur UI
        // background, quick block palette in the placement panel, reworked mods manager/browser.
        // Attack/effect range outlines and shield stacks deliberately skipped (sonka's call) -
        // see HeliumMod's javadoc for the adaptation notes and the self-disable guard.
        if (ComponentBoot.enabled("helium")) helium.HeliumMod()

        // Port of the third-party "Extra Editor" mod (KlasterX, v1.0) - MAP EDITOR utilities:
        // tile copy/cut/paste with rotate/flip and ghost preview, custom brush shapes, block
        // replace mode, advanced grid, undo list with per-operation revert. Lives entirely
        // inside MapEditorDialog - see ExtraEditorMod's javadoc for the adaptation notes.
        if (ComponentBoot.enabled("extraeditor")) extraeditor.ExtraEditorMod()

        // Port of the third-party "New Console Hardline" mod (Mnemotechnician/SMOLKEYS, v2.3) -
        // advanced JS console: syntax-highlighting code editor, log panel, execution history,
        // saved scripts, event-driven autorun and a file browser. Coexists with the native F8
        // console (shared JS scope) - see NewConsoleMod's javadoc for the adaptation notes.
        if (ComponentBoot.enabled("newconsole")) newconsole.NewConsoleMod()

        // Port of the third-party "PatchEditor" mod (minRi2/Dustdustry, v1.13.1) - in-game GUI
        // for the NATIVE v8 content-patch system (mindustry.mod.DataPatcher): visual patch
        // editing with a field tree, notes, selectors, undo/redo and HJSON/JSON export. Mounts
        // into the pause menu and the map assets dialog - see Main's javadoc (dustdustry
        // package) for the adaptation notes.
        if (ComponentBoot.enabled("patcheditor")) dustdustry.patcheditor.Main()

        // Port of the third-party "Mapping Utilities" mod (ApsZoldat, v1.9) - MAP EDITOR dialog
        // extensions: hidden map rules (fog colors, border darkness, drag, Env flags, any-team
        // rules 0-255, mode name, mission...), planet-background editor, better banned/revealed
        // content dialogs (planet tab filter), map resize limit bypass. Its own WIP editor is
        // disabled upstream and skipped - see MappingUtilitiesMod's javadoc.
        if (ComponentBoot.enabled("mu")) mu.MappingUtilitiesMod()

        // Port of the third-party "Testing Utilities" mod (MEEPofFaith, v69.10) IN FULL - sandbox
        // panel in the bottom-left HUD corner (BLUI): unit spawner + wave picker, block placer,
        // in-game terrain painter, status effects, world/weather menu, sandbox toggle, fill/dump
        // core, team changer, heal/invincibility, clone/self-destruct, light switch, Alt+click
        // teleport; plus the Interp visualizer and the sound room. Overlaps with scheme admin
        // tools / agzam4 spawner are kept on purpose - sonkaextras.AdminPanel lets sonka pick
        // which HUD panel is shown. See TestUtilsMod's javadoc for the inventory and skip-list.
        if (ComponentBoot.enabled("testing")) testing.TestUtilsMod()

        // Port of the third-party "Too Many Items" mod (EB-wilson, v3.2, Kotlin) - NEI/JEI-style
        // recipe browser (what produces X / where X is used / what a factory does, all vanilla
        // block types parsed, mod recipe API kept) plus the Schematic Calculator (recipe-graph
        // planner with balancing, statistics, PNG/text export). UniverseKit markdown/reflection
        // replaced by client StupidMarkupParser/arc Reflect - see TooManyItems' KDoc.
        if (ComponentBoot.enabled("tmi")) tmi.TooManyItems()

        // Port of the third-party "Mobile Pause" mod (Promiha27/ARCYDE, mobile-pause v1.2) -
        // map-screenshot button next to the mobile pause button. The mod's actual pause-button
        // fix was applied directly to HudFragment.java instead (root engine bug, not a mod
        // feature) - see MobilePauseMod's javadoc for the full story and self-disable guard.
        if (ComponentBoot.enabled("mobilepause")) MobilePauseMod()

        if (Core.app.isDesktop) {
            communicationSystem = SwitchableCommunicationSystem(BlockCommunicationSystem, PluginCommunicationSystem) // FINISHME: Profile this, it takes ~40ms which it really shouldn't
            communicationSystem.init()

            keyStorage = KeyStorage(Core.settings.dataDirectory.file())
            signatures = Signatures(keyStorage, ntp.clock)
        } else {
            keyStorage = KeyStorage(Files.createTempDirectory("keystorage").toFile())
            communicationSystem = SwitchableCommunicationSystem(DummyCommunicationSystem(mutableListOf()))
            communicationSystem.init()
        }

        communicationClient = Packets.CommunicationClient(communicationSystem)

        Navigation.navigator = AStarNavigatorOptimised // Man this class is heavy, it takes ~10ms to load

        Events.on(EventType.WorldLoadEvent::class.java) {
            if (!Vars.net.client()) { // This is so scuffed but shh
                setPluginNetworking(false)
                CommandCompletion.reset(true)
                NetServer.serverPacketReliable(Vars.player, "fooCheck", "") // Call locally
            }
            dispatchedBuildPlans.clear()
        }

        Events.on(EventType.ServerJoinEvent::class.java) {
            setPluginNetworking(false)
            CommandCompletion.reset(true)
            if (!Server.current.ghost) Call.serverPacketReliable("fooCheck", "") // Request version info FINISHME: The server should just send this info on join
        }

        /** @since v1 Checks for the presence of the foo plugin on the server */
        Vars.netClient.addPacketHandler("fooCheck") { version ->
            if (Server.current.ghost) return@addPacketHandler

            Log.debug("Server using client plugin version $version")
            if (!Strings.canParsePositiveFloat(version)) return@addPacketHandler

            ClientVars.pluginVersion = Strings.parseFloat(version)
        }

        /** @since v2 Toggles the state of plugin networking */
        Vars.netClient.addPacketHandler("fooTransmissionEnabled") { e ->
            val enabled = e.toBoolean()
            Log.debug("Server set transmissions to: $enabled")
            setPluginNetworking(enabled)
        }

        communicationClient.addListener { transmission, senderId ->
            when (transmission) {
                is BuildQueueTransmission -> {
                    if (senderId == communicationSystem.id) return@addListener
                    val path = Navigation.currentlyFollowing as? BuildPath ?: return@addListener
                    if (path.queues.contains(path.networkAssist)) {
                        val positions = IntSet()
                        for (plan in path.networkAssist) positions.add(Point2.pack(plan.x, plan.y))

                        for (plan in transmission.plans.sortedByDescending { it.dst(Vars.player) }) {
                            if (path.networkAssist.size > 1000) return@addListener  // too many plans, not accepting new ones
                            if (positions.contains(Point2.pack(plan.x, plan.y))) continue
                            path.networkAssist.add(plan)
                        }
                    }
                }

                is TlsRequestTransmission -> {
                    val cert = keyStorage.cert() ?: return@addListener
                    if (transmission.destinationSN != cert.serialNumber) return@addListener

                    val key = keyStorage.key() ?: return@addListener
                    val chain = keyStorage.chain() ?: return@addListener
                    val expected = keyStorage.findTrusted(transmission.sourceSN) ?: return@addListener

                    val peer = TlsClientHolder(cert, chain, expected, key)
                    val comms = TlsCommunicationSystem(peer, communicationClient, cert)
                    val commsClient = Packets.CommunicationClient(comms)

                    registerTlsListeners(commsClient, comms)

                    tlsPeers.add(Pair(commsClient, comms))
                }

                // tls peers handle data transmissions internally

                is SignatureTransmission -> {
                    var isValid = check(transmission)

                    next(EventType.PlayerChatEventClient::class.java, repetitions = 3) {
                        if (isValid) return@next
                        isValid = check(transmission)
                    }
                }

                is CommandTransmission -> {
                    transmission.type ?: return@addListener
                    if (transmission.verify()) transmission.type.lambda(transmission)
                }

                is ClientMessageTransmission -> {
                    if (senderId != Vars.player.id) transmission.addToChatfrag()
                }

                is ImageTransmission -> {
                    Log.debug("Received image transmission")
                    val msg = findMessage(transmission.message) ?: return@addListener
                    msg.attachments.add(Texture(transmission.image)).shrink()
                    transmission.image.dispose()
                }

                is SchematicTransmission -> {
                    transmission.addToChat()
                }
            }
        }

        Log.debug("Main in @ms", Time.millisSinceNanos(start))
    }

    private fun findMessage(id: Short): ChatFragment.ChatMessage? {
        val ending = InvisibleCharCoder.encode(id.toBytes())
        return Vars.ui.chatfrag.messages.lastOrNull { it.unformatted?.endsWith(ending) == true }
    }

    /** @return if it's done or not, NOT if it's valid */
    private fun check(transmission: SignatureTransmission): Boolean {
        fun invalid(msg: ChatFragment.ChatMessage, cert: X509Certificate?) {
            msg.sender = cert?.run { keyStorage.aliasOrName(this) }?.stripColors()?.run {
                if (Core.settings.getBool("showclientmsgsendername")) "$this (${msg.sender}[white])" else this
            }?.plus("[scarlet] impersonator") ?: "Verification failed"
            msg.backgroundColor = ClientVars.invalid
            msg.prefix = "${Iconc.cancel} ${msg.prefix} "
            msg.format()
        }

        val msg = findMessage(transmission.messageId) ?: return false

        if (!msg.message.endsWith(msg.unformatted)) { invalid(msg, null); Log.debug("Does not end with unformatted!") }

        if (!Core.settings.getBool("highlightcryptomsg")) return true
        val output = signatures.verifySignatureTransmission(msg.unformatted.encodeToByteArray(), transmission)

        return when (output.first) {
            Signatures.VerifyResult.VALID -> {
                msg.sender = output.second?.run { keyStorage.aliasOrName(this) }.plus(if (Core.settings.getBool("showclientmsgsendername")) " (${msg.sender}[white])" else "")
                msg.backgroundColor = if(keyStorage.builtInCerts.contains(output.second)) ClientVars.developerMsgBackground else ClientVars.verified
                msg.prefix = "${Iconc.ok} ${msg.prefix}"
                msg.findCoords()
                msg.findLinks()
                msg.format()
                true
            }
            Signatures.VerifyResult.INVALID -> {
                invalid(msg, output.second)
                true
            }
            Signatures.VerifyResult.UNKNOWN_CERT -> {
                true
            }
        }
    }

    fun sign(content: String): String {
        if (content.startsWith("/") && !(content.startsWith("/t ") || content.startsWith("/a ")) ||
            ((content == "y" || content == "n") && Darkdustry())) return content

        val msgId = Random.nextBits(16).toShort()
        val contentWithId = content + InvisibleCharCoder.encode(msgId.toBytes())

        communicationClient.send(signatures.signatureTransmission(
            NetClient.processCoords(contentWithId.replace("^/[t|a] ".toRegex(), ""), false).encodeToByteArray(),
            communicationSystem.id,
            msgId) ?: return contentWithId)

        return contentWithId
    }

    /** Run once per frame. */
    override fun update() {
        communicationClient.update()

        if (Core.scene.keyboardFocus == null && Core.input?.keyTap(Binding.sendBuildQueue) == true) {
            ClientVars.dispatchingBuildPlans = !ClientVars.dispatchingBuildPlans
        }

        if (ClientVars.dispatchingBuildPlans && Vars.player.unit() != null) {
            if (!Vars.net.client()) Vars.player.unit().plans.each { if (BuildPlanCommunicationSystem.isNetworking(it)) return@each; addBuildPlan(it) } // Player plans -> block ghosts in single player
            if (!isSendingPlans && !communicationClient.inUse && Groups.player.size() > 1 && buildPlanInterval.get(max(5 * 60f, planSendTime / 16.666f + 3 * 60))) sendBuildPlans()
        }

        for (peer in tlsPeers) {
            if (peer.second.isClosed) tlsPeers.remove(peer)
            peer.second.update()
            peer.first.update()
        }
    }

    fun connectTls(dstCert: X509Certificate, onFinish: ((Packets.CommunicationClient) -> Unit)? = null, onError: (() -> Unit)? = null) {
        val cert = keyStorage.cert() ?: return
        val key = keyStorage.key() ?: return
        val chain = keyStorage.chain() ?: return

        val peer = TlsServerHolder(cert, chain, dstCert, key)
        val comms = TlsCommunicationSystem(peer, communicationClient, cert)

        val commsClient = Packets.CommunicationClient(comms)
        registerTlsListeners(commsClient, comms)

        peer.onHandshakeFinish = {
            onFinish?.invoke(commsClient)
        }

        communicationClient.send(TlsRequestTransmission(cert.serialNumber, dstCert.serialNumber), onError = onError)
        Timer().schedule(500L) { tlsPeers.add(Pair(commsClient, comms)) }
    }

    fun setPluginNetworking(enable: Boolean) {
        if (!enable) ClientVars.pluginVersion = -1F
        when {
            enable -> {
                communicationSystem.activeCommunicationSystem = PluginCommunicationSystem
            }
            Core.app?.isDesktop == true -> {
                communicationSystem.activeCommunicationSystem = BlockCommunicationSystem
            }
            else -> {
                communicationSystem.activeCommunicationSystem = DummyCommunicationSystem(mutableListOf())
            }
        }
    }

    fun send(transmission: Transmission, onFinish: Runnable? = null) {
        communicationClient.send(transmission, onFinish)
    }

    /** Uses [Tmp.v1], do not cache returned vec or call this function on non-main thread. */
    fun floatEmbed(): Vec2 {
        val show = Core.settings.getBool("displayasuser")
        return when {
            Vars.player.dead() -> Tmp.v1.set(0F, 0F)
            Server.current.ghost -> Tmp.v1.set(Vars.player.unit().aimX, Vars.player.unit().aimY)
            Navigation.currentlyFollowing is AssistPath && show ->
                Tmp.v1.set(
                    FloatEmbed.embedInFloat(Vars.player.unit().aimX, ClientVars.FOO_USER),
                    FloatEmbed.embedInFloat(Vars.player.unit().aimY, ClientVars.ASSISTING)
                )
            Navigation.currentlyFollowing is AssistPath ->
                Tmp.v1.set(
                    FloatEmbed.embedInFloat(Vars.player.unit().aimX, ClientVars.ASSISTING),
                    FloatEmbed.embedInFloat(Vars.player.unit().aimY, ClientVars.ASSISTING)
                )
            show ->
                Tmp.v1.set(
                    FloatEmbed.embedInFloat(Vars.player.unit().aimX, ClientVars.FOO_USER),
                    FloatEmbed.embedInFloat(Vars.player.unit().aimY, ClientVars.FOO_USER)
                )
            else -> Tmp.v1.set(Vars.player.unit().aimX, Vars.player.unit().aimY)
        }
    }

    private fun sendBuildPlans(num: Int = 500) {
        var count = 0
        val unit = Vars.player.unit() ?: return
        val toSend = unit.plans.toList().takeLastWhile { !BuildPlanCommunicationSystem.isNetworking(it) && count++ < num }.toTypedArray()
        if (toSend.isEmpty()) return
        isSendingPlans = true
        val start = Time.millis()
        communicationClient.send(BuildQueueTransmission(toSend), { isSendingPlans = false; planSendTime = Time.timeSinceMillis(start) })
        dispatchedBuildPlans.addAll(toSend)
    }

    /** Singleplayer/host use only */
    private fun addBuildPlan(plan: BuildPlan) {
        if (plan.breaking) return
        if (plan.isDone) {
            Vars.player.unit().plans.remove(plan)
            return
        }

        val data = Vars.player.team().data()
        for (i in 0 until data.plans.size) {
            val b = data.plans[i]
            if (b.x == plan.x.toShort() && b.y == plan.y.toShort()) {
                data.plans.removeIndex(i)
                break
            }
        }
        data.plans.addFirst(BlockPlan(plan.x, plan.y, plan.rotation.toShort(), plan.block, plan.config))
    }

    private fun registerTlsListeners(commsClient: Packets.CommunicationClient, system: TlsCommunicationSystem) {
        commsClient.addListener { transmission, _ ->
            when (transmission) {
                is MessageTransmission -> {
                    ClientVars.lastCertName = system.peer.expectedCert.readableName
                    Vars.ui.chatfrag.addMessage(transmission.content,
                        keyStorage.aliasOrName(system.peer.expectedCert),
                        ClientVars.encrypted,
                        "[green]${Iconc.ok} [coral][[[white]${keyStorage.aliasOrName(system.peer.expectedCert)}[accent] -> [white]${keyStorage.cert()?.readableName ?: "you"}[coral]]:[white] ",
                        transmission.content
                    ).run { prefix = "${Iconc.ok} $prefix " }
                }

                is CommandTransmission -> {
                    transmission.type ?: return@addListener
                    if (transmission.verify()) transmission.type.lambda(transmission)
                }
            }
        }
    }
}
