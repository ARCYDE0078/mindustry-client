package sonkaextras.hotkeys;

import arc.*;
import arc.func.*;
import arc.input.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.client.ComponentBoot;
import mindustry.input.*;
import sonkaextras.*;

/**
 * Источник строк для {@link HotkeysDialog}: весь реестр {@link KeyBind#all} (ваниль + клиент +
 * вшитые моды, в порядке регистрации, категории - как в «Управлении») плюс ручной каталог комбо,
 * которых в реестре нет. Ручные записи держатся ЗДЕСЬ, а не размазаны по 17 пакетам: они
 * описательные (текст клавиши + что делает), пакеты для них трогать не нужно, а бинды других
 * пакетов ищутся по имени через {@code KeyBind.all} - импорт их Binding-классов запускает
 * регистрацию как побочный эффект и ломает самоотключение (см. комментарий в FeaturesDialog).
 * <p>
 * Бандл: ручная запись {@code id} → {@code client.sonka.hotkeys.<id>.name} / {@code .desc};
 * категория KeyBind → {@code category.<cat>.name} (как в KeybindDialog); категории ручных записей
 * - те же строки, что и у секций вкладки «Моды» ({@code client.setting.modsec-<pkg>.category}),
 * чтобы группы не двоились.
 */
public class HotkeyCatalog{
    private HotkeyCatalog(){
    }

    public static String bindName(KeyBind bind){
        return Core.bundle.get("keybind." + bind.name + ".name", Strings.capitalize(bind.name));
    }

    static String categoryName(@Nullable String category){
        if(category == null) return Core.bundle.get("category.general.name", "General");
        return Core.bundle.get("category." + category + ".name", Strings.capitalize(category));
    }

    static String modsec(String pkg){
        return Core.bundle.get("client.setting.modsec-" + pkg + ".category", pkg);
    }

    static @Nullable KeyBind find(String name){
        return KeyBind.all.find(b -> b.name.equals(name));
    }

    /** Текст клавиши бинда по имени; «?» если такого бинда нет (пакет самоотключился). */
    static String key(String bindName){
        KeyBind b = find(bindName);
        if(b == null) return "?";
        String s = Hotkey.keyString(b);
        return s.isEmpty() ? Core.bundle.get("client.sonka.hotkeys.unset") : s;
    }

    static String key(KeyBind bind){
        String s = Hotkey.keyString(bind);
        return s.isEmpty() ? Core.bundle.get("client.sonka.hotkeys.unset") : s;
    }

