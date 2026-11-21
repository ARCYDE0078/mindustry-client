package eui.icons;

import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.content.StatusEffects;
import mindustry.content.UnitTypes;
import mindustry.ctype.UnlockableContent;
import mindustry.game.Team;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.mod.Mods.LoadedMod;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static mindustry.Vars.content;
import static mindustry.Vars.mods;

/**
 * "Give me a {@link Drawable} for this arbitrary icon name" lookup used by the icon-category picker
 * ({@link IconCategoriesConfig}) and the schematics-table cell/category icons - tries, in order: a
 * built-in UI icon ({@link Icon#icons}), a content sprite (item/liquid/unit/status/block, by internal
 * name), a team color swatch/sprite, an icon contributed by an enabled mod, a couple of special-cased
 * hidden "missile" units, a UI texture ({@code Tex}, by reflection) and finally a direct atlas region lookup (with and without a "unit-...-ui"
 * prefix) - falling back to the pencil icon if nothing matched. Ported from utils/icons.js.
 */
public class Icons{
    private static final ObjectMap<String, Drawable> allSprites = new ObjectMap<>();
    private static final ObjectMap<String, Drawable> teamIcons = new ObjectMap<>();
    private static final ObjectMap<String, Drawable> modIcons = new ObjectMap<>();
    private static final ObjectMap<String, Drawable> texIcons = new ObjectMap<>();

    private static boolean spritesCached = false;
    private static boolean texCached = false;
    private static boolean teamIconsCached = false;
    private static boolean modIconsCached = false;

    private static final String[] missileUnits = {
        "scathe-missile", "scathe-missile-phase", "scathe-missile-surge", "scathe-missile-surge-split",
        "quell-missile", "disrupt-missile", "anthicus-missile"
    };

    /** Forces every lookup cache to populate, so {@link #allIconNames()}/{@link #modIconNames()} see the complete set - used by {@link IconCategoriesConfig}'s "everything else" bucket. */
    public static void ensureAllCached(){
        if(!spritesCached) setupSprites();
        if(!teamIconsCached) setupTeamIcons();
        if(!modIconsCached) setupModIcons();
        if(!texCached) setupTex();
    }

    /** Every icon name resolvable via {@link #getIconDrawable} through the UI/sprite/team/mod tiers (not the atlas-fallback tiers, which aren't enumerable by name). */
    public static Seq<String> allIconNames(){
        ensureAllCached();
        Seq<String> names = new Seq<>();
        names.addAll(Icon.icons.keys().toSeq());
        names.addAll(allSprites.keys().toSeq());
        names.addAll(teamIcons.keys().toSeq());
        names.addAll(modIcons.keys().toSeq());
        names.addAll(texIcons.keys().toSeq());
        return names;
    }

    /** Имена всех UI-текстур ({@code mindustry.gen.Tex}: панели, кнопки, чекбоксы, логотип...) - для категории "UI Textures" пикера. */
    public static Seq<String> texIconNames(){
        if(!texCached) setupTex();
        Seq<String> names = texIcons.keys().toSeq();
        names.sort();
        return names;
    }

    public static Seq<String> modIconNames(){
        if(!modIconsCached) setupModIcons();
        return modIcons.keys().toSeq();
    }

    /**
     * true - иконка из "значков" ({@code Icon.icons}: Icon.star, Icon.pencil и т.п.) - плотно
     * обрезанные глифы, занимающие почти весь квадрат региона. false - контентная иконка (блок/
     * юнит/предмет/жидкость/эффект через {@code uiIcon}) - в спрайте обычно заложен заметный
     * отступ вокруг самой картинки (особенно у блоков), из-за чего при одинаковом коэффициенте
     * растяжения контентные иконки визуально мельче значков (sonka: "иконки блоков слишком
     * мелкие, а значки нормального размера"). Используется вызывающим кодом, чтобы компенсировать
     * коэффициентом побольше именно контентные иконки, не трогая уже нормальные значки.
     */
    public static boolean isGlyphIcon(String iconName){
        return iconName != null && Icon.icons.containsKey(iconName);
    }

