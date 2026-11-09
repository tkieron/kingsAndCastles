package pl.kingscastles.server.room;

import pl.kingscastles.engine.order.Order;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the children did in one turn. Computer kings are not saved: the engine is deterministic,
 * so replaying the children's orders from the same seed rebuilds the exact same game.
 */
public record TurnRecord(Map<String, List<Order>> orders, Set<String> present) {
}
