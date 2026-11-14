package agzam4.industry;

import static agzam4.ModWork.*;

import java.util.Arrays;

import agzam4.ModWork;
import agzam4.flow.metric.Collectors;
import agzam4.render.MyDraw;
import agzam4.render.Text;
import agzam4.utils.Bungle;
import agzam4.utils.Prefs;
import agzam4.ModWork.KeyBinds;
import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.math.Mathf;
import arc.math.geom.Geometry;
import arc.math.geom.Point2;
import arc.scene.ui.layout.*;
import arc.struct.IntQueue;
import arc.struct.IntSet;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.*;
import arc.util.pooling.Pools;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.core.World;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.*;
import mindustry.gen.Building;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.logic.LAccess;
import mindustry.logic.LExecutor;
import mindustry.logic.LVar;
import mindustry.type.*;
import mindustry.ui.Fonts;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.production.Drill.DrillBuild;
import mindustry.world.blocks.heat.HeatProducer;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;
import mindustry.world.blocks.logic.LogicDisplay.LogicDisplayBuild;
import mindustry.world.blocks.logic.MemoryBlock.MemoryBuild;
import mindustry.world.blocks.power.HeaterGenerator;

/**
 * Флагман Agzam's Mod - "промышленный калькулятор":
 * <ul>
 * <li>выделение построек прямоугольником (бинд "selection", по умолчанию G; "clear-selection" - Q)
 *     с онлайн-балансом предметов/жидкостей/энергии/тепла/нагрузки payload в правом верхнем углу,
 *     включая ДПС турелей и подсказки "сколько буров/помп/крафтеров нужно, чтобы закрыть дефицит";</li>
 * <li>учёт планов строительства в балансе (buildplans-calculations);</li>
 * <li>тултип блока под курсором (show-blocks-tooltip): потребление/производство в секунду,
 *     подсветка жилы бура, буфер логических дисплеев/процессоров, остаток стройматериалов;</li>
 * <li>цифра здоровья постройки под курсором (show-units-health - ключ оригинала).</li>
 * </ul>
 * Расчёт ведёт система сборщиков {@code agzam4.flow.metric} (по сборщику на класс блока и на тип
 * потребителя), а не прежняя россыпь {@link ModWork}.produce/consume*.
 * Адаптация: выброшен debug-инспектор объектов и отладочный вывод сборщиков (Debug/ObjectInspector
 * не портированы), обёртка Events мода заменена на arc.Events (вшитой копии нечего отписывать).
 * ВАЖНО: класс нельзя инициализировать до загрузки контента - статические массивы размечаются по Vars.content.
 */
public class IndustryCalculator {

	/* перф: скретчи BFS-обхода рудного пятна под буром (тултип при наведении) - раньше
	 * new IntQueue(128)+new IntSet(128) на каждый кадр наведения; не покидают метод */
	private static final IntQueue drillOres = new IntQueue(128);
	private static final IntSet drillPositions = new IntSet(128);

	private static Seq<Drill> drills = ModWork.getBlocks(Drill.class);
	private static Seq<Pump> pumps = ModWork.getBlocks(Pump.class);
	private static Seq<HeaterGenerator> heatGenerators = ModWork.getBlocks(HeaterGenerator.class);
	private static Seq<HeatProducer> heatProducers = ModWork.getBlocks(HeatProducer.class);
	
	private static Seq<Block>[] crafters = createCrafters();
	private static Seq<Block>[] liquidCrafters = createLiquidCrafters();
	
	/** Жидкости, которые в принципе можно добыть помпами на текущей карте. */
	public static boolean[] hasLiquid = new boolean[Vars.content.liquids().size];
	
	private static BalanceFragment balanceFragment;
	public static AveragedItemsMetrics items;
	public static AveragedLiquidsMetrics liquids;
	
	public static void init() {
		{
			items = new AveragedItemsMetrics(60);
			liquids = new AveragedLiquidsMetrics(60);
			Collectors.items = items;
			Collectors.liquids = liquids;
			Collectors.init();
		}
		
		balanceFragment = new BalanceFragment();
		balanceFragment.build();
		
		Events.on(DataPatchLoadEvent.class, e -> {
			
		});

		Events.on(WorldLoadEndEvent.class, e -> {
			if(hasLiquid.length != Vars.content.liquids().size) hasLiquid = new boolean[Vars.content.liquids().size]; // that can be got using pumps

			drills = ModWork.getBlocks(Drill.class);
			pumps = ModWork.getBlocks(Pump.class);
			heatGenerators = ModWork.getBlocks(HeaterGenerator.class);
			heatProducers = ModWork.getBlocks(HeatProducer.class);

			crafters = createCrafters();
			liquidCrafters = createLiquidCrafters();
			
			for (int i = 0; i < hasLiquid.length; i++) {
				hasLiquid[i] = false;
			}

			for (Tile t : Vars.world.tiles) {
				if(t.block().isAir() && t.floor().liquidDrop != null) {
					hasLiquid[t.floor().liquidDrop.id] = true;
				}
			}
		});

		Events.on(TileChangeEvent.class, e -> {
			if(e.tile.block().isAir() && e.tile.floor().liquidDrop != null) {
				hasLiquid[e.tile.floor().liquidDrop.id] = true;
			}
		});
	}
	

