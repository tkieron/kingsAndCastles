package pl.kingscastles.engine;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import pl.kingscastles.engine.ai.ComputerKing;
import pl.kingscastles.engine.model.GameState;
import pl.kingscastles.engine.model.Pos;
import pl.kingscastles.engine.model.Territory;
import pl.kingscastles.engine.order.Order;
import pl.kingscastles.engine.model.Enums.Controller;
import pl.kingscastles.engine.model.Kingdom;
import pl.kingscastles.engine.model.Settlement;
import pl.kingscastles.engine.order.OrderValidator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ComputerKingTest {

    @ParameterizedTest
    @EnumSource(value = Controller.class, names = {"AI_BEGINNER", "AI_ADVANCED", "AI_PRO"})
    void computerKingsPlayAWholeGameWithValidOrders(Controller level) {
        Game g = Game.create(new Game.Setup(11, 30, List.of(new Game.Seat("Ania", Controller.HUMAN)), level, new Balance()));
        for (int t = 0; t < 30 && !g.state().finished; t++) {
            for (Kingdom k : g.state().kingdoms.values()) {
                if (!k.isHuman()) {
                    var orders = ComputerKing.orders(g.state(), k.id, k.controller);
                    assertTrue(OrderValidator.validate(g.state(), k.id, orders).isEmpty());
                }
            }
            g.endTurn();
        }
        assertTrue(g.state().finished);
        long built = g.state().settlements.stream()
                .filter(st -> !st.owner.equals("fox"))
                .mapToLong(st -> st.buildings.size()).sum();
        assertTrue(built >= 6, "computer kings should build their kingdoms, built " + built);
    }

    @Test
    void anAbsentChildIsPlayedByTheComputerAndStaysAbsent() {
        Game g = Game.create(new Game.Setup(5, 30, List.of(new Game.Seat("Ania", Controller.HUMAN))));
        Kingdom fox = g.state().kingdom("fox");
        for (int t = 0; t < 6; t++) {
            g.endTurn();
        }
        assertTrue(fox.autopilot);
        long foxBuildings = g.state().settlementsOf("fox").stream().mapToLong(st -> st.buildings.size()).sum()
                + improvements(g.state(), "fox");
        assertTrue(foxBuildings > 0, "the computer looked after the absent child's kingdom");

        g.markPresent("fox");
        g.endTurn();
        assertFalse(fox.autopilot);
    }

    @ParameterizedTest
    @EnumSource(value = Controller.class, names = {"AI_BEGINNER", "AI_ADVANCED", "AI_PRO"})
    void aComputerKingMakesOneMoveATurnLikeTheChildren(Controller level) {
        Game g = Game.create(new Game.Setup(11, 30, List.of(new Game.Seat("Ania", Controller.HUMAN)), level, new Balance()));
        for (int t = 0; t < 30 && !g.state().finished; t++) {
            for (Kingdom k : g.state().kingdoms.values()) {
                if (!k.isHuman()) {
                    long moves = ComputerKing.orders(g.state(), k.id, k.controller).stream()
                            .filter(o -> o.category() != Order.Category.ANSWER).count();
                    assertTrue(moves <= 1, k.id + " made " + moves + " moves in turn " + g.state().turn);
                }
            }
            g.endTurn();
        }
    }

    private static long improvements(GameState s, String kingdom) {
        Territory land = Territory.of(s);
        long n = 0;
        for (int y = 0; y < s.map.height; y++) {
            for (int x = 0; x < s.map.width; x++) {
                Pos p = new Pos(x, y);
                if (s.map.tile(p).improvement != null && land.owns(kingdom, p)) {
                    n++;
                }
            }
        }
        return n;
    }
}
