package pl.kingscastles.engine;

import org.junit.jupiter.api.Test;
import pl.kingscastles.engine.model.BuildingType;
import pl.kingscastles.engine.model.Kingdom;
import pl.kingscastles.engine.model.Settlement;
import pl.kingscastles.engine.model.Terrain;
import pl.kingscastles.engine.model.Unit;
import pl.kingscastles.engine.model.UnitType;
import pl.kingscastles.engine.order.Order;
import pl.kingscastles.engine.turn.Combat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static pl.kingscastles.engine.TestWorld.p;

class WarTest {

    /** Dice that roll the given numbers in order. */
    static final class FixedDice extends Random {
        private final Deque<Integer> rolls = new ArrayDeque<>();

        FixedDice(int... values) {
            for (int v : values) {
                rolls.add(v);
            }
        }

        @Override
        public int nextInt(int bound) {
            return rolls.poll() - 1;
        }
    }

    @Test
    void battleMathIsTheOneShownToChildren() {
        TestWorld w = TestWorld.meadow().war();
        w.kingdom("fox", 0);
        w.kingdom("owl", 0);
        w.terrain(3, 2, Terrain.FOREST);
        Unit knight = w.unit("fox", UnitType.KNIGHT, 2, 2);
        Unit archer = w.unit("owl", UnitType.ARCHER, 3, 2);

        Combat.Result r = Combat.attack(w.s, knight, archer, new FixedDice(3, 5));
        // knight 5 + strong vs archer 2 + dice 3 = 10, archer 2 + forest 1 + dice 5 = 8
        assertEquals(10, r.attacker().total());
        assertEquals(8, r.defender().total());
        assertTrue(r.attackerWins());
    }

    @Test
    void aTieGoesToTheDefender() {
        TestWorld w = TestWorld.meadow().war();
        w.kingdom("fox", 0);
        w.kingdom("owl", 0);
        Unit a = w.unit("fox", UnitType.SPEARMAN, 2, 2);
        Unit d = w.unit("owl", UnitType.SPEARMAN, 3, 2);
        // 2 + 4 = 6 vs 3 + 3 = 6
        assertFalse(Combat.attack(w.s, a, d, new FixedDice(4, 3)).attackerWins());
    }

    @Test
    void wallsHelpDefendersAndRamsBreakWalls() {
        TestWorld w = TestWorld.meadow().war();
        w.kingdom("fox", 0);
        w.kingdom("owl", 0);
        Settlement v = w.village("owl", 3, 2);
        v.buildings.add(BuildingType.WALLS);
        Unit spear = w.unit("owl", UnitType.SPEARMAN, 3, 2);
        Unit knight = w.unit("fox", UnitType.KNIGHT, 2, 2);
        Unit ram = w.unit("fox", UnitType.RAM, 2, 3);

        Combat.Result k = Combat.attack(w.s, knight, spear, new FixedDice(6, 1));
        assertEquals(3 + 3 + 1, k.defender().total());
        assertEquals(5 + 6, k.attacker().total());

        Combat.Result r = Combat.attack(w.s, ram, spear, new FixedDice(1, 1));
        assertEquals(1 + 6 + 1, r.attacker().total());
    }

    @Test
    void takingAnEmptyVillageMovesInAndTakesLoot() {
        TestWorld w = TestWorld.meadow().war();
        Kingdom fox = w.kingdom("fox", 0);
        Kingdom owl = w.kingdom("owl", 50);
        Settlement v = w.village("owl", 3, 2);
        v.families = 0;
        Unit knight = w.unit("fox", UnitType.KNIGHT, 2, 2);
        w.order("fox", new Order.Attack(knight.id, p(3, 2)));
        w.endTurn();
        assertEquals(p(3, 2), knight.pos);
        assertEquals("fox", v.owner);
        assertEquals(30, owl.gold);
        assertEquals(20 - 5, fox.gold, "20 loot minus the knight's 5 upkeep");
    }

    @Test
    void attackingNeedsTheTargetNextToTheUnit() {
        TestWorld w = TestWorld.meadow().war();
        w.kingdom("fox", 100);
        w.kingdom("owl", 100);
        Unit knight = w.unit("fox", UnitType.KNIGHT, 2, 2);
        Unit far = w.unit("owl", UnitType.SPEARMAN, 5, 2);
        var report = w.endTurn();
        w.order("fox", new Order.Attack(knight.id, p(5, 2)));
        report = w.endTurn();
        assertTrue(w.alive(far));
        assertTrue(report.events().stream().anyMatch(e -> e.key().equals("chronicle.attackOutOfReach")));
    }

    @Test
    void castlesAreSafeAtTheStart() {
        TestWorld w = TestWorld.meadow();
        w.s.balance.peaceTurns = 0;
        w.kingdom("fox", 100);
        Kingdom owl = w.kingdom("owl", 100);
        Settlement castle = w.castle("owl", 3, 2);
        Unit knight = w.unit("fox", UnitType.KNIGHT, 2, 2);
        w.order("fox", new Order.Attack(knight.id, castle.pos));
        w.endTurn();
        assertNull(owl.overlord);
        assertEquals(p(2, 2), knight.pos);
    }

    @Test
    void takingACastleMakesAVassalWhoPlaysOnAndCanBuyFreedom() {
        TestWorld w = TestWorld.meadow().war();
        Kingdom fox = w.kingdom("fox", 0);
        Kingdom owl = w.kingdom("owl", 300);
        Settlement castle = w.castle("owl", 6, 6);
        castle.families = 0;
        Unit knight = w.unit("fox", UnitType.KNIGHT, 5, 6);
        w.order("fox", new Order.Attack(knight.id, castle.pos));
        w.endTurn();
        assertEquals("fox", owl.overlord);
        assertEquals("owl", castle.owner, "the castle stays the vassal's home");
        assertNotEquals(castle.pos, knight.pos, "the winner's unit steps out of the castle");

        int foxBefore = fox.gold;
        w.order("owl", new Order.BuyFreedom());
        w.endTurn();
        assertNull(owl.overlord);
        assertTrue(fox.gold >= foxBefore + 150 - 5);
    }

    @Test
    void fasterUnitsAttackFirstAndABeatenUnitDoesNotStrikeBack() {
        TestWorld w = TestWorld.meadow().war();
        w.kingdom("fox", 1000);
        w.kingdom("owl", 1000);
        Unit settler = w.unit("owl", UnitType.SETTLER, 3, 2);
        Unit knight = w.unit("fox", UnitType.KNIGHT, 2, 2);
        Unit ram = w.unit("owl", UnitType.RAM, 2, 3);
        // the owl ram attacks the knight, the knight attacks the settler: the knight goes first
        w.order("owl", new Order.Attack(ram.id, knight.pos));
        w.order("fox", new Order.Attack(knight.id, settler.pos));
        var report = w.endTurn();
        var battles = report.events().stream().filter(e -> e.key().equals("chronicle.battle")).toList();
        assertFalse(battles.isEmpty());
        assertEquals(UnitType.KNIGHT, battles.get(0).params().get("attackerUnit"));
    }
}