	public static BuildTooltip buildTooltip = new BuildTooltip();

	public static void draw() {
		drawSelect();
	}

	private static LVar memoryIterator = new LVar("");
	private static LVar memoryReader = new LVar("");
	
	public static void drawUi() {

		final float mouseX = Core.input.mouseWorldX();
		final float mouseY = Core.input.mouseWorldY();
		float drawX = mouseX;
		float drawY = mouseY;
		
		Tile tile = Vars.world.tileWorld(mouseX, mouseY);
		if(tile == null) return;
		if(tile.build == null) return;
		Building building = tile.build;

		float multiplier = Vars.state.rules.blockHealthMultiplier*building.team.rules().blockHealthMultiplier;
		float health = building.health()*multiplier;
		float maxHealth = building.maxHealth()*multiplier;
		
		int index = ModWork.getGradientIndex(health, maxHealth);
		
		if(Prefs.settings.bool("show-units-health")) {
			Text.size(1f);
			Draw.color(rs[index], gs[index], bs[index]);
			Text.at(ModWork.roundSimple(health), building.x, building.y + (building.block.size+1)*Vars.tilesize/2f, Align.center);
		}
		

		if(building.team == Vars.player.team() && Prefs.settings.bool("show-blocks-tooltip")) {
			Block block = building.block;


			if(building instanceof DrillBuild drill && drill.dominantItem != null) {
				IntQueue ores = drillOres;
				IntSet positions = drillPositions;
				ores.clear();
				positions.clear();
				if(drill.tile != null) drill.tile.getLinkedTilesAs(block, t -> {
					if(t.drop() != drill.dominantItem) return;
					ores.addLast(t.pos());
					positions.add(t.pos());
				});
				Draw.z(Layer.playerName);
				var draker = Tmp.c1.set(drill.dominantItem.color).lerp(Color.black, .5f);
				float top = mouseY, right = mouseX;
				for (int i = 0; i < 128 && !ores.isEmpty(); i++) {
					Tile ore = Vars.world.tile(ores.removeFirst());
					if(ore == null) continue;
			        Draw.color(drill.dominantItem.color, .75f);
			        Fill.rect(ore.worldx(), ore.worldy(), Vars.tilesize, Vars.tilesize);
			        
			        right = Math.max(right, ore.worldx()+2f);
			        top = Math.max(top, ore.worldy()-Vars.tilesize+2f);

			        for (int a = 0; a < 4; a++) {
						Tile near = Vars.world.tile(ore.x + Geometry.d4x[a], ore.y + Geometry.d4y[a]);
						if(near == null || near.drop() != drill.dominantItem) {
					        Draw.color(draker);
					        Fill.rect(
					        		(ore.worldx()) + Geometry.d4x[a]*Vars.tilesize/2f, 
					        		(ore.worldy()) + Geometry.d4y[a]*Vars.tilesize/2f, 
					        		a%2==0 ? 1 : Vars.tilesize+1, a%2==0 ? Vars.tilesize+1 : 1);
					        continue;
						}
						if(positions.contains(near.pos())) continue;
						
						if(positions.size > 128) continue;
						ores.addLast(near.pos());
						positions.add(near.pos());
					}
			        for (int a = 0; a < 4; a++) {
						Tile near = Vars.world.tile(ore.x + Geometry.d8edge[a].x, ore.y + Geometry.d8edge[a].y);
						if(near == null || near.drop() != drill.dominantItem) continue;
						if(positions.contains(near.pos())) continue;
						
						if(positions.size > 128) continue;
						ores.addLast(near.pos());
						positions.add(near.pos());
					}
				}
				drawX = right;
				drawY = top;
			}

			final float tootlipX = drawX;
			final float tootlipY = drawY;

			if(building instanceof ConstructBuild) {
				Draw.z(Layer.playerName);
				buildTooltip.draw(tootlipX, tootlipY);
				return;
			}
			if(buildTooltip.size() <= 1) return;
			Draw.z(Layer.playerName);
			buildTooltip.draw(tootlipX, tootlipY);
		}
	}
	
