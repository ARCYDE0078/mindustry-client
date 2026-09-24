package mi2u.ui;

import arc.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mi2u.*;
import mi2u.ui.elements.*;
import mindustry.client.*;
import mindustry.gen.*;
import mindustry.ui.*;
import mindustry.world.blocks.logic.LogicBlock.*;

import static mi2u.MI2UVars.*;
import static mindustry.Vars.*;

/**
 * Поиск по коду логических процессоров на карте: вводишь фразу - видишь все процессоры, где она встречается, кнопка облетает камерой к процессору.
 * Идея из LogicSearchFrag клиента fk4b, но на Mindow2.
 */
public class LogicSearchMindow extends Mindow2{
    private static final int maxResults = 100;

    private String query = "";
    private final Table results = new Table();

    public LogicSearchMindow(){
        super("LogicSearch");
        setVisibleInGame();
        hasCloseButton = true;

        titlePane.defaults().height(buttonSize);
        titlePane.add("@" + name + ".MI2U").labelAlign(Align.center).growX();
    }

    @Override
    public void setupCont(Table cont){
        cont.clear();
        cont.table(top -> {
            top.left();
            TextField field = top.field(query, s -> {
                query = s;
                rebuildResults();
            }).growX().minWidth(220f).get();
            field.setMessageText("@logicsearch.hint");
            top.button("" + Iconc.refresh, textb, this::rebuildResults).size(buttonSize).with(b -> MI2Utils.tooltip(b, "@logicsearch.refresh"));
        }).growX().row();
        cont.pane(Styles.noBarPane, results).grow().maxHeight(400f).row();
        rebuildResults();
    }

    private void rebuildResults(){
        results.clear();
        results.top().left().defaults().left().pad(2f);

        String q = query.trim().toLowerCase();
        if(q.isEmpty()){
            results.add("@logicsearch.empty").color(arc.graphics.Color.gray);
            return;
        }

        int[] found = {0};
        Groups.build.each(b -> {
            if(!(b instanceof LogicBuild lb) || !lb.isValid() || lb.code == null) return;
            if(!lb.code.toLowerCase().contains(q)) return;
            if(found[0]++ >= maxResults) return;

            results.table(row -> {
                row.image(lb.block.uiIcon).size(24f).padRight(4f);
                row.add("[#" + lb.team.color + "]■[] (" + lb.tileX() + ", " + lb.tileY() + ")").padRight(6f);
                row.button("" + Iconc.eye, textb, () -> Spectate.INSTANCE.spectate(lb)).size(buttonSize);
            }).growX().row();
        });

        if(found[0] == 0) results.add("@logicsearch.none").color(arc.graphics.Color.gray);
        else if(found[0] > maxResults) results.add(Core.bundle.format("logicsearch.more", found[0] - maxResults)).color(arc.graphics.Color.gray);
    }
}
