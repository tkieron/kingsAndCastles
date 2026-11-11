package pl.kingscastles.browser;

import org.junit.jupiter.api.Test;
import pl.kingscastles.engine.Balance;
import pl.kingscastles.engine.model.BuildingType;
import pl.kingscastles.engine.model.Enums.TaxRate;
import pl.kingscastles.engine.model.Enums.TreatyType;
import pl.kingscastles.engine.model.Pos;
import pl.kingscastles.engine.model.UnitType;
import pl.kingscastles.engine.order.Order;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineTest {

    @Test
    void rulesListEveryBalanceNumber() {
        Map<?, ?> balance = (Map<?, ?>) ((Map<?, ?>) Json.read(Engine.rules())).get("balance");
        for (Field f : Balance.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                assertTrue(balance.containsKey(f.getName()), "rules() misses Balance." + f.getName());
            }
        }
    }

    @Test
    void ordersSurviveTheTripThroughJson() {
        List<Order> all = List.of(
                new Order.SetTax(TaxRate.HIGH),
                new Order.Build(3, BuildingType.MARKET),
                new Order.BuildOnTile(new Pos(4, 5), BuildingType.BRIDGE),
                new Order.Recruit(3, UnitType.KNIGHT),
                new Order.Move(7, List.of(new Pos(1, 2), new Pos(2, 2))),
                new Order.Attack(7, new Pos(3, 3)),
                new Order.FoundVillage(9, "Żabki \"nad\" rzeką"),
                new Order.Gift("owl", 10, 2),
                new Order.MarketTrade(1, 0),
                new Order.TakeLoan(),
                new Order.BuyFreedom(),
                new Order.ProposeTreaty("owl", TreatyType.PACT),
                new Order.AcceptTreaty("owl", TreatyType.TRADE),
                new Order.RejectTreaty("owl", TreatyType.ALLIANCE),
                new Order.BreakTreaty("owl", TreatyType.ALLIANCE));
        assertEquals(Order.class.getPermittedSubclasses().length, all.size(), "every kind of order is tested");
        for (Order o : all) {
            String json = Json.write(Engine.order(o));
            assertEquals(o, Engine.order((Map<?, ?>) Json.read(json)), json);
        }
    }

    @Test
    void playsAWholeGameWithOneChild() {
        Engine.newGame("{\"seed\":42,\"maxTurns\":10,\"seats\":[{\"name\":\"Ania\",\"controller\":\"HUMAN\"},"
                + "{\"name\":\"C\",\"controller\":\"AI_PRO\"},{\"name\":\"C\",\"controller\":\"AI_PRO\"},"
                + "{\"name\":\"C\",\"controller\":\"AI_PRO\"},{\"name\":\"C\",\"controller\":\"AI_PRO\"},"
                + "{\"name\":\"C\",\"controller\":\"AI_PRO\"},{\"name\":\"C\",\"controller\":\"AI_PRO\"}]}");
        assertEquals("[]", Engine.submit("fox", "[{\"order\":\"setTax\",\"rate\":\"MEDIUM\"}]"));
        assertEquals("[\"fox\"]", Engine.waitingFor());
        while (!Engine.finished()) {
            Engine.endTurn();
        }
        Map<?, ?> view = (Map<?, ?>) Json.read(Engine.view("fox"));
        assertEquals(Boolean.TRUE, view.get("finished"));
        assertTrue(!((List<?>) view.get("winners")).isEmpty());
    }
}