	private static void drawSelect() {
		if(!Prefs.settings.bool("selection-calculations")) return;
        final float ts = Vars.tilesize/2f;
		if(selectStart.x != -1 && selectEnd.x != -1) {
			int minX = Math.min(selectStart.x, selectEnd.x);
			int maxX = Math.max(selectStart.x, selectEnd.x)+1;
			int minY = Math.min(selectStart.y, selectEnd.y);
			int maxY = Math.max(selectStart.y, selectEnd.y);

			Draw.z(Layer.plans);
	        Lines.stroke(2f);
	        Draw.color(selectBack);
	        Lines.rect(minX*Vars.tilesize - ts, minY*Vars.tilesize - 1 - ts, (maxX-minX)*Vars.tilesize, (maxY-minY+1)*Vars.tilesize);
	        Draw.color(select);
	        Lines.rect(minX*Vars.tilesize - ts, minY*Vars.tilesize - ts, (maxX-minX)*Vars.tilesize, (maxY-minY+1)*Vars.tilesize);

	        Lines.stroke(1);
	        for (int y = minY; y <= maxY; y++) {
				for (int x = minX; x < maxX; x++) {
					Tile tile = Vars.world.tile(x, y);
					if(tile == null || tile.build == null) continue;
					if(tile.build.team != Vars.player.team()) continue;
					boolean needDraw = true;
					final int size = tile.block().size;
					int zeroX = tile.build.tileX() - Mathf.floor((size-1)/2f);
					int zeroY = tile.build.tileY() - Mathf.floor((size-1)/2f);

					int tx = Math.min(Math.max(minX, zeroX), maxX);
					int ty = Math.min(Math.max(minY, zeroY), maxY);

					needDraw = x == tx && y == ty;
					
					if(!tile.block().isMultiblock()) needDraw = true;
					if(needDraw) {
						float dSize = tile.block().size*Vars.tilesize;
						float dx = zeroX*Vars.tilesize + dSize/2f - ts;//(tile.build.tileX() - size/2f)*Vars.tilesize;
						float dy = zeroY*Vars.tilesize + dSize/2f - ts;//(tile.build.tileY() - size/2f)*Vars.tilesize;
						Draw.z(Layer.blockAdditive);
				        Draw.color(select);
				        Lines.rect(dx-dSize/2f, dy-dSize/2f, dSize, dSize);
						Draw.z(Layer.blockAdditive);
				        Draw.color(selectHower);
				        Fill.rect(dx, dy, dSize, dSize);
					}
				}
			}
		}
	        
		for (int i = 0; i < selected.size; i++) {
			Tile tile = selected.get(i);
			Block block = tile.block();
			int zeroX = tile.centerX() - Mathf.floor((block.size-1)/2f);
			int zeroY = tile.centerY() - Mathf.floor((block.size-1)/2f);

			float dSize = tile.block().size*Vars.tilesize;
			float dx = zeroX*Vars.tilesize + dSize/2f - ts;
			float dy = zeroY*Vars.tilesize + dSize/2f - ts;
			Draw.z(Layer.blockAdditive);
			Draw.color(select);
			Lines.rect(dx-dSize/2f, dy-dSize/2f, dSize, dSize);
			Draw.z(Layer.blockAdditive);
			Draw.color(selectHower);
			Fill.rect(dx, dy, dSize, dSize);
		}
	}
	
	
	// a73e3e
	// D8D8D8 A3A3A3
	// 00DB00 3EA53E 00DB00
	private static final Color select = Color.valueOf("ffffff"), 
			selectBack = Color.valueOf("a3a3a3"), selectHower = Color.valueOf("ffffff").a(.5f);
	
	private static Seq<Tile> selected = new Seq<>();

	private static Point2 selectStart = new Point2(-1, -1);
	private static Point2 selectEnd = new Point2(-1, -1);
	
