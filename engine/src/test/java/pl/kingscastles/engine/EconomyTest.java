package pl.kingscastles.engine;

import org.junit.jupiter.api.Test;
import pl.kingscastles.engine.model.BuildingType;
import pl.kingscastles.engine.model.Enums.Mood;
import pl.kingscastles.engine.model.Enums.TaxRate;
import pl.kingscastles.engine.model.Kingdom;
import pl.kingscastles.engine.model.Settlement;
import pl.kingscastles.engine.model.Terrain;
import pl.kingscastles.engine.model.UnitType;
import pl.kingscastles.engine.order.Order;
import pl.kingscastles.engine.order.OrderValidator;
import pl.kingscastles.engine.turn.TurnReport;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static pl.kingscastles.engine.TestWorld.p;

class EconomyTest {

    @Test
    void castleAndLowTaxIncome() {
        TestWorld w = TestWorld.meadow();
        Kingdom fox = w.kingdom("fox", 100);
        Settlement castle = w.castle("fox", 5, 5);

        TurnReport r = w.endTurn();

        // castle 10 + 3 families x 2 gold
        assertEquals(116, fox.gold);
        // 8 meadows around x 2 food, 3 eaten
        assertEquals(50 + 16 - 3, fox.food);
        assertEquals(Mood.HAPPY, fox.mood);
        assertEquals(4, castle.families, "a food surplus of 5+ grows the settlement");
        assertTrue(r.ledgers().get("fox").stream().anyMatch(l -> l.key().equals("ledger.tax") && l.gold() == 6));
    }

    @Test
    void highTaxPaysMoreButMakesPeopleSad() {
        TestWorld w = TestWorld.meadow();
        Kingdom fox = w.kingdom("fox", 100);
        w.castle("fox", 5, 5);
        w.order("fox", new Order.SetTax(TaxRate.HIGH));
        w.endTurn();
        assertEquals(100 + 10 + 12, fox.gold);
        assertEquals(Mood.SAD, fox.mood);

        w.order("fox", new Order.SetTax(TaxRate.HIGH));
        w.endTurn();
        // sad families pay 1 gold less: 3 x (4 - 1)
        assertEquals(122 + 10 + 9, fox.gold);
    }

    @Test
    void forestsMinesAndMarketsEarnGold() {
        TestWorld w = TestWorld.meadow();
        Kingdom fox = w.kingdom("fox", 0);
        Settlement castle = w.castle("fox", 5, 5);
        castle.buildings.add(BuildingType.MARKET);
        w.terrain(6, 6, Terrain.FOREST);
        w.terrain(7, 7, Terrain.FOREST);
        w.terrain(3, 3, Terrain.HILLS);
        w.s.map.tile(3, 3).improvement = BuildingType.GOLD_MINE;

        w.endTurn();
        // castle 10 + tax 6 + forests 2 + mine 10 + market 5
        assertEquals(33, fox.gold);
    }

    @Test
    void armyCostsUpkeepAndUnpaidSoldiersGoHome() {
        TestWorld w = TestWorld.meadow();
        Kingdom fox = w.kingdom("fox", 0);
        w.castle("fox", 5, 5);
        w.s.settlements.get(0).families = 0;
        w.unit("fox", UnitType.KNIGHT, 1, 1);
        w.unit("fox", UnitType.KNIGHT, 2, 1);
        w.unit("fox", UnitType.KNIGHT, 3, 1);

        w.endTurn();
        // income 10, three knights cost 15: one goes home, two are paid
        assertEquals(2, w.s.unitsOf("fox").size());
        assertEquals(0, fox.gold);
    }

    @Test
    void loanGivesHundredAndCostsHundredTwenty() {
        TestWorld w = TestWorld.meadow();
        Kingdom fox = w.kingdom("fox", 0);
        w.castle("fox", 5, 5);
        w.order("fox", new Order.TakeLoan());
        w.endTurn();
        assertEquals(100, fox.loanLeft);
        int paid = 20;
        for (int i = 0; i < 5; i++) {
            w.endTurn();
            paid += 20;
        }
        assertEquals(0, fox.loanLeft);
        assertEquals(120, paid);
        assertTrue(OrderValidator.validate(w.s, "fox", List.of(new Order.TakeLoan())).isEmpty());
    }

    @Test
    void treasuryPaysInterestOnSavings() {
        TestWorld w = TestWorld.meadow();
        Kingdom fox = w.kingdom("fox", 250);
        Settlement castle = w.castle("fox", 5, 5);
        castle.families = 0;
        castle.buildings.add(BuildingType.TREASURY);
        w.endTurn();
        // 250 + castle 10 = 260 -> 2 full hundreds -> +10
        assertEquals(270, fox.gold);
    }

    @Test
    void foodMarketSharesOnePriceAndPriceFalls() {
        TestWorld w = TestWorld.meadow();
        Kingdom fox = w.kingdom("fox", 0);
        Kingdom owl = w.kingdom("owl", 0);
        w.castle("fox", 2, 2).families = 0;
        w.castle("owl", 12, 8).families = 0;
        w.order("fox", new Order.MarketTrade(0, 10));
        w.order("owl", new Order.MarketTrade(0, 5));
        TurnReport r = w.endTurn();
        assertEquals(10 + 30, fox.gold);
        assertEquals(10 + 15, owl.gold);
        assertEquals(1, r.foodPrice(), "15 food sold, none bought: price falls by 3, to 1 at the lowest");
    }

    @Test
    void ordersCannotSpendMoreGoldThanTheKingdomHas() {
        TestWorld w = TestWorld.meadow();
        w.kingdom("fox", 60);
        Settlement castle = w.castle("fox", 5, 5);
        var problems = OrderValidator.validate(w.s, "fox", List.of(
                new Order.Build(castle.id, BuildingType.MARKET),
                new Order.Recruit(castle.id, UnitType.SPEARMAN)));
        assertTrue(problems.stream().anyMatch(p -> p.key().equals("error.notEnoughGold")));
    }

    @Test
    void vassalPaysTribute() {
        TestWorld w = TestWorld.meadow();
        Kingdom fox = w.kingdom("fox", 0);
        Kingdom owl = w.kingdom("owl", 0);
        w.castle("fox", 2, 2).families = 0;
        w.castle("owl", 12, 8).families = 0;
        owl.overlord = "fox";
        w.endTurn();
        assertEquals(20, fox.gold);
        assertEquals(0, owl.gold);
    }
}
