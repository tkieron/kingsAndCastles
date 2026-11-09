package pl.kingscastles.engine;

import org.junit.jupiter.api.Test;
import pl.kingscastles.engine.model.BuildingType;
import pl.kingscastles.engine.model.Kingdom;
import pl.kingscastles.engine.model.Settlement;
import pl.kingscastles.engine.model.Terrain;
import pl.kingscastles.engine.model.Unit;
import pl.kingscastles.engine.model.UnitType;
import pl.kingscastles.engine.order.Order;
import pl.kingscastles.engine.order.OrderValidator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static pl.kingscastles.engine.TestWorld.p;

class BuildTest {

    private static String firstProblem(TestWorld w, String k, Order o) {
        var problems = OrderValidator.validate(w.s, k, List.of(o));
        return problems.isEmpty() ? null : problems.get(0).key();
    }

    @Test
    void buildingAMarketCostsFiftyAndAppearsAtTurnEnd() {
        TestWorld w = TestWorld.meadow();
        Kingdom fox = w.kingdom("fox", 100);
        Settlement village = w.village("fox", 5, 5);
        village.families = 0;
        w.order("fox", new Order.Build(village.id, BuildingType.MARKET));
        w.endTurn();
        assertTrue(village.buildings.contains(BuildingType.MARKET));
        assertEquals(100 - 50, fox.gold, "the new market starts paying next turn; a village has no castle income");
    }

    @Test
    void placementRules() {
        TestWorld w = TestWorld.meadow();
        w.kingdom("fox", 1000);
        Settlement castle = w.castle("fox", 5, 5);
        Settlement village = w.village("fox", 10, 5);
        assertEquals("error.castleOnly", firstProblem(w, "fox", new Order.Build(village.id, BuildingType.TREASURY)));
        assertEquals("error.villageOnly", firstProblem(w, "fox", new Order.Build(castle.id, BuildingType.INN)));
        assertEquals("error.mineNeedsHills", firstProblem(w, "fox", new Order.BuildOnTile(p(6, 6), BuildingType.GOLD_MINE)));
        assertEquals("error.needsBarracks", firstProblem(w, "fox", new Order.Recruit(castle.id, UnitType.KNIGHT)));
        assertNull(firstProblem(w, "fox", new Order.Recruit(castle.id, UnitType.SPEARMAN)));
        w.terrain(6, 6, Terrain.HILLS);
        assertNull(firstProblem(w, "fox", new Order.BuildOnTile(p(6, 6), BuildingType.GOLD_MINE)));
        assertEquals("error.notYourLand", firstProblem(w, "fox", new Order.BuildOnTile(p(0, 0), BuildingType.GOLD_MINE)));
    }

    @Test
    void oneBuildingPerSettlementPerTurn() {
        TestWorld w = TestWorld.meadow();
        w.kingdom("fox", 1000);
        Settlement castle = w.castle("fox", 5, 5);
        var problems = OrderValidator.validate(w.s, "fox", List.of(
                new Order.Build(castle.id, BuildingType.MARKET),
                new Order.Build(castle.id, BuildingType.WALLS)));
        assertEquals("error.oneBuildPerSettlement", problems.get(0).key());
    }

    @Test
    void recruitAppearsInTheSettlementOrNextToIt() {
        TestWorld w = TestWorld.meadow();
        w.kingdom("fox", 100);
        Settlement castle = w.castle("fox", 5, 5);
        Unit guard = w.unit("fox", UnitType.SPEARMAN, 5, 5);
        w.order("fox", new Order.Recruit(castle.id, UnitType.SPEARMAN));
        w.endTurn();
        assertEquals(2, w.s.unitsOf("fox").size());
        Unit fresh = w.s.unitsOf("fox").stream().filter(u -> u != guard).findFirst().orElseThrow();
        assertEquals(1, fresh.pos.distance(castle.pos));
    }

    @Test
    void greatPalaceIsPaidInFiveInstallments() {
        TestWorld w = TestWorld.meadow();
        Kingdom fox = w.kingdom("fox", 1000);
        Settlement castle = w.castle("fox", 5, 5);
        for (int i = 0; i < 5; i++) {
            assertFalse(fox.palaceDone);
            w.order("fox", new Order.Build(castle.id, BuildingType.GREAT_PALACE));
            w.endTurn();
        }
        assertTrue(fox.palaceDone);
    }

    @Test
    void settlerFoundsVillageAwayFromOthers() {
        TestWorld w = TestWorld.meadow();
        w.kingdom("fox", 0);
        w.castle("fox", 2, 2);
        Unit near = w.unit("fox", UnitType.SETTLER, 4, 2);
        Unit far = w.unit("fox", UnitType.SETTLER, 9, 6);
        w.order("fox", new Order.FoundVillage(near.id, "Too close"), new Order.FoundVillage(far.id, "Brookside"));
        w.endTurn();
        assertTrue(w.alive(near));
        assertFalse(w.alive(far));
        assertTrue(w.s.settlementAt(p(9, 6)).isPresent());
        assertEquals("Brookside", w.s.settlementAt(p(9, 6)).get().name);
    }
}