	public static void update() {
		if(!Prefs.settings.bool("selection-calculations")) return;
		int tileX = World.toTile(Core.input.mouseWorldX());
		int tileY = World.toTile(Core.input.mouseWorldY());
		if(tileX < 0) return;
		if(tileY < 0) return;
		if(tileX >= Vars.world.width()) return;
		if(tileY >= Vars.world.height()) return;
		
		if(ModWork.acceptKey()) {
			if(ModWork.keyDown(KeyBinds.clearSelection)) {
				clearSelection();
			}
		}
		
		if(ModWork.acceptKey() && (ModWork.hasKeyBoard() ? 
				ModWork.keyDown(KeyBinds.selection) 
				: (ModWork.keyDown(KeyBinds.selection) && Core.input.isTouched()))) {
			if(selectStart.x == -1 || selectStart.y == -1) {
				selectStart.x = tileX;
				selectStart.y = tileY;
			}
			selectEnd.x = tileX;
			selectEnd.y = tileY;
		} else {
			if(selectStart.x != -1) {
				int minX = Math.min(selectStart.x, selectEnd.x);
				int maxX = Math.max(selectStart.x, selectEnd.x)+1;
				int minY = Math.min(selectStart.y, selectEnd.y);
				int maxY = Math.max(selectStart.y, selectEnd.y);
				
				Building startBuilding = Vars.world.build(selectStart.x, selectStart.y);
				boolean add = true;
				if(startBuilding != null) {
					if(selected.contains(startBuilding.tileOn())) {
						add = false;
					}
				}
		        for (int y = minY; y <= maxY; y++) {
					for (int x = minX; x < maxX; x++) {
						Building build = Vars.world.build(x, y);
						if(build == null) continue;
						if(add) {
							if(!selected.contains(build.tileOn())) {
								selected.add(build.tileOn());
							}
						} else {
							selected.remove(build.tileOn());
						}
					}
		        }
				
			}
			selectStart.x = -1;
			selectStart.y = -1;
		}
		
		calcBalance();
		calcTooltip();
	}
	

	public static void calcTooltip() {
		if(!Prefs.settings.bool("show-blocks-tooltip")) return;
		
		buildTooltip.rebuild();

		final float mouseX = Core.input.mouseWorldX();
		final float mouseY = Core.input.mouseWorldY();
		
		Tile tile = Vars.world.tileWorld(mouseX, mouseY);
		if(tile == null) return;
		if(tile.build == null) return;
		Building building = tile.build;

		if(building.team != Vars.player.team()) return;
		
		Block block = building.block;

		if(building instanceof ConstructBuild) {
			ConstructBuild cb = (ConstructBuild) building;
			buildTooltip.line(cb.current, "[white]" + cb.current.localizedName.toUpperCase());
			ModWork.getRequired(cb, (item, amount) -> {
				buildTooltip.line(item, "[white]" + amount);
			});
			return;
		}
		
		
		buildTooltip.line(block, "[white]" + block.localizedName.toUpperCase());

		if(building instanceof LogicDisplayBuild) {
			LogicDisplayBuild ldb = (LogicDisplayBuild) building;
			buildTooltip.line("[gray]Commands buffer: [white]" + ldb.commands.size);
			buildTooltip.line("[gray]Operations: [white]" + ldb.operations);
		}
		
		if(building instanceof MemoryBuild) {
			MemoryBuild mb = (MemoryBuild) building;
			for (int i = 0; i < mb.sense(LAccess.memoryCapacity); i++) {
				memoryIterator.setnum(i);
				mb.read(memoryIterator, memoryReader);
				if(memoryReader.isobj) {
					if(memoryReader.objval != null) buildTooltip.line("[gray]" + i + ". [white]" + memoryReader.objval);
				} else {
					if(memoryReader.numval != 0) buildTooltip.line("[gray]" + i + ". [white]" + memoryReader.numval);
				}
			}
		}
		
		if(building instanceof LogicBuild) {
			LogicBuild mb = (LogicBuild) building;

			if(mb.executor.counter != null) {
				buildTooltip.line("[gray]Line: [white]" + Mathf.round((float) (mb.executor.counter.numval)));
			}

			if(mb.executor.graphicsBuffer.size > 0) {
				buildTooltip.line("[gray]Draw buffer: [white]" + mb.executor.graphicsBuffer.size + "/" + LExecutor.maxGraphicsBuffer);
			}
		}

		Collectors.collectors.reset();
		Collectors.collectors.collector(block).from(building);
		
		{


			for (int i = 0; i < Collectors.items.size(); i++) {
				Item item = Vars.content.item(i);
				float ips = Collectors.items.balance[i];
				if(ips == 0) continue;
				addItemInfo(buildTooltip, block, item, ips, false);
			}

			for (int i = 0; i < Collectors.liquids.size(); i++) {
				Liquid liquid = Vars.content.liquid(i);
				float lps = Collectors.liquids.balance[i];
				if(lps == 0) continue;
				addLiquidInfo(buildTooltip, block, liquid, lps, false);
			}

			if(Collectors.heat.heat < 0) {
				buildTooltip.line("[red]" + Iconc.waves + " [lightgray]" + ModWork.round(Collectors.heat.heat) + Bungle.core("unit.persecond"));
				addHeatCrafters(buildTooltip, block, Collectors.heat.heat);
			}

		}
	}
	
