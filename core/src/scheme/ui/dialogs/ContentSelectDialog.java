package scheme.ui.dialogs;

import arc.func.Cons4;
import arc.func.Func;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Label;
import arc.scene.ui.Slider;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import mindustry.content.StatusEffects;
import mindustry.content.UnitTypes;
import mindustry.ctype.UnlockableContent;
import mindustry.game.Team;
import mindustry.gen.Icon;
import mindustry.gen.Player;
import scheme.ui.TextSlider;

import static arc.Core.*;
import static mindustry.Vars.*;

/** Выбор контента (юнит/эффект/предмет) + игрок + команда + слайдер количества. */
public class ContentSelectDialog<T extends UnlockableContent> extends ListDialog{

    public static final int row = mobile ? 5 : 10;
    public static final float size = mobile ? 52f : 64f;
    /** Скрытый в логике контент, который всё равно полезно выбирать (null - если такого нет в этой версии/патче). */
    public static final Seq<UnlockableContent> specials = Seq.with(
        UnitTypes.latum, UnitTypes.renale, content.unit("quell-missile"), content.unit("disrupt-missile"), content.unit("scathe-missile"),
        StatusEffects.muddy, StatusEffects.shielded, StatusEffects.corroded, StatusEffects.disarmed, StatusEffects.invincible);

    public Cons4<Player, Team, T, Float> callback;
    public Func<Float, String> format;

    public boolean showSlider;
    public int items;

    public TextSlider amount;
    public Label label;
    public Slider slider;

    /** Единиц слайдера на единицу, вводимую в поле количества (60 - тики, вводимые секундами). */
    public float inputScale = 1f;
    /** Поле количества не опускает слайдер ниже этого значения, в единицах слайдера. */
    public float inputFloor = Float.NEGATIVE_INFINITY;

    public ContentSelectDialog(String title, Seq<T> content, int min, int max, int step, Func<Float, String> format){
        super(title);
        this.format = format;

        amount = new TextSlider(min, max, step, min, inputScale, format).fieldWidth(120f);
        label = amount.label;
        slider = amount.slider;

        Table table = new Table();
        table.pane(pane -> {
            pane.margin(0f, 24f, 0f, 24f);

            content.each(this::visible, item -> {
                pane.button(new TextureRegionDrawable(item.uiIcon), () -> {
                    callback.get(players.get(), teams.get(), item, slider.getValue());
                    hide();
                }).size(size).tooltip(item.localizedName);

                if(++items % row == 0) pane.row();
            });
        });

        addPlayer();

        cont.table(cont -> {
            cont.add(table).row();
            cont.table(slide -> {
                slide.button(Icon.add, () -> {
                    content.each(this::visible, item -> callback.get(players.get(), teams.get(), item, slider.getValue()));
                    hide();
                }).tooltip("@scheme.select.all").bottom();
                slide.add(amount).padLeft(8f).growX();
            }).fillX().padTop(16f).visible(() -> showSlider);
        }).growX();

        addTeam();
    }

    public void select(boolean showSlider, boolean showPlayers, boolean showTeams, Cons4<Player, Team, T, Float> callback){
        // in portrait orientation, ui elements may not fit into the screen
        boolean minimize = graphics.getWidth() < Scl.scl(mobile ? 900f : 1250f);

        players.pane.visible(showPlayers);
        players.rebuild(minimize);

        teams.pane.visible(showTeams);
        teams.rebuild(minimize);

        //inputScale и inputFloor выставляют уже после конструктора
        amount.scale = inputScale;
        amount.floor = inputFloor;
        amount.refresh();

        this.showSlider = showSlider;
        this.callback = callback;
        show();
    }

    /** Раньше показывался почти весь контент (isHidden почти всегда false) вместе с мусором без иконок. */
    public boolean visible(T item){
        return item.logicVisible() || specials.contains(item);
    }
}
