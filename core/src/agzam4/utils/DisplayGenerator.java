package agzam4.utils;

import agzam4.io.GifIO;
import agzam4.utils.DisplayGeneratorShapes.Poly;
import agzam4.utils.DisplayGeneratorShapes.Rect;
import agzam4.utils.DisplayGeneratorShapes.Tri;
import agzam4.utils.animationgen.GifGenerator;
import agzam4.utils.code.Code;
import arc.files.Fi;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.Pixmap;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.struct.StringMap;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Schematic;
import mindustry.game.Schematic.Stile;
import mindustry.ui.FileChooser;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.LogicBlock.LogicLink;
import mindustry.world.blocks.logic.LogicDisplay;

/**
 * Генератор артов: превращает png/jpg (и gif - в анимацию через {@link GifGenerator}) в схему
 * "спираль микропроцессоров вокруг логического дисплея", рисующую картинку draw rect-ами.
 * Адаптация: старый Vars.platform.showMultiFileChooser заменён на builder-API
 * {@link FileChooser} этого форка (см. прецедент в eui SchematicsImportExport).
 */
public class DisplayGenerator {

	// 80x80 - logicDisplay, 176x176 - largeLogicDisplay

	public static void show() {
		Cons<Fi> cons = file -> {
			BaseDialog dialog = new BaseDialog(Bungle.dialog("utils.select-display"));
			dialog.title.setColor(Color.white);
			dialog.titleTable.remove();
			dialog.closeOnBack();

			dialog.cont.pane(p -> {
				p.defaults().left();

				Table t = new Table();
				p.add(t).row();

				t.button(Blocks.logicDisplay.emoji() + " " + Blocks.logicDisplay.localizedName, Styles.defaultt, () -> {
					create(file, 80);
					dialog.hide();
				}).growX().pad(10).padBottom(4).wrapLabel(false).row();

				t.button(Blocks.largeLogicDisplay.emoji() + " " + Blocks.largeLogicDisplay.localizedName, Styles.defaultt, () -> {
					create(file, 176);
					dialog.hide();
				}).growX().pad(10).padBottom(4).wrapLabel(false).row();

				// "LITE" - приближение картинки фигурами (rect/triangle/poly) вместо построчной заливки: схема
				// в разы короче, но это лишь аппроксимация. Для gif не предлагается - анимация только построчная.
				if(!file.extension().equals("gif")) {
					t.button(Blocks.logicDisplay.emoji() + " [LITE] " + Blocks.logicDisplay.localizedName, Styles.defaultt, () -> {
						createBeta(file, 80);
						dialog.hide();
					}).growX().pad(10).padBottom(4).wrapLabel(false).row();

					t.button(Blocks.largeLogicDisplay.emoji() + " [LITE] " + Blocks.largeLogicDisplay.localizedName, Styles.defaultt, () -> {
						createBeta(file, 176);
						dialog.hide();
					}).growX().pad(10).padBottom(4).wrapLabel(false).row();
				}

				t.button("@back", Styles.defaultt, () -> {
					dialog.hide();
					PlayerUtils.hide();
				}).growX().pad(10).padBottom(4).wrapLabel(false).row();
			});
			dialog.show();
		};

		FileChooser.open("png", "jpg", "jpeg", "gif").submit(cons);
	}

	private static void create(Fi file, int size) {
		if(file.extension().equals("gif")) {
			try {
				LogicDisplay display = (LogicDisplay) (size == 80 ? Blocks.logicDisplay : Blocks.largeLogicDisplay);
				GifGenerator.scheme(GifIO.readGifFrames(file, display.displaySize), display);
				PlayerUtils.hide();
			} catch (Throwable e) {
				Vars.ui.showException(e);
			}
			return;
		}

		Pixmap pixmap = new Pixmap(file);
		create(pixmap, size);
		pixmap.dispose();
		PlayerUtils.hide();
	}

	private static void createBeta(Fi file, int size) {
		Pixmap pixmap = new Pixmap(file);
		createBeta(pixmap, size);
		pixmap.dispose();
		PlayerUtils.hide();
	}

