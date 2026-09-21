package eui.core;

import mindustry.graphics.Pal;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;

/**
 * A non-interactive section-header row for the shared "Extended UI++" settings category. Registering it
 * through {@link SettingsTable#pref} - same as {@link ButtonSetting} - keeps it tracked in the table's
 * own {@code Setting} list; a raw {@code table.add(...)} row isn't, and the settings screen disables its
 * search bar for the whole category the moment it spots the mismatch ("Mod added an unexpected row to
 * SettingsTable"). Same idiom as {@code qol.core.LabelSetting}, kept as its own copy here rather than a
 * shared dependency so the two mods' settings categories stay independently self-contained.
 */
public class LabelSetting extends SettingsTable.Setting{
    final String text;

    public LabelSetting(String name, String text){
        super(name);
        this.text = text;
        //title - то, что видит поиск по настройкам: без этого там торчало бы техническое имя ключа
        this.title = text;
    }

    /** Подзаголовок, а не настройка: глобальный поиск использует его текст как «крошку» для строк ниже. */
    @Override
    public boolean isHeader(){
        return true;
    }

    @Override
    public void add(SettingsTable table){
        table.row();
        table.add(text).color(Pal.accent).padTop(8f).left().row();
    }
}
