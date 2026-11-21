package eui.icons;

import arc.struct.Seq;

/**
 * Static taxonomy of icon names for the icon-picker dialogs (schematic/category cell images) - grouped
 * into collapsible sections matching what a player would recognize from the vanilla build-menu/content
 * browser, rather than one giant flat list. Names are internal content/UI-icon names (no type prefix,
 * e.g. "copper" not "item-copper") looked up through {@link Icons#getIconDrawable}. Ported from
 * ui/other/icon-categories-config.js - the icon lists themselves are just data, copied over verbatim.
 */
public class IconCategoriesConfig{
    public static final boolean USE_ICON_CATEGORIES = true;
    public static final int ICONS_PER_ROW = 16;

    public static class Category{
        public final String name;
        public final String icon;
        public final String[] icons;

        Category(String name, String icon, String... icons){
            this.name = name;
            this.icon = icon;
            this.icons = icons;
        }
    }

    public static final Seq<Category> CATEGORIES = Seq.with(
        new Category("Interface", "settings",
            "menu", "settings", "edit", "save", "load", "cancel", "ok", "add", "remove",
            "copy", "paste", "trash", "info", "warning", "error", "search", "filter",
            "left", "right", "up", "down", "home", "back", "forward",
            "leftOpen", "rightOpen", "upOpen", "downOpen",
            "refresh", "refresh1", "undo", "redo", "rotate", "move", "resize",
            "play", "pause", "stop", "list", "terminal",
            "lock", "lockOpen", "eye", "eyeOff", "star", "image",
            "wrench", "hammer", "file", "fileText", "fileImage", "folder",
            "link", "upload", "download", "export", "import", "zoom"),

        new Category("UI Textures", "button",
            "alphaaaa", "alphaBg", "alphaBgLine", "pane", "pane2", "paneSolid",
            "paneTop", "paneLeft", "paneRight", "whitePane", "whiteui",
            "button", "buttonDown", "buttonOver", "buttonDisabled", "buttonRed",
            "buttonSelect", "buttonSelectTrans", "buttonTrans",
            "buttonRight", "buttonRightDown", "buttonRightOver", "buttonRightDisabled",
            "checkOn", "checkOnOver", "checkOnDisabled", "checkOff", "checkDisabled", "checkOver",
            "underline", "underline2", "underlineOver", "underlineWhite", "underlineRed", "underlineDisabled",
            "sideline", "sidelineOver",
            "bar", "barTop", "inventory", "cursor", "selection", "clear",
            "logo", "cat", "crater", "logicNode", "scroll", "slider"),

        new Category("Resources", "copper",
            "copper", "lead", "coal", "sand", "scrap", "graphite", "silicon", "metaglass",
            "titanium", "thorium", "plastanium", "phase-fabric", "surge-alloy", "spore-pod", "pyratite", "blast-compound",
            "beryllium", "tungsten", "oxide", "carbide",
            "fissile-matter", "dormant-cyst"),

        new Category("Liquids", "water",
            "water", "oil", "slag",
            "cryofluid",
            "arkycite", "hydrogen", "ozone", "nitrogen", "cyanogen", "gallium", "neoplasm"),

        new Category("Units", "dagger",
            "dagger", "mace", "fortress", "scepter", "reign",
            "crawler", "atrax", "spiroct", "arkyid", "toxopid",
            "nova", "pulsar", "quasar", "vela", "corvus",
            "flare", "horizon", "zenith", "antumbra", "eclipse",
            "mono", "poly", "mega", "quad", "oct",
            "risso", "minke", "bryde", "sei", "omura",
            "retusa", "oxynoe", "cyerce", "aegires", "navanax",
            "stell", "locus", "precept", "vanquish", "conquer",
            "merui", "cleroi", "anthicus", "tecta", "collaris",
            "elude", "avert", "obviate", "quell", "disrupt", "quell-missile", "disrupt-missile",
            "scathe-missile", "scathe-missile-phase", "scathe-missile-surge", "scathe-missile-surge-split",
            "renale", "latum",
            "alpha", "beta", "gamma",
            "evoke", "incite", "emanate",
            "ground-factory", "air-factory", "naval-factory",
            "additive-reconstructor", "multiplicative-reconstructor", "exponential-reconstructor", "tetrative-reconstructor",
            "tank-fabricator", "ship-fabricator", "mech-fabricator",
            "tank-refabricator", "ship-refabricator", "mech-refabricator", "prime-refabricator",
            "tank-assembler", "ship-assembler", "mech-assembler", "basic-assembler-module",
            "repair-point", "repair-turret", "unit-repair-tower", "payload-conveyor", "payload-router", "reinforced-payload-conveyor", "reinforced-payload-router", "payload-loader", "payload-unloader",
            "payload-mass-driver", "large-payload-mass-driver", "small-deconstructor", "deconstructor", "constructor", "large-constructor", "payload-source", "payload-void"),

        new Category("Conveyors", "conveyor",
            "conveyor", "titanium-conveyor", "plastanium-conveyor", "armored-conveyor",
            "junction", "bridge-conveyor", "phase-conveyor", "sorter", "inverted-sorter", "router", "distributor", "overflow-gate", "underflow-gate", "unloader", "mass-driver",
            "duct", "armored-duct", "duct-router", "overflow-duct", "underflow-duct", "duct-bridge", "duct-unloader",
            "surge-conveyor", "surge-router", "unit-cargo-loader", "unit-cargo-unload-point",
            "item-source", "item-void"),

        new Category("Pipes", "conduit",
            "mechanical-pump", "rotary-pump", "impulse-pump",
            "conduit", "pulse-conduit", "plated-conduit",
            "liquid-router", "liquid-tank", "liquid-container",
            "liquid-junction", "bridge-conduit", "phase-conduit",
            "reinforced-pump", "reinforced-conduit", "reinforced-liquid-junction", "reinforced-bridge-conduit", "reinforced-liquid-router", "reinforced-liquid-container", "reinforced-liquid-tank",
            "liquid-source", "liquid-void"),

        new Category("Fabric", "silicon-smelter",
            "graphite-press", "multi-press", "silicon-smelter", "silicon-crucible", "kiln",
            "plastanium-compressor", "phase-weaver", "surge-smelter", "cryofluid-mixer", "pyratite-mixer", "blast-mixer", "melter", "separator",
            "disassembler", "spore-press", "pulverizer", "coal-centrifuge", "incinerator",
            "silicon-arc-furnace", "electrolyzer", "atmospheric-concentrator", "oxidation-chamber", "electric-heater", "phase-heater", "slag-heater", "small-heat-redirector",
            "heat-redirector", "heat-router", "slag-incinerator", "carbide-crucible", "slag-centrifuge", "surge-crucible", "phase-synthesizer", "cyanogen-synthesizer", "heat-reactor", "heat-source"),

        new Category("Utility", "mend-projector",
            "mender", "mend-projector", "overdrive-projector", "overdrive-dome", "force-projector", "shock-mine", "radar", "build-tower", "regen-projector",
            "shockwave-tower", "shield-projector", "large-shield-projector", "core-shard", "core-foundation", "core-nucleus", "core-bastion", "core-citadel", "core-acropolis",
            "container", "vault", "reinforced-container", "reinforced-vault", "illuminator", "launch-pad", "advanced-launch-pad", "landing-pad", "interplanetary-accelerator"),

        new Category("Power", "battery",
            "power-node", "power-node-large", "surge-tower", "diode", "battery", "battery-large", "combustion-generator", "thermal-generator", "steam-generator",
            "differential-generator", "rtg-generator", "solar-panel", "solar-panel-large", "thorium-reactor", "impact-reactor",
            "beam-node", "beam-tower", "beam-link", "turbine-condenser", "chemical-combustion-chamber", "pyrolysis-generator", "flux-reactor", "neoplasia-reactor",
            "power-source", "power-void"),

        new Category("Defense", "duo",
            "duo", "scatter", "scorch", "hail", "wave", "lancer", "arc", "parallax", "swarmer",
            "salvo", "segment", "tsunami", "fuse", "ripple", "cyclone", "foreshadow", "spectre", "meltdown",
            "breach", "diffuse", "sublimate", "titan", "disperse", "afflict", "lustre", "scathe", "smite", "malign",
            "copper-wall", "copper-wall-large", "titanium-wall", "titanium-wall-large", "plastanium-wall", "plastanium-wall-large", "thorium-wall", "thorium-wall-large", "phase-wall",
            "phase-wall-large", "surge-wall", "surge-wall-large", "door", "door-large", "scrap-wall", "scrap-wall-large", "scrap-wall-gigantic",
            "thruster",
            "beryllium-wall", "beryllium-wall-large", "tungsten-wall", "tungsten-wall-large", "blast-door", "reinforced-surge-wall", "reinforced-surge-wall-large", "carbide-wall", "carbide-wall-large", "shielded-wall"),

        new Category("Production", "blast-drill",
            "mechanical-drill", "pneumatic-drill", "laser-drill", "blast-drill", "water-extractor", "cultivator", "oil-extractor",
            "vent-condenser", "cliff-crusher", "large-cliff-crusher", "plasma-bore", "large-plasma-bore", "impact-drill", "eruption-drill"),

        new Category("Logic", "logic-processor",
            "micro-processor", "logic-processor", "hyper-processor", "memory-cell", "memory-bank", "message", "reinforced-message", "canvas",
            "tile-logic-display", "logic-display", "large-logic-display", "world-processor", "world-cell", "world-message", "world-switch"),

        new Category("Status Effects", "burning",
            "burning", "freezing", "unmoving", "slow", "wet", "muddy", "melting", "sapped", "electrified",
            "spore-slowed", "tarred", "overdrive", "overclock", "shielded", "boss", "shocked", "blasted", "corroded",
            "disarmed", "invincible"),

        new Category("Teams", "sharded",
            "sharded", "crux", "malis", "derelict",
            "green", "blue",
            "neoplastic"),

        //remaining icons not covered by any category above - filled in dynamically by getOtherIcons()
        new Category("Other", "menu",
            "discord", "github", "redditAlien", "trello", "steam", "googleplay",
            "itchio", "android", "f-droid", "dev-builds",
            "book", "bookOpen", "list", "add", "wrench", "link"),

        //filled in dynamically by getModIconsCategory()
        new Category("Mods", "github"),

        //error/placeholder handling only
        new Category("Error", "none", "none")
    );

