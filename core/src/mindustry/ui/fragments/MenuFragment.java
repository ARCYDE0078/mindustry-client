package mindustry.ui.fragments;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.actions.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.ImageButton.*;
import arc.scene.ui.TextButton.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.client.ui.*;
import mindustry.core.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;

import static mindustry.Vars.*;
import static mindustry.gen.Tex.*;

public class MenuFragment{
    private Table container, submenu;
    private Button currentMenu;
    public MenuRenderer renderer;
    private Seq<MenuButton> customButtons = new Seq<>();
    public Seq<MenuButton> desktopButtons = null;

    public void build(Group parent){
        renderer = new MenuRenderer();

        Group group = new WidgetGroup();
        group.setFillParent(true);
        group.visible(() -> !ui.editor.isShown());
        parent.addChild(group);

        parent = group;

        parent.fill((x, y, w, h) -> renderer.render());

        parent.fill(c -> {
            c.pane(Styles.noBarPane, cont -> {
                container = cont;
                cont.name = "menu container";

                if(!mobile){
                    c.left();
                    buildDesktop();
                    Events.on(ResizeEvent.class, event -> buildDesktop());
                }else{
                    buildMobile();
                    Events.on(ResizeEvent.class, event -> buildMobile());
                }
            }).with(pane -> {
                pane.setOverscroll(false, false);
            }).grow();
        });

        parent.fill(c -> c.bottom().right().button(Icon.discord, new ImageButtonStyle(){{ // FINISHME: This is cursed af
            up = discordBanner;
        }}, ui.discord::show).visible(() -> !ui.consolefrag.shown()).marginTop(9f).marginLeft(10f).tooltip("@discord").size(84, 45).name("discord"));

        parent.fill(c ->
            c.bottom().left().image(
                    Core.atlas.find("monolith")
            ).tooltip("@client.tagline")
            .size(60, 60).pad(5)
        );

        //info icon
        if(mobile){
            //left/right gutter areas
            parent.fill((x, y, w, h) -> {
                x = 0f;
                y = 0f;
                w = Core.graphics.getWidth();
                h = Core.graphics.getHeight();
                if(Core.scene.marginLeft > 0){
                    paneRight.draw(x, y, Core.scene.marginLeft, h);
                }

                if(Core.scene.marginRight > 0){
                    paneLeft.draw(x + w - Core.scene.marginRight, y, Core.scene.marginRight, h);
                }

                if(Core.scene.marginBottom > 0){
                    Tex.paneTop.draw(Core.scene.marginLeft, 0, Core.graphics.getWidth() - Core.scene.marginRight - Core.scene.marginLeft, Core.scene.marginBottom);
                }
            });

            parent.fill(c -> c.bottom().left().button("", new TextButtonStyle(){{
                font = Fonts.def;
                fontColor = Color.white;
                up = infoBanner;
            }}, ui.about::show).size(84, 45).name("info"));
        }else{
            parent.fill(c -> {
                // Uninstall foo's button
                c.bottom().right().button("@client.uninstall", Icon.trash, () ->
                    ui.showConfirm("@client.uninstall.title", Core.bundle.get("client.uninstall.body") + (steam ? Core.bundle.get("client.uninstall.body.append.steam") : ""), () -> {
                        var installerMod = Vars.mods.getMod("fooinstaller");
                        if(installerMod != null) Vars.mods.setEnabled(installerMod, false);
                        if(steam){
                            platform.checkIntegrity();
                        }else{
                            ui.loadfrag.show();
                            becontrol.checkUpdate(result -> {
                                ui.loadfrag.hide();
                                if(!result){
                                    ui.showInfo("@be.noupdates");
                                }else{
                                    becontrol.showUpdateDialog();
                                }
                            }, "anuken/mindustry", false);
                        }
                    })
                ).size(200, 60).padRight(10);

                // Switch to lower major version (v7)
                c.bottom().right().button("@client.version.swap.v7", Icon.download, () -> {
                    ui.loadfrag.show();
                    becontrol.checkUpdate(result -> {
                        ui.loadfrag.hide();
                        if(!result){
                            ui.showInfo("@be.noupdates");
                        }else{
                            becontrol.showUpdateDialog();
                        }
                    }, "mindustry-antigrief/mindustry-client-v7-builds", false);
                }).size(200, 60).padRight(10);

                // "Switch to (un)stable" button
                c.button("", Icon.refresh, () -> {
                    Core.settings.put("updateurl", (Core.settings.getString("updateurl") + "-v8-builds").replaceFirst("((-v[6-8])?-builds) {2}", ""));
                    ui.loadfrag.show();
                    becontrol.checkUpdate(result -> {
                        ui.loadfrag.hide();
                        if(!result){
                            showNoUpdates();
                        }else{
                            becontrol.showUpdateDialog();
                        }
                    });
                }).size(200, 60).padRight(10).update(t -> t.getLabel().setText(Core.settings.getString("updateurl").endsWith("-builds") ? "@client.version.swap.stable" : "@client.version.swap.unstable")).disabled(true); // FINISHME: Re-enable when v7 releases

                // "Check for updates" button
                c.bottom().right().button("@be.check", Icon.refresh, () -> {
                    ui.loadfrag.show();
                    becontrol.checkUpdate(result -> {
                        ui.loadfrag.hide();
                        if(!result){
                            showNoUpdates();
                        }else{
                            becontrol.showUpdateDialog();
                        }
                    });
                }).size(200, 60).name("becheck").update(t -> t.getLabel().setColor(becontrol.isUpdateAvailable() ? Tmp.c1.set(Color.white).lerp(Pal.accent, Mathf.absin(5f, 1f)) : Color.white));
            });
        }

        parent.fill((x, y, w, h) -> {
            String versionText = ((Version.build == -1) ? "[#fc8140aa]" : "[#ffffffba]") + Version.combined() + Strings.format("\n[gray]Don't press H[]\nCursedness Level: @", CursednessLevel.fromInteger(Core.settings.getInt("cursednesslevel")).name());

            float width = Core.graphics.getWidth(), height = Core.graphics.getHeight() - Core.scene.marginTop;
            float maxLogoWidth = Core.graphics.getWidth() - Scl.scl(20);

            float fx = (int)(width / 2f);
            LogoLayout logo = measureLogo(maxLogoWidth);
            float fy = (int)(height - 6 - logo.height) + logo.height / 2 - (Core.graphics.isPortrait() ? Scl.scl(30f) : 0f);
            if(Core.settings.getBool("macnotch") ){
                fy -= Scl.scl(macNotchHeight);
            }
            drawLogo(logo, fx, fy);

            Fonts.outline.setColor(Color.white);
            Fonts.outline.draw(versionText, fx, fy - logo.height/2f - Scl.scl(2f), Align.center);
        }).touchable = Touchable.disabled;
    }

