package scheme.ui.dialogs;

import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.input.KeyCode;
import arc.scene.ui.Dialog;
import arc.scene.ui.Image;
import arc.scene.ui.Label;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.util.Structs;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.gen.Player;
import mindustry.gen.Tex;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import scheme.ui.List;

import static arc.Core.*;
import static mindustry.Vars.*;

/** Базовый диалог с двумя списками: игроки и базовые команды. */
public class ListDialog extends BaseDialog{

    private static final String[] baseTextures = {
        "team-derelict",
        "team-sharded",
        "team-crux",
        "team-malis",
        "status-electrified-ui",
        "status-wet-ui"
    };

    public List<Player> players;
    public List<Team> teams;

    public ListDialog(String title){
        super(title);
        addCloseButton();

        players = new List<>(Groups.player::each, Player::coloredName, Player::icon, player -> player.team().color);
        teams = new List<>(cons -> Structs.each(cons, Team.baseTeams), Team::localized, ListDialog::texture, team -> team.color);
        teams.header = this::teamIdRow;
    }

    @Override
    public Dialog show(){
        players.set(player);
        return super.show();
    }

    public void addPlayer(){
        players.build(cont);
        players.pane.left();
    }

    public void addTeam(){
        teams.build(cont);
        teams.pane.right();

        // не через List.set, потому что некоторые диалоги закрываются после выбора команды
        players.onChanged = player -> teams.selected = player.team();
    }

    /** ID команды 0-255 из поля или null, если текст - не она. Только цифры, не больше трёх: переполнения нет. */
    public static Team parseTeam(String text){
        if(text == null || text.isEmpty() || text.length() > 3) return null;
        int id = 0;
        for(int i = 0; i < text.length(); i++){
            char c = text.charAt(i);
            if(c < '0' || c > '9') return null;
            id = id * 10 + (c - '0');
        }
        return id <= 255 ? Team.get(id) : null;
    }

    /**
     * Первая строка списка команд: любая команда по ID. В сам список не входит; квадрат цвета и название
     * показывают набранную команду. Если выбрана небазовая команда (например, у выбранного игрока),
     * поле подхватывает её ID.
     */
    private void teamIdRow(Table list){
        list.table(t -> {
            Image swatch = t.image(Tex.whiteui).size(40f).padRight(6f).get();
            TextField field = t.field("", text -> {}).growX().get();
            field.setMessageText("@scheme.team.id");
            field.setFilter(TextField.TextFieldFilter.digitsOnly);
            field.setMaxLength(3);

            Runnable pick = () -> {
                Team team = parseTeam(field.getText());
                if(team != null) teams.set(team);
            };
            field.keyDown(KeyCode.enter, pick);
            t.button(Icon.ok, Styles.clearNonei, pick).size(46f).padLeft(4f).disabled(b -> parseTeam(field.getText()) == null);
            t.row();

            Label name = t.add("").colspan(3).left().padTop(2f).get();
            t.update(() -> {
                Team selected = teams.selected;
                boolean custom = selected != null && selected.id >= Team.baseTeams.length;
                if(custom && !field.hasKeyboard() && field.getText().isEmpty()) field.setText(String.valueOf(selected.id));

                Team typed = parseTeam(field.getText());
                swatch.setColor(typed == null ? Color.darkGray : typed.color);
                name.setText(typed == null ? "" : typed.localized());
                name.setColor(typed == null ? Color.gray : typed.color);
            });
        }).height(74f).row();
    }

    public void addEmpty(){
        cont.table().width(288f);
    }

    public static TextureRegion texture(Team team){
        //собственная иконка есть только у базовых команд
        return team.id < baseTextures.length ? atlas.find(baseTextures[team.id]) : Icon.players.getRegion();
    }
}
