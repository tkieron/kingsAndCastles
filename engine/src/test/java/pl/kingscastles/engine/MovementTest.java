package pl.kingscastles.engine;

import org.junit.jupiter.api.Test;
import pl.kingscastles.engine.model.Terrain;
import pl.kingscastles.engine.model.Unit;
import pl.kingscastles.engine.model.UnitType;
import pl.kingscastles.engine.order.Order;
import pl.kingscastles.engine.order.OrderValidator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static pl.kingscastles.engine.TestWorld.p;

class MovementTest {

    @Test
    void knightWalksTwoMeadows() {
        TestWorld w = TestWorld.meadow();
        w.kingdom("fox", 100);
        Unit k = w.unit("fox", UnitType.KNIGHT, 2, 2);
        w.order("fox", new Order.Move(k.id, List.of(p(3, 2), p(4, 3))));
        w.endTurn();
        assertEquals(p(4, 3), k.pos);
    }

    @Test
    void pathRules() {
        TestWorld w = TestWorld.meadow();
        w.kingdom("fox", 100);
        Unit spear = w.unit("fox", UnitType.SPEARMAN, 2, 2);
        Unit knight = w.unit("fox", UnitType.KNIGHT, 2, 6);
        w.terrain(4, 6, Terrain.FOREST);
        w.terrain(3, 2, Terrain.FOREST);
        w.terrain(5, 5, Terrain.MOUNTAIN);

        assertEquals("error.tooFar", key(w, new Order.Move(spear.id, List.of(p(2, 3), p(2, 4), p(2, 5)))));
        assertNull(key(w, new Order.Move(spear.id, List.of(p(3, 2)))), "one step is always allowed, even into forest");
        assertEquals("error.tooFar", key(w, new Order.Move(knight.id, List.of(p(3, 6), p(4, 6), p(5, 6)))));
        assertEquals("error.pathNotConnected", key(w, new Order.Move(knight.id, List.of(p(4, 6)))));
        Unit nearMountain = w.unit("fox", UnitType.SPEARMAN, 4, 4);
        assertEquals("error.cannotEnter", key(w, new Order.Move(nearMountain.id, List.of(p(5, 5)))));
    }

    private static String key(TestWorld w, Order o) {
        var problems = OrderValidator.validate(w.s, "fox", List.of(o));
        return problems.isEmpty() ? null : problems.get(0).key();
    }

    @Test
    void twoFriendsWantTheSameFieldTheEarlierOrderWins() {
        TestWorld w = TestWorld.meadow();
        w.kingdom("fox", 100);
        Unit a = w.unit("fox", UnitType.SPEARMAN, 2, 2);
        Unit b = w.unit("fox", UnitType.SPEARMAN, 4, 2);
        w.order("fox", new Order.Move(b.id, List.of(p(3, 2))), new Order.Move(a.id, List.of(p(3, 2))));
        w.endTurn();
        assertEquals(p(3, 2), b.pos);
        assertEquals(p(2, 2), a.pos);
    }

    @Test
    void aUnitThatStaysBlocksTheField() {
        TestWorld w = TestWorld.meadow();
        w.kingdom("fox", 100);
        w.kingdom("owl", 100);
        Unit a = w.unit("fox", UnitType.SPEARMAN, 2, 2);
        w.unit("owl", UnitType.SPEARMAN, 3, 2);
        w.order("fox", new Order.Move(a.id, List.of(p(3, 2))));
        w.endTurn();
        assertEquals(p(2, 2), a.pos);
    }

    @Test
    void aFieldLeftInTheSameStepCanBeEntered() {
        TestWorld w = TestWorld.meadow();
        w.kingdom("fox", 100);
        Unit a = w.unit("fox", UnitType.SPEARMAN, 2, 2);
        Unit b = w.unit("fox", UnitType.SPEARMAN, 3, 2);
        w.order("fox", new Order.Move(a.id, List.of(p(3, 2))), new Order.Move(b.id, List.of(p(4, 2))));
        w.endTurn();
        assertEquals(p(3, 2), a.pos);
        assertEquals(p(4, 2), b.pos);
    }

    @Test
    void enemiesHeadingForTheSameFieldFight() {
        TestWorld w = TestWorld.meadow().war();
        w.kingdom("fox", 100);
        w.kingdom("owl", 100);
        Unit knight = w.unit("fox", UnitType.KNIGHT, 2, 2);
        Unit settler = w.unit("owl", UnitType.SETTLER, 4, 2);
        w.order("fox", new Order.Move(knight.id, List.of(p(3, 2))));
        w.order("owl", new Order.Move(settler.id, List.of(p(3, 2))));
        var report = w.endTurn();
        assertTrue(w.alive(knight), "a settler (attack 0) can never beat a knight");
        boolean fought = report.events().stream().anyMatch(e -> e.key().startsWith("chronicle.meeting"));
        assertTrue(fought);
        if (!w.alive(settler)) {
            assertEquals(p(3, 2), knight.pos);
        }
    }

    @Test
    void duringThePeaceNobodyFights() {
        TestWorld w = TestWorld.meadow();
        w.kingdom("fox", 100);
        w.kingdom("owl", 100);
        Unit a = w.unit("fox", UnitType.KNIGHT, 2, 2);
        Unit b = w.unit("owl", UnitType.KNIGHT, 4, 2);
        w.order("fox", new Order.Move(a.id, List.of(p(3, 2))));
        w.order("owl", new Order.Move(b.id, List.of(p(3, 2))));
        w.endTurn();
        assertTrue(w.alive(a));
        assertTrue(w.alive(b));
        assertNotEquals(a.pos, b.pos);
    }

    @Test
    void ruinsGiveTreasureToTheFirstVisitor() {
        TestWorld w = TestWorld.meadow();
        var fox = w.kingdom("fox", 0);
        Unit a = w.unit("fox", UnitType.SPEARMAN, 2, 2);
        w.s.map.tile(3, 2).ruins = true;
        w.order("fox", new Order.Move(a.id, List.of(p(3, 2))));
        w.endTurn();
        assertTrue(fox.gold >= 30 - 2, "30-50 gold found, minus 2 upkeep");
        assertFalse(w.s.map.tile(3, 2).ruins);
    }
}