    /** sonka: описывает, что и с каким масштабом рисовать в качестве лого главного меню - либо
     * стандартную картинку "Mindustry", либо буквы из своего текста (см. measureLogo/drawLogo). */
    private static class LogoLayout{
        String custom;
        TextureRegion[] regs;
        TextureRegion stockLogo;
        float width, height, scale, spacing;
    }

    /** sonka: считает размеры и раскладку лого без отрисовки (нужно заранее, чтобы узнать logoh и посчитать fy).
     * Если в настройках задан свой текст (customlogotext), раскладывает его из спрайтов logofont-*
     * вместо стандартной картинки "Mindustry" (только A-Z и 0-9, пробел = отступ). */
    private LogoLayout measureLogo(float maxWidth){
        LogoLayout l = new LogoLayout();
        l.custom = sanitizeLogoText(Core.settings.getString("customlogotext", ""));

        if(l.custom.isEmpty()){
            l.stockLogo = Core.atlas.find("logo");
            float logoscl = Scl.scl(1) * l.stockLogo.scale;
            l.width = Math.min(l.stockLogo.width * logoscl, maxWidth);
            l.height = l.width * (float)l.stockLogo.height / l.stockLogo.width;
            return l;
        }

        l.spacing = 6f;
        l.regs = new TextureRegion[l.custom.length()];
        float nativeW = 0f, nativeH = 0f, logoscl = Scl.scl(1);
        for(int i = 0; i < l.custom.length(); i++){
            char c = l.custom.charAt(i);
            if(c == ' '){
                nativeW += 40f;
            }else{
                TextureRegion reg = Core.atlas.find("logofont-" + Character.toLowerCase(c));
                l.regs[i] = reg;
                logoscl = Scl.scl(1) * reg.scale;
                nativeW += reg.width;
                nativeH = Math.max(nativeH, reg.height);
            }
            if(i < l.custom.length() - 1) nativeW += l.spacing;
        }
        l.scale = nativeW <= 0f ? 1f : Math.min(nativeW * logoscl, maxWidth) / nativeW;
        l.width = nativeW * l.scale;
        l.height = nativeH * l.scale;
        return l;
    }