	public static void clearSelection() {
		if(selected.size > 0) {
			selected.clear();
			return;
		}
	}
	
	public static Seq<Tile> selected() {
		return selected;
	}

	
	private static float[] selectionItems = new float[0];

	/** Баланс предмета (в секунду) по выделению/планам на момент последнего кадра расчёта. */
	public static float selectionItemBalance(Item item) {
		return item.id < selectionItems.length ? selectionItems[item.id] : 0f;
	}

	private static int blockRequirements[] = new int[Vars.content.items().size];


	private static void calcBalance() {
		
		if(blockRequirements.length != Vars.content.items().size) {
			blockRequirements = new int[Vars.content.items().size];
		}
		Arrays.fill(blockRequirements, 0);
		


		Collectors.collectors.reset();
		
		balanceFragment.element.rebuild();
		
		boolean buildPlans = false;
		if(Prefs.settings.bool("buildplans-calculations")) {
			Cons<BuildPlan> calcFor = buildPlan -> {
				if(buildPlan.breaking) {
					for (var r : buildPlan.block.requirements) blockRequirements[r.item.id] -= r.amount * Vars.state.rules.buildCostMultiplier * Vars.state.rules.deconstructRefundMultiplier;
				} else {
					for (var r : buildPlan.block.requirements) blockRequirements[r.item.id] += r.amount * Vars.state.rules.buildCostMultiplier;
				}
				if(buildPlan.breaking) return;
				Collectors.collectors.collector(buildPlan.block).from(buildPlan);
				
			};
			if(Vars.player.unit() != null) {
				if(Vars.player.unit().plans != null) {
					if(Vars.player.unit().plans().size > 0) {
						for (int i = 0; i < Vars.player.unit().plans().size; i++) {
							calcFor.get(Vars.player.unit().plans().get(i));
						}
						buildPlans = true;
					}
				}
			}
			if(Vars.control.input.selectPlans.size > 0) {
				for (int i = 0; i < Vars.control.input.selectPlans.size; i++) {
					calcFor.get(Vars.control.input.selectPlans.get(i));
				}
				buildPlans = true;
			}
			if(Vars.control.input.linePlans.size > 0) {
				for (int i = 0; i < Vars.control.input.linePlans.size; i++) {
					calcFor.get(Vars.control.input.linePlans.get(i));
				}
				buildPlans = true;
			}
		}
		
		
		if(selected.size > 0) {
			balanceFragment.element.line(buildPlans ? Bungle.calculator("header.selected-and-plans") : Bungle.calculator("header.selected"));
		} else if(buildPlans) {
			balanceFragment.element.line(Bungle.calculator("header.plans"));
		}

		Seq<Tile> selected_ = new Seq<>();
		
		for (int s = 0; s < selected.size; s++) {
			Tile tile = selected.get(s);
			if(tile.build == null) continue;
			if(selected_.contains(tile.build.tile)) continue;
			selected_.add(tile);
		}
		
		selected = selected_;
		
		ObjectMap<Block, Integer> count = new ObjectMap<>();
		
		for (int s = 0; s < selected.size; s++) {
			Tile tile = selected.get(s);
			Building building = tile.build;
			Block block = tile.block();
			if(building == null) continue;
			Collectors.collectors.collector(block).from(building); //(block, building);
			count.put(block, count.get(block, 0)+1);

		}


		/* Снимок баланса ВЫДЕЛЕНИЯ: метрики общие с тултипом, а он сбрасывает их сразу после calcBalance
		 * (Collectors.collectors.reset() в calcTooltip), так что читать Collectors.items напрямую
		 * вне этого метода (генератор процессоров) нельзя - там уже баланс блока под курсором. */
		if(selectionItems.length != Collectors.items.balance.length) selectionItems = new float[Collectors.items.balance.length];
		System.arraycopy(Collectors.items.balance, 0, selectionItems, 0, selectionItems.length);

		{
			if(Collectors.power.power != 0) {
				balanceFragment.element.line(Icon.power.getRegion(), (Collectors.power.power > 0 ? "[green]" : "[scarlet]") + ModWork.round(Collectors.power.power) + Bungle.core("unit.persecond"));
				balanceFragment.element.color(Pal.engine);
			}
			if(Collectors.heat.heat != 0) {
				balanceFragment.element.line("[red]" + Iconc.waves + (Collectors.heat.heat > 0 ? " [green]" : " [scarlet]") + ModWork.round(Collectors.heat.heat) + Bungle.core("unit.persecond"));
				if(Collectors.heat.heat < 0) addHeatCrafters(balanceFragment.element, null, -Collectors.heat.heat);
			}
			if(Collectors.combat.airDps != 0 || Collectors.combat.groundDps != 0) {
				balanceFragment.element.line(Icon.modeAttack.getRegion(), "[sky]" + ModWork.round(Collectors.combat.airDps) + " " + Bungle.calculator("line.air-dps"));
				balanceFragment.element.line(Icon.modeAttack.getRegion(), "[olive]" + ModWork.round(Collectors.combat.groundDps)  + " " + Bungle.calculator("line.ground-dps"));
			}
			

			for (int i = 0; i < Collectors.items.size(); i++) {
				Item item = Vars.content.item(i);
				float ips = Collectors.items.balance[i];
				boolean warn = Collectors.items.overflow[i];
				
				if(ips == 0) {
					if(warn) {
						balanceFragment.element.line(item, "[yellow]0" + Bungle.core("unit.persecond") + " " + Iconc.warning);
					}
					continue;
				}
				if(ips < 0) {
					addItemInfo(balanceFragment.element, null, item, ips, warn);
				} else {
					balanceFragment.element.line(item, " [green]+" + ModWork.round(ips) + Bungle.core("unit.persecond") + (warn ? (" [yellow]" + Iconc.warning) : ""));
				}
			}

			for (int i = 0; i < Collectors.liquids.size(); i++) {
				Liquid liquid = Vars.content.liquid(i);
				float lps = Collectors.liquids.balance[i];
				if(lps == 0) continue;
				if(lps < 0) {
					addLiquidInfo(balanceFragment.element, null, liquid, lps, false);
				} else {
					balanceFragment.element.line(liquid, " [green]+" + ModWork.round(lps) + Bungle.core("unit.persecond"));
				}
			}
			
			if(Collectors.payload.size() > 0) {
				Collectors.payload.each((item, ips) -> {
					if(ips < 0) {
						balanceFragment.element.line(item, " [scarlet]" + ModWork.round(ips) + Bungle.core("unit.persecond"));
						ips = -ips;
					} else {
						balanceFragment.element.line(item, " [lightgray]" + ModWork.round(ips) + Bungle.core("unit.persecond"));
					}
				});
			}

			
		}
		
		
		
		

		count.each((block, c) -> {
			balanceFragment.element.line(block, "[white]x" + c);
			balanceFragment.element.color(Color.white);
		});
		
		for (int i = 0; i < blockRequirements.length; i++) {
			int r = blockRequirements[i];
			if(r == 0) continue;
			balanceFragment.element.line(Vars.content.item(i), r > 0 ? "[red]-"+r : "[green]+"+-r);
			balanceFragment.element.color(Color.white);
		}
		
	}
	
