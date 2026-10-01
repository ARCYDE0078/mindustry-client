package agzam4.render;

import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.GlyphLayout;
import arc.graphics.g2d.Lines;
import arc.math.geom.Position;
import arc.scene.ui.layout.Scl;
import arc.util.Align;
import arc.util.Time;
import arc.util.pooling.Pools;
import mindustry.graphics.Layer;
import mindustry.ui.Fonts;

/**
 * Отрисовочные хелперы мода. Урезано до реально используемого портированными фичами:
 * textHeight (метрика тултипов), drawTooltip (подсказка у курсора в генераторе процессоров),
 * rotatingArcs (вращающиеся дуги выделения цели доставки).
 */
public class MyDraw {

	public static final int textHeight = 11;

	/** Раньше возвращал GlyphLayout из пула и никто его не освобождал (единственный вызов результат не использует) - утечка на кадр. */
	public static void drawTooltip(String text, float x, float y) {
		Draw.z(Layer.playerName);

		Font font = Fonts.outline;

		GlyphLayout layout = Pools.obtain(GlyphLayout.class, GlyphLayout::new);

		font.setUseIntegerPositions(false);
		font.getData().setScale(0.25f / Scl.scl(1f));
		font.getData().setLineHeight(textHeight*2f * Scl.scl(1f));
		layout.setText(font, text);

		y += layout.height;

		Draw.color(0f, 0f, 0f, 0.5f);
		Fill.rect(x + layout.width / 2, y + textHeight - layout.height / 2, layout.width + 4, layout.height + 3);

		Draw.color();
		Draw.alpha(1f);
		font.setColor(1, 1, 1, 1);
		font.draw(text, x, y + textHeight, 0, Align.left, false);
		Draw.color();

		font.getData().setScale(1f);

		Pools.free(layout);
	}

	public static void rotatingArcs(Position center, float rad, float speed) {
		if(center == null) return;
		float statAngle = Time.time * speed;
		for (int angle = 0; angle < 360; angle+=90) {
			Lines.arc(center.getX(), center.getY(), rad, .2f, statAngle+angle);
		}
	}
}
