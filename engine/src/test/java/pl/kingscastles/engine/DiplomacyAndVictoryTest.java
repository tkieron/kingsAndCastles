package pl.kingscastles.engine;

import org.junit.jupiter.api.Test;
import pl.kingscastles.engine.model.Enums.TreatyType;
import pl.kingscastles.engine.model.Kingdom;
import pl.kingscastles.engine.model.Unit;
import pl.kingscastles.engine.model.UnitType;
import pl.kingscastles.engine.order.Order;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static pl.kingscastles.engine.TestWorld.p;

class DiplomacyAndVictoryTest {

    @Test
    void aProposedPactIsAcceptedNextTurnAndStopsFighting() {
        TestWorld w = TestWorld.meadow().war();
        w.kingdom("fox", 100);
        w.kingdom("owl", 100);
        w.order("fox", new Order.ProposeTreaty("owl", TreatyType.PACT));
        w.endTurn();
        assertEquals(1, w.s.proposals.size());

        w.order("owl", new Order.AcceptTreaty("fox", TreatyType.PACT));
        w.endTurn();
        assertTrue(w.s.hasTreaty("fox", "owl", TreatyType.PACT));
        assertFalse(w.s.hostile("fox", "owl"));

        Unit a = w.unit("fox", UnitType.KNIGHT, 2, 2);
        Unit b = w.unit("owl", UnitType.SPEARMAN, 3, 2);
        w.order("fox", new Order.Attack(a.id, b.pos));
        w.endTurn();
        assertTrue(w.alive(a));
        assertTrue(w.alive(b));
    }

    @Test
    void anUnansweredProposalExpires() {
        TestWorld w = TestWorld.meadow();
        w.kingdom("fox", 100);
        w.kingdom("owl", 100);
        w.order("fox", new Order.ProposeTreaty("owl", TreatyType.ALLIANCE));
        w.endTurn();
        w.endTurn();
        assertTrue(w.s.proposals.isEmpty());
    }

    @Test
    void breakingAnAllianceTakesTwoTurnsAndCostsPrestige() {
        TestWorld w = TestWorld.meadow();
        Kingdom fox = w.kingdom("fox", 100);
        w.kingdom("owl", 100);
        w.order("fox", new Order.ProposeTreaty("owl", TreatyType.ALLIANCE));
        w.endTurn();
        w.order("owl", new Order.AcceptTreaty("fox", TreatyType.ALLIANCE));
        w.endTurn();
        assertTrue(w.s.allied("fox", "owl"));

        w.order("fox", new Order.BreakTreaty("owl", TreatyType.ALLIANCE));
        w.endTurn();
        assertEquals(1, fox.brokenPromises);
        assertTrue(w.s.allied("fox", "owl"), "still allies during the notice period");
        w.endTurn();
        w.endTurn();
        assertFalse(w.s.allied("fox", "owl"));
    }

    @Test
    void alliancesHaveAtMostThreeKingdoms() {
        TestWorld w = TestWorld.meadow();
        for (String k : List.of("fox", "owl", "bear", "stag")) {
            w.kingdom(k, 100);
        }
        w.order("fox", new Order.ProposeTreaty("owl", TreatyType.ALLIANCE),
                new Order.ProposeTreaty("bear", TreatyType.ALLIANCE),
                new Order.ProposeTreaty("stag", TreatyType.ALLIANCE));
        w.endTurn();
        w.order("owl", new Order.AcceptTreaty("fox", TreatyType.ALLIANCE));
        w.order("bear", new Order.AcceptTreaty("fox", TreatyType.ALLIANCE));
        w.order("stag", new Order.AcceptTreaty("fox", TreatyType.ALLIANCE));
        w.endTurn();
        assertEquals(2, w.s.alliesOf("fox").size());
        assertFalse(w.s.allied("fox", "stag"));
    }

    @Test
    void fourKingdomsUnderOneCrownWinsAtOnce() {
        TestWorld w = TestWorld.meadow();
        for (String k : List.of("fox", "owl", "bear", "stag", "wolf")) {
            w.kingdom(k, 0);
        }
        w.s.kingdom("owl").overlord = "fox";
        w.s.kingdom("bear").overlord = "fox";
        w.s.kingdom("stag").overlord = "fox";
        w.endTurn();
        assertTrue(w.s.finished);
        assertEquals("military", w.s.winReason);
        assertEquals("fox", w.s.winners.get(0));
    }

    @Test
    void alliesDoNotCountTowardsTheMilitaryWin() {
        TestWorld w = TestWorld.meadow();
        for (String k : List.of("fox", "owl", "bear", "stag")) {
            w.kingdom(k, 0);
        }
        w.s.kingdom("owl").overlord = "fox";
        w.s.kingdom("bear").overlord = "fox";
        w.s.treaties.add(new pl.kingscastles.engine.model.Treaty(TreatyType.ALLIANCE, "fox", "stag", 1, Integer.MAX_VALUE));
        w.endTurn();
        assertFalse(w.s.finished);
    }

    @Test
    void palaceAndThreeHundredGoldWinTheEconomicWay() {
        TestWorld w = TestWorld.meadow();
        Kingdom fox = w.kingdom("fox", 300);
        w.kingdom("owl", 0);
        fox.palaceDone = true;
        w.endTurn();
        assertTrue(w.s.finished);
        assertEquals("economic", w.s.winReason);
    }

    @Test
    void afterTheLastTurnTheMostPrestigeWinsTogetherWithAllies() {
        TestWorld w = TestWorld.meadow();
        w.s.maxTurns = 1;
        w.kingdom("fox", 200);
        w.kingdom("owl", 10);
        w.kingdom("bear", 0);
        w.s.treaties.add(new pl.kingscastles.engine.model.Treaty(TreatyType.ALLIANCE, "fox", "bear", 1, Integer.MAX_VALUE));
        w.endTurn();
        assertTrue(w.s.finished);
        assertEquals("prestige", w.s.winReason);
        assertEquals(List.of("fox", "bear"), w.s.winners);
    }

    @Test
    void absentPlayerGetsAnAutopilotAndComesBack() {
        TestWorld w = TestWorld.meadow();
        Kingdom fox = w.kingdom("fox", 100);
        w.castle("fox", 5, 5);
        w.endTurn();
        w.endTurn();
        assertFalse(fox.autopilot);
        w.endTurn();
        assertTrue(fox.autopilot);
        w.order("fox", new Order.SetTax(pl.kingscastles.engine.model.Enums.TaxRate.MEDIUM));
        w.endTurn();
        assertFalse(fox.autopilot);
        assertEquals(p(5, 5), w.s.settlements.get(0).pos);
    }
}