	private static void addItemInfo(IndustryElement element, @Nullable Block block, Item item, float ips, boolean warn) {
		if(ips < 0) {
			element.line(item, " [scarlet]" + ModWork.round(ips) + Bungle.core("unit.persecond") + (warn ? " [yellow]" + Iconc.warning : ""));
			ips = -ips;
		} else {
			element.line(item, " [lightgray]" + ModWork.round(ips) + Bungle.core("unit.persecond") + (warn ? " [yellow]" + Iconc.warning : ""));
		}
		addDrills(element, block, item, ips);
		addCrafters(element, block, item, ips);
	}
	
	private static void addLiquidInfo(IndustryElement element, @Nullable Block block, Liquid liquid, float lps, boolean warn) {
		if(lps < 0) {
			element.line(liquid, " [scarlet]" + ModWork.round(lps) + Bungle.core("unit.persecond") + (warn ? " [yellow]" + Iconc.warning : ""));
			lps = -lps;
		} else {
			element.line(liquid, " [lightgray]" + ModWork.round(lps) + Bungle.core("unit.persecond") + (warn ? " [yellow]" + Iconc.warning : ""));
		}
		addPumps(element, block, liquid, lps);
		addLiquidCrafters(element, block, liquid, lps);
	}

	private static Seq<Block>[] createCrafters() {
		@SuppressWarnings("unchecked")
		Seq<Block>[] crafters = new Seq[Vars.content.items().size];
		for (int i = 0; i < crafters.length; i++) {
			Seq<Block> crafter = new Seq<>();
			crafters[i] = crafter;
		}

		Vars.content.blocks().each(b -> {
			if(b instanceof GenericCrafter) {
				GenericCrafter crafter = (GenericCrafter) b;
				if(crafter.outputItem != null) {
					crafters[crafter.outputItem.item.id].add(b);
				}
				if(crafter.outputItems != null) {
					for (int i = 0; i < crafter.outputItems.length; i++) {
						if(!crafters[crafter.outputItems[i].item.id].contains(b))
						crafters[crafter.outputItems[i].item.id].add(b);
					}
				}
			}
		});
		
		return crafters;
	}
	