    /**
     * sonka 2026-08-22: "иконки, которые НЕ БЛОКИ, всё ещё большие" - уточнение {@link #isGlyphIcon}:
     * заметный отступ вокруг картинки в спрайте есть ТОЛЬКО у блоков (block.uiIcon). Предметы,
     * жидкости, юниты, статус-эффекты обрезаны так же плотно, как и значки - и с блочным бустом
     * они выходят визуально крупнее соседей. Поэтому компенсирующий буст должен получать именно
     * блок, а не "любой не-значок". Порядок проверки повторяет {@link #getIconDrawable}: имя из
     * {@code Icon.icons} побеждает всегда, затем блок по имени (блоки регистрируются в
     * {@link #setupSprites} ПОСЛЕДНИМИ и перекрывают одноимённые предметы вроде "sand" - так что
     * если {@code content.block(name)} есть, на экране именно блочный спрайт).
     */
    public static boolean isBlockIcon(String iconName){
        if(iconName == null || iconName.isEmpty()) return false;
        if(Icon.icons.containsKey(iconName)) return false;
        try{
            return content.block(iconName) != null;
        }catch(Throwable t){
            return false;
        }
    }

    public static Drawable getIconDrawable(String iconName){
        if(iconName == null || iconName.isEmpty()) return pencil();

        Drawable found = lookup(iconName);
        return found != null ? found : pencil();
    }

    /** true - у имени есть настоящая иконка (а не заглушка-карандаш): пикер не показывает имена, которые никуда не ведут. */
    public static boolean resolves(String iconName){
        return iconName != null && !iconName.isEmpty() && lookup(iconName) != null;
    }

    /** Иконка по имени или null, если ни один ярус не знает такого имени. */
    static Drawable lookup(String iconName){
        try{
            if(Icon.icons.containsKey(iconName)) return Icon.icons.get(iconName);

            if(!spritesCached) setupSprites();
            if(allSprites.containsKey(iconName)) return allSprites.get(iconName);

            if(iconName.startsWith("team-")) return teamIconDrawable(iconName.substring(5));
            if(isTeamName(iconName)) return teamIconDrawable(iconName);

            if(!modIconsCached) setupModIcons();
            if(modIcons.containsKey(iconName)) return modIcons.get(iconName);

            //sonka 2026-11-21 ("в таблице схем для ячейки не все иконки есть"): ярус Tex[имя] из оригинала не был
            //портирован, и ВСЯ категория "UI Textures" (49 имён) рисовалась одним и тем же карандашом - как и любое
            //имя, которого нет среди Icon.*. Tex - обычный сгенерированный класс со статическими Drawable-полями,
            //так что достаточно рефлексии по его полям (один раз, кэш)
            if(!texCached) setupTex();
            if(texIcons.containsKey(iconName)) return texIcons.get(iconName);

            if(isMissileUnit(iconName)){
                Drawable missile = atlasDrawable("unit-" + iconName + "-ui");
                if(missile != null) return missile;
            }

            Drawable direct = atlasDrawable(iconName);
            if(direct != null) return direct;

            return atlasDrawable("unit-" + iconName + "-ui");
        }catch(Exception e){
            return null;
        }
    }

    static Drawable pencil(){
        return Icon.pencil;
    }

    static Drawable atlasDrawable(String regionName){
        var region = arc.Core.atlas.find(regionName);
        return region != null && region.found() ? new TextureRegionDrawable(region) : null;
    }

    static boolean isMissileUnit(String name){
        for(String m : missileUnits) if(m.equals(name)) return true;
        return false;
    }

    static boolean isTeamName(String name){
        return switch(name){
            case "sharded", "crux", "malis", "derelict", "green", "blue", "neoplastic" -> true;
            default -> false;
        };
    }

    static void setupSprites(){
        registerAll(content.items());
        registerAll(content.liquids());
        registerAll(content.units());
        registerAll(content.statusEffects());
        registerAll(content.blocks());
        //sonka 2026-11-04 ("в выборе иконки схемы нету всех иконок"): планеты, погода и секторные пресеты лежат в
        //отдельных реестрах и в пикер не попадали. Кладём ПОСЛЕ блоков и только если имя свободно - чтобы они
        //никогда не перекрывали одноимённые предметы/блоки (на экране остаётся то, что и было раньше)
        registerAllIfAbsent(content.planets());
        registerAllIfAbsent(content.weathers());
        registerAllIfAbsent(content.sectors());
        spritesCached = true;
    }