	/** Рисует картинку набором фигур (см. {@link DisplayGeneratorShapes}), а не построчно. */
	public static void createBeta(Pixmap pixmap, int size) {
		var shapes = DisplayGeneratorShapes.decompose(pixmap, size, 900/2 - 900/255);
		Seq<Code> codes = new Seq<Code>();

		final int maxCount = 999-2;

		Code code = new Code();
		code.getLink("#Display", 0);
		code.jump("equal #Display null", -1);

		// ждём, пока дисплей дорисует буфер предыдущей команды (не переполнять его)
		code.sensor("#Lock", "@operations", "#Display");
		code.jump("notEqual #Lock 0", -3);

		int count = maxCount;

		int buffer = 0;
		for (int i = 0; i < shapes.size; i++) {
			if(count <= 3) {
				code.drawflush("#Display");
				codes.add(code);
				code = new Code();
				code.getLink("#Display", 0);
				code.jump("equal #Display null", -1);
				count = maxCount;
			}
			var shape = shapes.get(i);

			code.color(shape.r, shape.g, shape.b, 255);
			count--;

			if(shape instanceof Rect rect) code.drawRect(rect.x, rect.y, rect.w, rect.h);
			if(shape instanceof Tri tri) code.drawTriangle(tri.xs, tri.ys);
			if(shape instanceof Poly poly) code.drawPoly((int)poly.x, (int)poly.y, (int)poly.sides, (int)poly.radius, (int)poly.rotation);
			count--;

			buffer += 2;
			if(buffer >= 255) {
				code.drawflush("#Display");
				count--;
				buffer = 0;
			}
		}
		code.drawflush("#Display");
		codes.add(code);

		Code nCode = null;
		codes.add(nCode);

		buildScheme(codes, size);
	}

	public static void create(Pixmap pixmap, int size) {
		int rgb[][] = new int[size][size];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				rgb[x][y] = pixmap.get(x*pixmap.width/size, y*pixmap.height/size);
			}
		}
		Seq<Code> codes = new Seq<Code>();

		final int maxCount = 999-2;

		Code code = new Code();
		code.getLink("#Display", 0);
		code.jump("equal #Display null", -1);
		int count = maxCount;

		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				if(count <= 3) {
					if(code != null) {
						codes.add(code);
					}
					code = new Code();
					code.getLink("#Display", 0);
					code.jump("equal #Display null", -1);
					count = maxCount;
				}
				int w = 1;
				for (int sx = x+1; sx < size; sx++) {
					if(rgb[sx][y] != rgb[x][y]) {
						break;
					}
					w++;
				}
				code.color(rgb[x][y]);
				count--;
				code.drawRect(x, size-y-1, w, 1);
				count--;
				code.drawflush("#Display");
				count--;
				x += w-1;
			}
		}
		if(code != null) codes.add(code);

		Code nCode = null;
		codes.add(nCode);

		buildScheme(codes, size);
	}

	private static void buildScheme(Seq<Code> codes, int size) {
		Seq<Stile> tiles = new Seq<Schematic.Stile>();

		int bsize = size == 80 ? 2 : 4;
		int d = size == 80 ? 0 : -1;
		int wh = bsize;

		int index = 0;

		for (int bs = bsize; bs < 100; bs++) {
			for (int y = -bs-d; y <= bs; y++) {
				for (int x = -bs-d; x <= bs; x++) {
					if(x > -bs-d && y > -bs-d && x < bs && y < bs) continue;
					if(index >= codes.size) {
						wh = bs;
						break;
					}
					Code c = codes.get(index++);
					if(c == null) {
						tiles.add(new Stile(Blocks.message, x, y, "[gold]Auto generated images processor\n[lightgray]Agzam's mod", (byte) 0));
					} else {
						LogicLink link = new LogicLink(-x, -y, "agzamMod-delivery-autolink-" + index, false);
						tiles.add(new Stile(Blocks.microProcessor, x, y,
								LogicBlock.compress(c.toString(), new Seq<>(new LogicLink[] {link})), (byte) 0));
					}
				}
				if(index >= codes.size) break;
			}
			if(index >= codes.size) break;
		}
		wh += bsize;
		tiles.add(new Stile(size == 80 ? Blocks.logicDisplay : Blocks.largeLogicDisplay, 0, 0, null, (byte) 0));

		Schematic schematic = new Schematic(tiles, new StringMap(), wh+1, wh+1);

		Vars.control.input.useSchematic(schematic);
	}
}