	private static Seq<Block>[] createLiquidCrafters() {
		@SuppressWarnings("unchecked")
		Seq<Block>[] crafters = new Seq[Vars.content.liquids().size];
		for (int i = 0; i < crafters.length; i++) {
			Seq<Block> crafter = new Seq<>();
			crafters[i] = crafter;
		}

		Vars.content.blocks().each(b -> {
			if(b instanceof GenericCrafter) {
				GenericCrafter crafter = (GenericCrafter) b;
				if(crafter.outputLiquid != null) {
					crafters[crafter.outputLiquid.liquid.id].add(b);
				}
				if(crafter.outputLiquids != null) {
					for (int i = 0; i < crafter.outputLiquids.length; i++) {
						if(!crafters[crafter.outputLiquids[i].liquid.id].contains(b))
						crafters[crafter.outputLiquids[i].liquid.id].add(b);
					}
				}
			}
		});
		return crafters;
	}
	
	/** Подсказка "сколько помп нужно, чтобы дать столько жидкости". */
	private static void addPumps(IndustryElement element, @Nullable Block block, Liquid liquid, float lps) {
			for (int d = 0; d < pumps.size; d++) {
			Pump pump = pumps.get(d);
			if(!pump.environmentBuildable()) continue;
			if(!pump.isPlaceable()) continue;
			float pumpLps = pump.pumpAmount*60;
			if(pump instanceof SolidPump) {
				if(((SolidPump) pump).result != liquid) continue;
			} else {
				if(!hasLiquid[liquid.id]) continue;
				pumpLps *= pump.size*pump.size;
			}
			float count = lps/pumpLps;
			if(block != null) {
				element.line("[lightgray]> ", 
						pump, " [white]x" + ModWork.round(count) + "[lightgray] or ",
						block, "[white] x" + ModWork.round(1/count));
			} else {
				element.line("[lightgray]> ", pump, " [white]x" + ModWork.round(count));
			}
		}
	}
	
	/** Подсказка "сколько буров нужно, чтобы дать столько предметов". */
	private static void addDrills(IndustryElement element, @Nullable Block block, Item item, float ips) {
		if(!Vars.indexer.hasOre(item)) return;
		for (int d = 0; d < drills.size; d++) {
			Drill drill = drills.get(d);
			if(!drill.environmentBuildable()) continue;
			if(!drill.isPlaceable()) continue;
			if(item.hardness > drill.tier) continue;
			boolean liquid = ModWork.needDrillWaterBoost(drill, item);
			float count = ips/ModWork.drillSpeed(drill, item, liquid);
			if(block != null) {
				element.line("[lightgray]> [white]",
						drill, (liquid ? " [sky]" : " [white]") + "x" + ModWork.round(count) + "[lightgray] or [white]",
						block, "[white] x" + ModWork.round(1/count)
				);
			} else {
				element.line("[lightgray]> [white]",
						drill, (liquid ? " [sky]" : " [white]") + "x" + ModWork.round(count)
				);
			}
		}
	}
	
	/** Подсказка "сколько крафтеров нужно, чтобы дать столько предметов". */
	private static void addCrafters(IndustryElement element, @Nullable Block block, Item item, float ips) {
		for (int c = 0; c < crafters[item.id].size; c++) {
			Block crafter = crafters[item.id].get(c);
			if(!crafter.environmentBuildable()) continue;
			if(!crafter.isPlaceable()) continue;
			float cps = 0f; // crafts per second
			if(crafter instanceof GenericCrafter) {
				GenericCrafter gCrafter = (GenericCrafter) crafter;
				if(gCrafter.outputItem != null) {
					if(gCrafter.outputItem.item == item) cps = gCrafter.outputItem.amount;
				}
				if(gCrafter.outputItems != null) {
					for (int oi = 0; oi < gCrafter.outputItems.length; oi++) {
						if(gCrafter.outputItems[oi].item == item) {
							cps = gCrafter.outputItems[oi].amount;
							break;
						}
					}
				}
				cps *= 60f / gCrafter.craftTime;
			}
			
			float count = ips/cps;
			if(block != null)  {
				element.line("[lightgray]> [white]", 
						crafter, " [white]" + "x" + ModWork.round(count) + "[lightgray] or [white]",
						block, "[white] x" + ModWork.round(1/count)
						);
			} else {
				element.line("[lightgray]> [white]", crafter, " [white]" + "x" + ModWork.round(count));
			}
		}
	}

