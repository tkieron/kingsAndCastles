package pl.kingscastles.engine;

import org.junit.jupiter.api.Test;
import pl.kingscastles.engine.model.BuildingType;
import pl.kingscastles.engine.model.Enums.TaxRate;
import pl.kingscastles.engine.model.Enums.TreatyType;
import pl.kingscastles.engine.model.Settlement;
import pl.kingscastles.engine.order.Order;
import pl.kingscastles.engine.order.OrderValidator;
import pl.kingscastles.engine.order.OrderValidator.Problem;
import pl.kingscastles.engine.turn.TurnReport;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** One turn, one move: the child picks a kind of move and gives one order; answers to offers are free. */
class OneMoveTest {

    private static TestWorld oneMove() {
        TestWorld w = TestWorld.meadow();
        w.s.balance.actionsPerTurn = 1;
        return w;
    }

    private static List<String> keys(List<Problem> problems) {
        return problems.stream().map(Problem::key).toList();
    }

    @Test
    void oneOrderIsFine() {
        TestWorld w = oneMove();
        w.kingdom("fox", 100);
        Settlement castle = w.castle("fox", 2, 2);
        assertTrue(OrderValidator.validate(w.s, "fox", List.of(new Order.Build(castle.id, BuildingType.MARKET))).isEmpty());
    }

    @Test
    void aBuildingsMoveMayBuildOnceInEverySettlement() {
        TestWorld w = oneMove();
        w.kingdom("fox", 200);
        Settlement castle = w.castle("fox", 2, 2);
        Settlement village = w.village("fox", 6, 2);
        assertTrue(OrderValidator.validate(w.s, "fox", List.of(
                new Order.Build(castle.id, BuildingType.MARKET),
                new Order.Build(village.id, BuildingType.MARKET))).isEmpty());
    }

    @Test
    void moreOrdersThanSettlementsAreTooMany() {
        TestWorld w = oneMove();
        w.kingdom("fox", 300);
        Settlement castle = w.castle("fox", 2, 2);
        Settlement village = w.village("fox", 6, 2);
        w.terrain(3, 3, pl.kingscastles.engine.model.Terrain.HILLS);
        List<Problem> problems = OrderValidator.validate(w.s, "fox", List.of(
                new Order.Build(castle.id, BuildingType.MARKET),
                new Order.Build(village.id, BuildingType.MARKET),
                new Order.BuildOnTile(TestWorld.p(3, 3), BuildingType.GOLD_MINE)));
        assertEquals(List.of("error.onePerSettlement"), keys(problems));
    }

    @Test
    void withOneSettlementTwoOrdersAreOneTooMany() {
        TestWorld w = oneMove();
        w.kingdom("fox", 300);
        Settlement castle = w.castle("fox", 2, 2);
        w.terrain(3, 3, pl.kingscastles.engine.model.Terrain.HILLS);
        List<Problem> problems = OrderValidator.validate(w.s, "fox", List.of(
                new Order.Build(castle.id, BuildingType.MARKET),
                new Order.BuildOnTile(TestWorld.p(3, 3), BuildingType.GOLD_MINE)));
        assertEquals(List.of("error.oneMovePerTurn"), keys(problems));
    }

    @Test
    void economyAndDiplomacyStayOneOrderWhateverTheSettlements() {
        TestWorld w = oneMove();
        w.kingdom("fox", 100);
        w.kingdom("owl", 100);
        w.castle("fox", 2, 2);
        w.village("fox", 6, 2);
        assertEquals(List.of("error.oneMovePerTurn"), keys(OrderValidator.validate(w.s, "fox", List.of(
                new Order.SetTax(TaxRate.HIGH), new Order.TakeLoan()))));
        assertEquals(List.of("error.oneMovePerTurn"), keys(OrderValidator.validate(w.s, "fox", List.of(
                new Order.ProposeTreaty("owl", TreatyType.TRADE), new Order.Gift("owl", 5, 0)))));
    }

    @Test
    void withoutOrdersPerSettlementAMoveIsOneOrderAgain() {
        TestWorld w = oneMove();
        w.s.balance.ordersPerSettlement = 0;
        w.kingdom("fox", 200);
        Settlement castle = w.castle("fox", 2, 2);
        Settlement village = w.village("fox", 6, 2);
        assertEquals(List.of("error.oneMovePerTurn"), keys(OrderValidator.validate(w.s, "fox", List.of(
                new Order.Build(castle.id, BuildingType.MARKET),
                new Order.Build(village.id, BuildingType.MARKET)))));
    }

