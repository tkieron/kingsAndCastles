package pl.kingscastles.server.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pl.kingscastles.engine.model.BuildingType;
import pl.kingscastles.engine.model.Enums.TaxRate;
import pl.kingscastles.engine.model.Enums.TreatyType;
import pl.kingscastles.engine.model.Pos;
import pl.kingscastles.engine.model.UnitType;
import pl.kingscastles.engine.order.Order;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderJsonTest {
    private final ObjectMapper json = OrderJson.configure(new ObjectMapper());

    @Test
    void everyOrderSurvivesTheTripThroughJson() throws Exception {
        List<Order> all = List.of(
                new Order.SetTax(TaxRate.HIGH),
                new Order.Build(3, BuildingType.MARKET),
                new Order.BuildOnTile(new Pos(4, 5), BuildingType.GOLD_MINE),
                new Order.Recruit(3, UnitType.KNIGHT),
                new Order.Move(7, List.of(new Pos(1, 2), new Pos(2, 2))),
                new Order.Attack(7, new Pos(3, 3)),
                new Order.FoundVillage(8, "Nowa Wieś"),
                new Order.Gift("owl", 10, 2),
                new Order.MarketTrade(0, 5),
                new Order.TakeLoan(),
                new Order.BuyFreedom(),
                new Order.ProposeTreaty("owl", TreatyType.PACT),
                new Order.AcceptTreaty("owl", TreatyType.ALLIANCE),
                new Order.RejectTreaty("owl", TreatyType.PACT),
                new Order.BreakTreaty("owl", TreatyType.TRADE));
        for (Order o : all) {
            String text = json.writeValueAsString(o);
            assertEquals(o, json.readValue(text, Order.class), text);
        }
    }

    @Test
    void theOrderKindIsInTheOrderField() throws Exception {
        Order o = json.readValue("{\"order\":\"proposeTreaty\",\"to\":\"bear\",\"type\":\"TRADE\"}", Order.class);
        assertEquals(new Order.ProposeTreaty("bear", TreatyType.TRADE), o);
    }
}