    /** sonka: рисует лого, уже посчитанное через measureLogo, с центром по X в fx и по Y в fy. */
    private void drawLogo(LogoLayout l, float fx, float fy){
        Draw.color();

        if(l.custom.isEmpty()){
            Draw.rect(l.stockLogo, fx, fy, l.width, l.height);
            return;
        }
        if(l.width <= 0f) return;

        float dx = fx - l.width / 2f;
        for(int i = 0; i < l.custom.length(); i++){
            char c = l.custom.charAt(i);
            if(c == ' '){
                dx += 40f * l.scale;
            }else{
                TextureRegion reg = l.regs[i];
                float gw = reg.width * l.scale, gh = reg.height * l.scale;
                Draw.rect(reg, dx + gw / 2f, fy - l.height / 2f + gh / 2f, gw, gh);
                dx += gw;
            }
            if(i < l.custom.length() - 1) dx += l.spacing * l.scale;
        }
    }

    /** sonka: оставляет только A-Z/0-9/пробел (для этих есть спрайты logofont-*), капс, обрезает пробелы по краям. */
    private String sanitizeLogoText(String text){
        StringBuilder out = new StringBuilder();
        for(char c : text.toUpperCase().toCharArray()){
            if((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == ' '){
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    private void buildMobile(){
        container.clear();
        container.name = "buttons";
        container.setSize(Core.graphics.getWidth(), Core.graphics.getHeight());

        float size = 120f;
        container.defaults().size(size).pad(5).padTop(4f);

        MobileButton
            play = new MobileButton(Icon.play, "@campaign", () -> checkPlay(ui.planet::show)),
            custom = new MobileButton(Icon.rightOpenOut, "@customgame", () -> checkPlay(ui.custom::show)),
            maps = new MobileButton(Icon.download, "@loadgame", () -> checkPlay(ui.load::show)),
            join = new MobileButton(Icon.add, "@joingame", () -> checkPlay(ui.join::show)),
            editor = new MobileButton(Icon.terrain, "@editor", () -> checkPlay(ui.maps::show)),
            tools = new MobileButton(Icon.settings, "@settings", ui.settings::show),
            mods = new MobileButton(Icon.book, "@mods", ui.mods::show),
            exit = new MobileButton(Icon.exit, "@quit", () -> Core.app.exit()),
            about = new MobileButton(Icon.info, "@about.button", ui.about::show);

        Seq<MobileButton> customs = customButtons.map(b -> new MobileButton(b.icon, b.text, b.runnable == null ? () -> {} : b.runnable));

        if(!Core.graphics.isPortrait()){
            container.marginTop(60f);
            container.add(play);
            container.add(join);
            container.add(custom);
            container.add(maps);
            // add odd custom buttons
            for(int i = 1; i < customs.size; i += 2){
                container.add(customs.get(i));
            }
            container.row();

            container.add(editor);
            container.add(tools);
            container.add(mods);
            // add even custom buttons (before the exit button)
            for(int i = 0; i < customs.size; i += 2){
                container.add(customs.get(i));
            }
            container.add(ios ? about : exit);
        }else{
            container.marginTop(0f);
            container.add(play);
            container.add(maps);
            container.row();
            container.add(custom);
            container.add(join);
            container.row();
            container.add(editor);
            container.add(tools);
            container.row();
            container.add(mods);
            // add custom buttons
            for(int i = 0; i < customs.size; i++){
                container.add(customs.get(i));
                if(i % 2 == 0) container.row();
            }
            container.add(ios ? about : exit);
        }
    }

    private void buildDesktop(){
        container.clear();
        container.setSize(Core.graphics.getWidth(), Core.graphics.getHeight());

        float width = 230f;
        Drawable background = Styles.black6;

        container.left();
        container.add().width(Core.graphics.getWidth()/10f);
        container.table(background, t -> {
            t.defaults().width(width).height(70f);
            t.name = "buttons";

            if(desktopButtons == null){
                desktopButtons = Seq.with(
                    new MenuButton("@play", Icon.play,
                        new MenuButton("@campaign", Icon.play, () -> checkPlay(ui.planet::show)),
                        new MenuButton("@joingame", Icon.add, () -> checkPlay(ui.join::show)),
                        new MenuButton("@client.claj.join", Icon.link, () -> checkPlay(ui.clajJoin::show)), // FINISHME: Bundle and stop creating new dialog every time
                        new MenuButton("@customgame", Icon.terrain, () -> checkPlay(ui.custom::show)),
                        new MenuButton("@loadgame", Icon.download, () -> checkPlay(ui.load::show))
                    ),
                    new MenuButton("@client.name", Icon.wrench,
                        new MenuButton("Github", Icon.github, () -> { // Link to client github
                            if (!Core.app.openURI("https://github.com/Promiha27/mindustry-client")) {
                                ui.showErrorMessage("@linkfail");
                                Core.app.setClipboardText("https://github.com/Promiha27/mindustry-client");
                            }
                        }),
                        new MenuButton("@client.changelog", Icon.edit, ChangelogDialog.INSTANCE::show),
                        new MenuButton("@client.features", Icon.list, FeaturesDialog.INSTANCE::show),
                        new MenuButton("@client.sonka.hotkeys.title", Icon.book, () -> sonkaextras.hotkeys.HotkeysDialog.open()), // sonka: единый список хоткеев всех пакетов
                        new MenuButton("@client.sonka.report.title", Icon.fileText, () -> new sonkaextras.DiagReport.ReportDialog().show()), // sonka: отчёт для багрепорта
                        new MenuButton("@client.certs.manage.title", Icon.lock, () -> new TLSKeyDialog().show())
                    ), // End of client section
                    new MenuButton("@database.button", Icon.menu,
                        new MenuButton("@schematics", Icon.paste, ui.schematics::show),
                        new MenuButton("@client.schematic.browser", Icon.host, SchematicBrowserDialog::showBrowser),
                        new MenuButton("@database", Icon.book, ui.database::show),
                        new MenuButton("@about.button", Icon.info, ui.about::show)
                    ),
                    new MenuButton("@editor", Icon.terrain, () -> checkPlay(ui.maps::show)), steam ? new MenuButton("@workshop", Icon.steam, platform::openWorkshop) : null,
                    new MenuButton("@mods", Icon.book, ui.mods::show),
                    new MenuButton("@settings", Icon.settings, ui.settings::show)
                );
            }

            buttons(t, desktopButtons.toArray(MenuButton.class));
            buttons(t, customButtons.toArray(MenuButton.class));
            buttons(t, new MenuButton("@quit", Icon.exit, Core.app::exit));
        }).width(width).growY();

        container.table(background, t -> {
            submenu = t;
            t.name = "submenu";
            t.color.a = 0f;
            t.top();
            t.defaults().width(width).height(70f);
            t.visible(() -> !t.getChildren().isEmpty());

        }).width(width).growY();
    }

    private void checkPlay(Runnable run){
        if(!mods.hasContentErrors()){
            run.run();
        }else{
            ui.showInfo("@mod.noerrorplay");
        }
    }

    /** "@be.noupdates" одинаково значило и "ты и так актуален", и "запрос к GitHub упал" - показываем реальную причину, если она есть. */
    private void showNoUpdates(){
        String err = becontrol.lastError();
        if(err != null){
            ui.showErrorMessage(Core.bundle.get("be.noupdates") + "\n\n" + err);
        }else{
            ui.showInfo("@be.noupdates");
        }
    }

    private void fadeInMenu(){
        submenu.clearActions();
        submenu.actions(Actions.alpha(1f, 0.15f, Interp.fade));
    }

    private void fadeOutMenu(){
        //nothing to fade out
        if(submenu.getChildren().isEmpty()){
            return;
        }

        submenu.clearActions();
        submenu.actions(Actions.alpha(1f), Actions.alpha(0f, 0.2f, Interp.fade), Actions.run(() -> submenu.clearChildren()));
    }

    private void buttons(Table t, MenuButton... buttons){
        for(MenuButton b : buttons){
            if(b == null) continue;
            Button[] out = {null};
            out[0] = t.button(b.text, b.icon, Styles.flatToggleMenut, () -> {
                if(currentMenu == out[0]){
                    currentMenu = null;
                    fadeOutMenu();
                }else{
                    if(b.submenu != null && b.submenu.any()){
                        currentMenu = out[0];
                        submenu.clearChildren();
                        fadeInMenu();
                        //correctly offset the button
                        submenu.add().height((Core.graphics.getHeight() - Core.scene.marginTop - Core.scene.marginBottom - out[0].getY(Align.topLeft)) / Scl.scl(1f));
                        submenu.row();
                        buttons(submenu, b.submenu.toArray());
                    }else{
                        currentMenu = null;
                        fadeOutMenu();
                        b.runnable.run();
                    }
                }
            }).marginLeft(11f).get();
            out[0].update(() -> out[0].setChecked(currentMenu == out[0]));
            t.row();
        }
    }

    /** Adds a custom button to the menu. */
    public void addButton(String text, Drawable icon, Runnable callback){
        addButton(new MenuButton(text, icon, callback));
    }

    /** Adds a custom button to the menu. */
    public void addButton(String text, Runnable callback){
        addButton(text, Styles.none, callback);
    }

    /**
     * Adds a custom button to the menu.
     * If {@link MenuButton#submenu} is null or the player is on mobile, {@link MenuButton#runnable} is invoked on click.
     * Otherwise, {@link MenuButton#submenu} is shown.
     */
    public void addButton(MenuButton button){
        customButtons.add(button);
    }

    /** Represents a menu button definition. */
    public static class MenuButton{
        public final Drawable icon;
        public final String text;
        /** Runnable ran when the button is clicked. Ignored on desktop if {@link #submenu} is not null. */
        public final Runnable runnable;
        /** Submenu shown when this button is clicked. Used instead of {@link #runnable} on desktop. */
        public final @Nullable Seq<MenuButton> submenu;

        /** Constructs a simple menu button, which behaves the same way on desktop and mobile. */
        public MenuButton(String text, Drawable icon, Runnable runnable){
            this.icon = icon;
            this.text = text;
            this.runnable = runnable;
            this.submenu = null;
        }

        /** Constructs a button that runs the runnable when clicked on mobile or shows the submenu on desktop. */
        public MenuButton(String text, Drawable icon, Runnable runnable, MenuButton... submenu){
            this.icon = icon;
            this.text = text;
            this.runnable = runnable;
            this.submenu = submenu != null ? Seq.with(submenu) : null;
        }

        /** Comstructs a desktop-only button; used internally. */
        MenuButton(String text, Drawable icon, MenuButton... submenu){
            this.icon = icon;
            this.text = text;
            this.runnable = () -> {};
            this.submenu = submenu != null ? Seq.with(submenu) : null;
        }
    }
}