    @Test
    void movesOfDifferentKindsAreNotMixedEvenWhenMoreMovesAreAllowed() {
        TestWorld w = oneMove();
        w.s.balance.actionsPerTurn = 3;
        w.kingdom("fox", 100);
        Settlement castle = w.castle("fox", 2, 2);
        List<Problem> problems = OrderValidator.validate(w.s, "fox", List.of(
                new Order.Build(castle.id, BuildingType.MARKET),
                new Order.SetTax(TaxRate.HIGH)));
        assertEquals(List.of("error.oneKindPerTurn"), keys(problems));
    }

    @Test
    void answeringAnOfferDoesNotUseUpTheMove() {
        TestWorld w = oneMove();
        w.kingdom("fox", 100);
        w.kingdom("owl", 100);
        w.kingdom("bear", 100);
        Settlement castle = w.castle("owl", 2, 2);
        w.order("fox", new Order.ProposeTreaty("owl", TreatyType.ALLIANCE));
        w.order("bear", new Order.ProposeTreaty("owl", TreatyType.TRADE));
        w.endTurn();
        assertTrue(OrderValidator.validate(w.s, "owl", List.of(
                new Order.AcceptTreaty("fox", TreatyType.ALLIANCE),
                new Order.RejectTreaty("bear", TreatyType.TRADE),
                new Order.Build(castle.id, BuildingType.MARKET))).isEmpty());
    }

    @Test
    void sayingNoRemovesTheOfferAndTellsTheKingdomThatMadeIt() {
        TestWorld w = oneMove();
        w.kingdom("fox", 100);
        w.kingdom("owl", 100);
        w.order("fox", new Order.ProposeTreaty("owl", TreatyType.ALLIANCE));
        w.endTurn();
        w.order("owl", new Order.RejectTreaty("fox", TreatyType.ALLIANCE));
        TurnReport r = w.endTurn();
        assertTrue(w.s.proposals.isEmpty());
        assertFalse(w.s.allied("fox", "owl"));
        assertTrue(r.events().stream().anyMatch(e -> e.key().equals("chronicle.treatyRejected") && e.visibleTo().contains("fox")));
    }

    @Test
    void onlyAnOfferThatWasMadeCanBeRefused() {
        TestWorld w = oneMove();
        w.kingdom("fox", 100);
        w.kingdom("owl", 100);
        assertEquals(List.of("error.noSuchProposal"),
                keys(OrderValidator.validate(w.s, "owl", List.of(new Order.RejectTreaty("fox", TreatyType.PACT)))));
    }

    @Test
    void anArmyMoveTakesEveryArmyAlongAndMayAlsoTrainOne() {
        TestWorld w = oneMove();
        w.kingdom("fox", 100);
        Settlement castle = w.castle("fox", 2, 2);
        var a = w.unit("fox", pl.kingscastles.engine.model.UnitType.SPEARMAN, 5, 5);
        var b = w.unit("fox", pl.kingscastles.engine.model.UnitType.KNIGHT, 8, 5);
        assertTrue(OrderValidator.validate(w.s, "fox", List.of(
                new Order.Move(a.id, List.of(TestWorld.p(6, 5))),
                new Order.Move(b.id, List.of(TestWorld.p(9, 5))),
                new Order.Recruit(castle.id, pl.kingscastles.engine.model.UnitType.SPEARMAN))).isEmpty());
        assertTrue(keys(OrderValidator.validate(w.s, "fox", List.of(
                new Order.Move(a.id, List.of(TestWorld.p(6, 5))),
                new Order.Recruit(castle.id, pl.kingscastles.engine.model.UnitType.SPEARMAN),
                new Order.TakeLoan()))).contains("error.oneKindPerTurn"));
        Settlement village = w.village("fox", 10, 9);
        // every settlement trains its own army in the same Army move, but only one each
        assertTrue(OrderValidator.validate(w.s, "fox", List.of(
                new Order.Recruit(castle.id, pl.kingscastles.engine.model.UnitType.SPEARMAN),
                new Order.Recruit(village.id, pl.kingscastles.engine.model.UnitType.SPEARMAN))).isEmpty());
        assertTrue(keys(OrderValidator.validate(w.s, "fox", List.of(
                new Order.Recruit(castle.id, pl.kingscastles.engine.model.UnitType.SPEARMAN),
                new Order.Recruit(castle.id, pl.kingscastles.engine.model.UnitType.SPEARMAN)))).contains("error.oneRecruitPerSettlement"));
    }

    @Test
    void gamesFromBeforeLongerGamesKeepTheirOldNumbers() {
        Balance old = Balance.forVersion(0);
        assertEquals(0, old.ordersPerSettlement);
        assertEquals(10, old.goldMineIncome);
        assertEquals(5, old.palaceInstallments);
        assertEquals(300, old.economicWinGold);
        Balance now = Balance.forVersion(Balance.VERSION);
        assertEquals(1, now.ordersPerSettlement);
        assertEquals(new Balance().goldMineIncome, now.goldMineIncome);
    }
}
