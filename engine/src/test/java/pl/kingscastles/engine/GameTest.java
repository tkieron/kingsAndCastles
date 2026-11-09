package pl.kingscastles.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.kingscastles.engine.model.Enums.Controller;
import pl.kingscastles.engine.model.GameState;
import pl.kingscastles.engine.model.Pos;
import pl.kingscastles.engine.model.Settlement;
import pl.kingscastles.engine.model.Terrain;
import pl.kingscastles.engine.model.Unit;
import pl.kingscastles.engine.order.Order;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameTest {

    private static List<Game.Seat> seats(int n) {
        List<Game.Seat> out = new ArrayList<>();
        out.add(new Game.Seat("Ania", Controller.HUMAN));
        for (int i = 1; i < n; i++) {
            out.add(new Game.Seat("Computer", Controller.AI_BEGINNER));
        }
        return out;
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7})
    void everyKingdomStartsWithTheSameHome(int n) {
        Game g = Game.create(new Game.Setup(1234 + n, 20, seats(n)));
        GameState s = g.state();
        List<Settlement> castles = s.settlements.stream().filter(st -> st.castle).toList();
        assertEquals(n, castles.size());
        assertEquals(2 * n, s.settlements.size());
        assertEquals(n, s.units.size());

        Settlement first = castles.get(0);
        for (Settlement c : castles) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    Pos a = new Pos(first.pos.x() + dx, first.pos.y() + dy);
                    Pos b = new Pos(c.pos.x() + dx, c.pos.y() + dy);
                    if (s.map.inBounds(a) && s.map.inBounds(b)
                            && s.settlementAt(a).isEmpty() && s.settlementAt(b).isEmpty()) {
                        assertEquals(s.map.tile(a).terrain, s.map.tile(b).terrain, "home areas must match");
                    }
                }
            }
            for (Settlement other : castles) {
                if (other != c) {
                    assertTrue(c.pos.distance(other.pos) >= 6, "castles too close: " + c.pos + " " + other.pos);
                }
            }
        }
        for (Settlement st : s.settlements) {
            assertNotEquals(Terrain.RIVER, s.map.tile(st.pos).terrain);
            assertTrue(s.map.tile(st.pos).terrain.passable);
        }
    }

    @Test
    void sameSeedAndOrdersGiveTheSameGame() {
        String a = playFiveTurns(99);
        String b = playFiveTurns(99);
        assertEquals(a, b);
    }

    private static String playFiveTurns(long seed) {
        Game g = Game.create(new Game.Setup(seed, 20, seats(4)));
        StringBuilder sb = new StringBuilder();
        for (int t = 0; t < 5; t++) {
            Unit u = g.state().unitsOf("fox").get(0);
            Pos next = u.pos.neighbours().stream().filter(g.state().map::passable).findFirst().orElseThrow();
            assertTrue(g.submit("fox", List.of(new Order.Move(u.id, List.of(next)))).isEmpty());
            var report = g.endTurn();
            sb.append(report.prestige()).append(report.foodPrice()).append(report.events().size());
            g.state().kingdoms.values().forEach(k -> sb.append(k.gold).append('/').append(k.food).append(';'));
        }
        return sb.toString();
    }

    @Test
    void invalidOrdersAreRejectedAndOldOrdersStay() {
        Game g = Game.create(new Game.Setup(7, 20, seats(2)));
        Unit u = g.state().unitsOf("fox").get(0);
        Pos next = u.pos.neighbours().stream().filter(g.state().map::passable).findFirst().orElseThrow();
        assertTrue(g.submit("fox", List.of(new Order.Move(u.id, List.of(next)))).isEmpty());
        assertFalse(g.submit("fox", List.of(new Order.Move(u.id, List.of(new Pos(-5, -5))))).isEmpty());
        assertEquals(1, g.ordersOf("fox").size());
        assertInstanceOf(Order.Move.class, g.ordersOf("fox").get(0));
    }

    @Test
    void aWholeGamePlaysToTheEnd() {
        Game g = Game.create(new Game.Setup(5, 10, seats(7)));
        for (int t = 0; t < 10; t++) {
            g.endTurn();
        }
        assertTrue(g.state().finished);
        assertFalse(g.state().winners.isEmpty());
        assertThrows(IllegalStateException.class, g::endTurn);
    }
}