	/** Подсказка "сколько крафтеров нужно, чтобы дать столько жидкости". */
	private static void addLiquidCrafters(IndustryElement element, @Nullable Block block, Liquid liquid, float lps) {
		for (int c = 0; c < liquidCrafters[liquid.id].size; c++) {
			Block crafter = liquidCrafters[liquid.id].get(c);
			if(!crafter.environmentBuildable()) continue;
			if(!crafter.isPlaceable()) continue;
			float cps = 0f; // crafts per second
			if(crafter instanceof GenericCrafter) {
				GenericCrafter gCrafter = (GenericCrafter) crafter;
				if(gCrafter.outputItem != null) {
					if(gCrafter.outputLiquid.liquid == liquid) cps = gCrafter.outputItem.amount;
				}
				if(gCrafter.outputLiquids != null) {
					for (int ol = 0; ol < gCrafter.outputLiquids.length; ol++) {
						if(gCrafter.outputLiquids[ol].liquid == liquid) {
							cps = gCrafter.outputLiquids[ol].amount;
							break;
						}
					}
				}
				cps *= 60f;// / gCrafter.craftTime;
			}
			
			float count = lps/cps;
			if(block != null) {
				element.line("[lightgray]> ", crafter, " [white]" + "x" + ModWork.round(count) + "[lightgray] or [white]", block, "[white] x" + ModWork.round(1/count));
			} else {
				element.line("[lightgray]> ", crafter, " [white]" + "x" + ModWork.round(count));
			}
		}
	}

	/** Подсказка "сколько источников тепла нужно". */
	private static void addHeatCrafters(IndustryElement element, @Nullable Block block, float hps) {
		for (HeaterGenerator generator : heatGenerators) {
			if(!generator.environmentBuildable()) continue;
			if(!generator.isPlaceable()) continue;
			float count = hps/generator.heatOutput;
			if(block != null) {
				element.line("[lightgray]> ", generator, " [white]" + "x" + ModWork.round(count) + "[lightgray] or [white]",
						block, "[white] x" + ModWork.round(1f/count));
			} else {
				element.line("[lightgray]> ", generator, " [white]" + "x" + ModWork.round(count));
			}
		}
		for (HeatProducer producer : heatProducers) {
			if(!producer.environmentBuildable()) continue;
			if(!producer.isPlaceable()) continue;
			float count = hps/producer.heatOutput;
			if(block != null) {
				element.line("[lightgray]> ", producer, " [white]" + "x" + ModWork.round(count) + "[lightgray] or [white]", block, "[white] x" + ModWork.round(1f/count));
			} else {
				element.line("[lightgray]> ", producer, " [white]" + "x" + ModWork.round(count));
			}
		}
	}
	
	
	/** Плашка баланса в правом верхнем углу экрана (рисуется поверх HUD). */
	static class BalanceFragment extends Table {

		BuildTooltip element = new BuildTooltip();
	    
	    private void build() {
			Core.scene.add(this);
		}
	    
	    String text = "";
	    
	    
		@Override
		public void draw() {
			if(!Prefs.settings.bool("selection-calculations")) return;
			if(element.isEmpty()) return;
			if(Vars.state.isMenu()) return;
			if(Vars.ui.schematics.isShown()) return;
			if(Vars.ui.content.isShown()) return;
			if(Vars.ui.database.isShown()) return;
			if(!Vars.ui.hudfrag.shown) return;
			
			Draw.color();
			Font font = Fonts.outline;
			

			GlyphLayout layout = Pools.obtain(GlyphLayout.class, GlyphLayout::new);
			font.setUseIntegerPositions(false);
			font.getData().setScale(1f);
			font.getData().setLineHeight(MyDraw.textHeight*2f * Scl.scl(1f));
			layout.setText(font, text);
			
			float width = Math.max(element.width(), MyDraw.textHeight*20) + Fonts.outline.getLineHeight();
			float x = Core.scene.getWidth() - width;
			float y = Core.scene.getHeight();
			
			element.padX = Fonts.outline.getLineHeight();
			element.padY = Fonts.outline.getLineHeight();

			element.draw(x, y-element.height()-Fonts.outline.getLineHeight()*2, width);
		} 
		
		protected void rect(float x, float y, float w, float h){
	        Draw.rect("whiteui", x + w/2f, y + h/2f, w, h);
	    }
	}

}