package pl.kingscastles.engine.turn;

import java.util.List;
import java.util.Map;

/**
 * What happened in one turn: the Chronicle (events), each kingdom's Ledger (gold in and out)
 * and the prestige standings after the turn.
 */
public record TurnReport(int turn,
                         List<ChronicleEvent> events,
                         Map<String, List<LedgerLine>> ledgers,
                         Map<String, Integer> prestige,
                         int foodPrice) {

    /** One line of the Ledger. Positive gold came in, negative went out. */
    public record LedgerLine(String key, int gold, Map<String, Object> params) {
    }
}
