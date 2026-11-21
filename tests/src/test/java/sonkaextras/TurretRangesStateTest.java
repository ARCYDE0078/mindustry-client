package sonkaextras;

import arc.util.*;
import mindustry.client.ClientVars;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.ItemTurret.*;
import org.junit.jupiter.api.*;
import test.ApplicationTests;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/** Радиусы турелей на настоящем движке: выключенная процессором, но заряженная турель считается рабочей; HUD-переключатели сторон. */
public class TurretRangesStateTest{
    ItemTurretBuild turret;

    @BeforeAll
    public static void launch(){
        ApplicationTests.launchApplication(true);
    }

    @BeforeEach
    void setup(){
        Time.setDeltaProvider(() -> 1f);
        logic.reset();
        state.set(State.playing);
        Tiles tiles = world.resize(30, 30);
        world.beginMapLoad();
        tiles.fill();
        world.endMapLoad();
        world.tile(10, 10).setBlock(Blocks.duo, Team.sharded, 0);
        turret = (ItemTurretBuild)world.tile(10, 10).build;
        turret.handleItem(turret, Items.graphite);
        turret.updateConsumption();

        Player p = Player.create();
        p.team(Team.sharded);
        player = p;
        ClientVars.showingTurrets = false;
        ClientVars.showingAllyTurrets = false;
    }

    @AfterEach
    void cleanup(){
        ClientVars.showingTurrets = false;
        ClientVars.showingAllyTurrets = false;
    }

    @Test
    void enabledTurretWithAmmoIsActive(){
        assertTrue(turret.hasAmmo());
        assertTrue(turret.canShoot());
        assertTrue(TurretRanges.active(turret));
        assertFalse(TurretRanges.disabledWithAmmo(turret));
        assertEquals(turret.estimateDps(), TurretRanges.turretDps(turret), 1e-3f);
        assertTrue(TurretRanges.turretDps(turret) > 0f);
    }

    @Test
    void disabledTurretWithAmmoStillCountsFully(){
        float enabledDps = turret.estimateDps();
        turret.enabled = false;
        turret.updateConsumption(); //движок обнуляет эффективность выключенной
        assertFalse(turret.canShoot(), "движок считает выключенную турель неспособной стрелять");
        assertEquals(0f, turret.estimateDps(), 1e-6f, "и обнуляет её DPS");

        assertTrue(TurretRanges.disabledWithAmmo(turret));
        assertTrue(TurretRanges.active(turret), "для радиусов она рабочая");
        assertEquals(enabledDps, TurretRanges.turretDps(turret), enabledDps * 0.02f, "DPS - как у включённой");
    }

    @Test
    void disabledTurretWithoutAmmoIsNotActive(){
        world.tile(12, 12).setBlock(Blocks.duo, Team.sharded, 0);
        ItemTurretBuild empty = (ItemTurretBuild)world.tile(12, 12).build;
        empty.enabled = false;
        empty.updateConsumption();
        assertFalse(TurretRanges.disabledWithAmmo(empty));
        assertFalse(TurretRanges.active(empty));
        assertEquals(0f, TurretRanges.turretDps(empty), 1e-6f);
    }

    @Test
    void sidesFollowHudToggles(){
        assertTrue(TurretRanges.isOwn(turret));
        assertFalse(TurretRanges.sideShown(turret));
        ClientVars.showingTurrets = true; //красный - вражеские: свою не включает
        assertFalse(TurretRanges.sideShown(turret));
        ClientVars.showingAllyTurrets = true; //зелёный - свои
        assertTrue(TurretRanges.sideShown(turret));

        world.tile(15, 15).setBlock(Blocks.duo, Team.crux, 0);
        var enemy = (ItemTurretBuild)world.tile(15, 15).build;
        assertFalse(TurretRanges.isOwn(enemy));
        ClientVars.showingTurrets = false;
        assertFalse(TurretRanges.sideShown(enemy));
        ClientVars.showingTurrets = true;
        assertTrue(TurretRanges.sideShown(enemy));
    }
}