    static <T extends UnlockableContent> void registerAllIfAbsent(Seq<T> items){
        for(T item : items){
            if(item == null || allSprites.containsKey(item.name)) continue;
            TextureRegion region = iconRegion(item);
            if(region != null) allSprites.put(item.name, new TextureRegionDrawable(region));
        }
    }

    static <T extends UnlockableContent> void registerAll(Seq<T> items){
        for(T item : items){
            if(item == null) continue;
            TextureRegion region = iconRegion(item);
            if(region != null) allSprites.put(item.name, new TextureRegionDrawable(region));
        }
    }

    /** uiIcon контента, а если у него нет своей UI-иконки (контент мода без -ui спрайта) - полноразмерная; иначе null (иконки нет вовсе). */
    static TextureRegion iconRegion(UnlockableContent item){
        if(item.uiIcon != null && item.uiIcon.found()) return item.uiIcon;
        if(item.fullIcon != null && item.fullIcon.found()) return item.fullIcon;
        return null;
    }

    static void setupTex(){
        for(Field f : Tex.class.getFields()){
            if(!Modifier.isStatic(f.getModifiers()) || !Drawable.class.isAssignableFrom(f.getType())) continue;
            try{
                if(f.get(null) instanceof Drawable d) texIcons.put(f.getName(), d);
            }catch(Throwable ignored){
                //недоступное поле - просто пропускаем
            }
        }
        //Tex ещё не загружен (поля null) - не кэшируем пустоту, переспросим при следующем обращении
        texCached = !texIcons.isEmpty();
    }

    static void setupTeamIcons(){
        registerTeamColor("derelict", Color.valueOf("4d4e58"));
        registerTeamColor("sharded", Pal.accent);
        registerTeamColor("crux", Color.valueOf("f25555"));
        registerTeamColor("malis", Color.valueOf("a27ce5"));
        registerTeamColor("green", Color.valueOf("54d67d"));
        registerTeamColor("blue", Color.valueOf("6c87fd"));

        Drawable neoplastic = atlasDrawable("team-neoplastic");
        if(neoplastic != null) teamIcons.put("neoplastic", neoplastic);

        teamIconsCached = true;
    }

    /**
     * The source called a {@code drawable.setTint(color)} mutator here that doesn't actually exist on
     * {@link TextureRegionDrawable} in this engine (checked via javap against the real dependency jar,
     * vanilla Arc doesn't have it either) - a latent bug, this fallback swatch would have thrown instead
     * of ever rendering. The real (non-mutating) API is {@link TextureRegionDrawable#tint(Color)}, which
     * returns a new correctly-tinted copy - used here instead to get the obviously-intended "solid color
     * team swatch" result.
     */
    static Drawable registerTeamColor(String teamName, Color fallbackColor){
        Drawable drawable = atlasDrawable("team-" + teamName);
        if(drawable == null){
            TextureRegionDrawable whiteui = (TextureRegionDrawable)atlasDrawable("whiteui");
            drawable = whiteui.tint(fallbackColor);
        }
        teamIcons.put(teamName, drawable);
        return drawable;
    }

    static Drawable teamIconDrawable(String teamName){
        if(!teamIconsCached) setupTeamIcons();
        if(teamIcons.containsKey(teamName)) return teamIcons.get(teamName);

        Team team = teamByName(teamName);
        if(team == null) return pencil();

        return registerTeamColor(teamName, team.color);
    }

    static Team teamByName(String name){
        for(Team t : Team.all){
            if(t != null && t.name.equals(name)) return t;
        }
        return null;
    }

    static void setupModIcons(){
        for(LoadedMod mod : mods.list()){
            if(!mod.enabled() || mod.meta == null) continue;

            registerModContent(content.blocks(), mod, "block-");
            registerModContent(content.items(), mod, "item-");
            registerModContent(content.units(), mod, "unit-");
        }
        modIconsCached = true;
    }

    static <T extends UnlockableContent> void registerModContent(Seq<T> items, LoadedMod mod, String prefix){
        for(T item : items){
            if(item.minfo.mod != mod || item.uiIcon == null || !item.uiIcon.found()) continue;

            String bareName = item.name.startsWith(prefix) ? item.name.substring(prefix.length()) : item.name;
            Drawable drawable = new TextureRegionDrawable(item.uiIcon);
            modIcons.put(bareName, drawable);
            modIcons.put(prefix + bareName, drawable);
        }
    }
}
