package scheme.ui.dialogs;

import arc.scene.ui.Dialog;
import arc.scene.ui.Image;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectIntMap;
import arc.util.Scaling;
import mindustry.content.StatusEffects;
import mindustry.gen.Icon;
import mindustry.type.UnitType;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;

import static arc.Core.*;
import static mindustry.Vars.*;

/**
 * Сводка приближающейся волны: суммарные хп/щиты, состав, боссы; кнопки копирования
 * в чат-формат. Открывается с HUD-плашки "волна на подходе". Расчёт волны инлайнен
 * сюда из UnitsCache мода (сам кэш не портирован - его остальные потребители не нужны).
 */
public class WaveApproachingDialog extends BaseDialog{

    public Label health;
    public Label shield;

    public Table enemies;
    public Table bosses;

    public float waveHealth, waveShield;
    public ObjectIntMap<UnitType> waveUnits = new ObjectIntMap<>();
    public ObjectIntMap<UnitType> waveBosses = new ObjectIntMap<>();

    public WaveApproachingDialog(){
        super("@scheme.approaching.name");
        addCloseButton();

        setFillParent(false); // no sense in full screen dialog
        cont.add().width(350f).row(); // set min width

        cont.add("").with(l -> health = l).left().row();
        cont.add("").with(l -> shield = l).left().row();

        cont.add("@scheme.approaching.enemies").left();
        cont.button(Icon.copySmall, Styles.clearNonei, () -> copyUnits(waveUnits)).row();
        cont.table(t -> enemies = t).padLeft(16f).left().row();

        cont.add("@scheme.approaching.bosses").left();
        cont.button(Icon.copySmall, Styles.clearNonei, () -> copyUnits(waveBosses)).row();
        cont.table(t -> bosses = t).padLeft(16f).left().row();
    }

    @Override
    public Dialog show(){
        refreshWaveInfo();
        title.setText(bundle.format("scheme.approaching.name", String.valueOf(state.wave)));

        health.setText(bundle.format("scheme.approaching.health", waveHealth));
        shield.setText(bundle.format("scheme.approaching.shield", waveShield));

        addUnits(enemies, waveUnits);
        addUnits(bosses, waveBosses);

        return super.show();
    }

    public void refreshWaveInfo(){
        waveHealth = waveShield = 0;
        waveUnits.clear();
        waveBosses.clear();

        state.rules.spawns.each(group -> group.type != null, group -> {
            int amount = group.getSpawned(state.wave - 1);
            if(amount == 0) return;

            waveHealth += group.type.health * amount;
            waveShield += group.getShield(state.wave - 1);
            waveUnits.put(group.type, amount);
            if(group.hasEffect(StatusEffects.boss)) waveBosses.put(group.type, amount);
        });
    }

    private void addUnits(Table table, ObjectIntMap<UnitType> units){
        table.clear();

        if(units.isEmpty()) table.add("@none");
        else for(var entry : units){
            table.stack(
                new Image(entry.key.uiIcon).setScaling(Scaling.fit),
                new Table(pad -> pad.bottom().left().add(String.valueOf(entry.value)))
            ).size(32f).padRight(8f);
        }
    }

    private void copyUnits(ObjectIntMap<UnitType> units){
        StringBuilder builder = new StringBuilder();

        if(units.isEmpty()) builder.append(bundle.get("none"));
        else for(var entry : units)
            builder.append(entry.value).append(entry.key.emoji()).append(" ");

        app.setClipboardText(builder.toString());
        ui.showInfoFade("@copied");
        hide();
    }
}