    /** Полный список, строится заново при каждом открытии диалога (бинды могли переназначить). */
    public static Seq<Hotkey> all(){
        Seq<Hotkey> out = new Seq<>();

        //1. реестр KeyBind: категория null = продолжение предыдущей (так же трактует KeybindDialog)
        String last = null;
        for(KeyBind bind : KeyBind.all){
            if(bind.category != null) last = bind.category;
            String desc = Core.bundle.getOrNull("keybind." + bind.name + ".desc");
            out.add(new Hotkey(categoryName(last), bind, desc));
        }

        //2. ручной каталог
        String clientCat = categoryName("client");
        manual(out, clientCat, "unit_letters", () -> key(Binding.selectUnitTypeModifier) + " + A–Z, ;", null, null);
        manual(out, clientCat, "control_groups", () -> key(Binding.createControlGroup) + " + 1–0  /  1–0", null, null);
        manual(out, clientCat, "best_drill", () -> key(Binding.pick), null, null);
        manual(out, clientCat, "line_rotate", () -> Core.bundle.get("client.sonka.hotkeys.line_rotate.key"), null, () -> new LineRotate.PickerDialog().show());
        manual(out, clientCat, "menu_regen", () -> "H", null, null);
        manual(out, clientCat, "join_refresh", () -> "F5", "F5", null);
        manual(out, clientCat, "glyph_copy", () -> "Shift + " + Core.bundle.get("client.sonka.hotkeys.click"), null, null);
        manual(out, clientCat, "editor_tools", () -> "1–9, 0, R, E, ↑/↓, Esc", null, null);
        manual(out, clientCat, "editor_alt_tools", () -> "Shift / Alt (" + Core.bundle.get("client.sonka.hotkeys.hold") + ")", null, null);
        manual(out, clientCat, "maptags_delete", () -> "Shift + " + Core.bundle.get("client.sonka.hotkeys.click"), null, null);

        //ручные строки вшитых пакетов - только у ВКЛЮЧЁННЫХ в ComponentBoot: иначе диалог показывал бы хоткеи
        //компонентов, которых в игре нет (tmi_calc_save, he_slots и т.п. ничего не делают)
        if(ComponentBoot.enabled("eui")){
            String euiCat = categoryName("extended-ui");
            manual(out, euiCat, "schem_chord", () -> key("schem_table_leader") + ", 0–9, 0–9", null, null);
            manual(out, euiCat, "schem_cell_rmb", () -> Core.bundle.get("client.sonka.hotkeys.rmb"), null, null);
        }

        if(ComponentBoot.enabled("helium")){
            String heliumCat = modsec("helium");
            manual(out, heliumCat, "he_slots", () -> "1–8", null, null);
        }

        if(ComponentBoot.enabled("tmi")){
            String tmiCat = modsec("tmi");
            manual(out, tmiCat, "tmi_calc_save", () -> "Ctrl + S  /  Alt + S  /  Ctrl + Shift + S", null, null);
            manual(out, tmiCat, "tmi_calc_refresh", () -> "F5", "F5", null);
        }

        if(ComponentBoot.enabled("qol")){
            String qolCat = modsec("qol");
            manual(out, qolCat, "autobuild_schem", () -> "Shift + " + key(Binding.select) + " (" + Core.bundle.get("client.sonka.hotkeys.drag") + ")", null, null);
            manual(out, qolCat, "forcebuild_schem", () -> "Ctrl + " + key(Binding.select) + " (" + Core.bundle.get("client.sonka.hotkeys.drag") + ")", null, null);
            manual(out, qolCat, "copy_anywhere", () -> key(Binding.schematicSelect), null, null);
        }

        if(ComponentBoot.enabled("patcheditor")){
            String peCat = Core.bundle.get("client.features.mod.patcheditor.name", "PatchEditor");
            manual(out, peCat, "pe_undo", () -> "Ctrl + Z  /  Ctrl + Shift + Z  /  Ctrl + Y", null, null);
            manual(out, peCat, "pe_nav", () -> "↑ / ↓  /  Mouse 4 / Mouse 5", null, null);
            manual(out, peCat, "pe_node", () -> key(Binding.pick), null, null);
        }

        if(ComponentBoot.enabled("extraeditor")){
            String eeCat = modsec("extraeditor");
            manual(out, eeCat, "ee_esc", () -> "Esc", null, null);
        }

        //3. пользовательские чат-бинды QoL Control: формат ключа "ctrl+alt+k" (qolc.keybinds.ChatKeyBindsFeature)
        if(ComponentBoot.enabled("qolc")){
            String qolcCat = modsec("qolc");
            Runnable cfg = qolc.keybinds.ChatKeyBindsFeature::showDialog;
            int n = 0;
            try{
                //формат ChatKeyBindsFeature с v7.1 - МАССИВ {key, cmd, enabled}; старый (объект {"ctrl+K": "cmd"})
                //читаем тоже, как это делает сама фича. Раньше тут был только объект - на массиве asObject() падал,
                //и диалог всегда показывал пустую заглушку.
                Jval root = Jval.read(Core.settings.getString("qol-binds", "[]"));
                Seq<String[]> rows = new Seq<>();
                if(root.isArray()){
                    for(Jval item : root.asArray()){
                        if(!item.getBool("enabled", true)) continue; //выключенный бинд не срабатывает - в списке хоткеев ему не место
                        rows.add(new String[]{item.getString("key", ""), item.getString("cmd", "")});
                    }
                }else if(root.isObject()){
                    for(var entry : root.asObject()) rows.add(new String[]{entry.key, entry.value.asString()});
                }
                for(String[] row : rows){
                    String raw = row[0];
                    if(raw.isEmpty()) continue;
                    String keyText = chatBindKey(raw);
                    String sig = chatBindSignature(raw);
                    String name = row[1].replace('\n', ' ');
                    if(name.length() > 48) name = name.substring(0, 48) + "…";
                    out.add(new Hotkey(qolcCat, name, Core.bundle.get("client.sonka.hotkeys.qolc_bind.desc"), () -> keyText, sig, cfg));
                    n++;
                }
            }catch(Throwable t){
                Log.err("[hotkeys] failed to parse qol-binds", t);
            }
            if(n == 0){
                manual(out, qolcCat, "qolc_none", () -> "", null, cfg);
            }
        }
        return out;
    }

    private static void manual(Seq<Hotkey> out, String category, String id, Prov<String> key, @Nullable String signature, @Nullable Runnable configure){
        out.add(new Hotkey(category,
            Core.bundle.get("client.sonka.hotkeys." + id + ".name"),
            Core.bundle.getOrNull("client.sonka.hotkeys." + id + ".desc"),
            key, signature, configure));
    }

    /** "ctrl+alt+k" → "Ctrl + Alt + K" (имя клавиши - как у KeyCode, чтобы совпадать с «Управлением»). */
    static String chatBindKey(String raw){
        String[] parts = raw.split("\\+");
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < parts.length - 1; i++){
            sb.append(Strings.capitalize(parts[i])).append(" + ");
        }
        String keyName = parts[parts.length - 1];
        try{
            sb.append(KeyCode.valueOf(keyName).getName());
        }catch(IllegalArgumentException e){
            sb.append(keyName);
        }
        return sb.toString();
    }

    static @Nullable String chatBindSignature(String raw){
        String[] parts = raw.split("\\+");
        KeyCode key;
        try{
            key = KeyCode.valueOf(parts[parts.length - 1]);
        }catch(IllegalArgumentException e){
            return null;
        }
        Seq<KeyCode> mods = new Seq<>();
        for(int i = 0; i < parts.length - 1; i++){
            switch(parts[i]){
                case "ctrl" -> mods.add(KeyCode.controlLeft);
                case "alt" -> mods.add(KeyCode.altLeft);
                case "shift" -> mods.add(KeyCode.shiftLeft);
            }
        }
        return Hotkey.signature(mods.toArray(KeyCode.class), key);
    }
}