    public static boolean isErrorCategory(String name){
        return "Error".equals(name);
    }

    public static boolean isOtherCategory(String name){
        return "Other".equals(name);
    }

    /** Every icon listed in a real category above (excluding Other/Error, whose contents are computed, not listed). */
    public static Seq<String> getAllCategoryIcons(){
        Seq<String> all = new Seq<>();
        for(Category category : CATEGORIES){
            if(category.name.equals("Other") || category.name.equals("Error")) continue;
            all.addAll(category.icons);
        }
        return all;
    }

    /** Категория "UI Textures": сначала знакомые имена из списка выше, затем все остальные текстуры {@code Tex} (по алфавиту) - раньше они в пикере не показывались вовсе. */
    public static Seq<String> getUiTextureIcons(){
        Seq<String> names = new Seq<>();
        for(Category category : CATEGORIES){
            if(category.name.equals("UI Textures")) for(String n : category.icons) if(Icons.resolves(n) && !names.contains(n)) names.add(n);
        }
        for(String n : Icons.texIconNames()) if(!names.contains(n)) names.add(n);
        return names;
    }

    /** Every icon {@link Icons} can resolve that isn't already listed in a real category or contributed by a mod - the picker's catch-all bucket. */
    public static Seq<String> getOtherIcons(){
        Seq<String> categorized = getAllCategoryIcons();
        categorized.addAll(Icons.texIconNames()); //показаны в своей категории
        Seq<String> modIconNames = getModIconsCategory();

        Seq<String> other = new Seq<>();
        for(String name : Icons.allIconNames()){
            if(!categorized.contains(name) && !modIconNames.contains(name)) other.add(name);
        }
        other.sort();
        return other;
    }

    public static Seq<String> getModIconsCategory(){
        Seq<String> names = Icons.modIconNames();
        names.sort();
        return names;
    }
}
