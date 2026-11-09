package pl.kingscastles.engine.model;

import pl.kingscastles.engine.Balance;
import pl.kingscastles.engine.model.Enums.TreatyType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Everything about one game. The server holds it in memory and saves it after every turn. */
public class GameState {
    public final long seed;
    public final Balance balance;
    public final GameMap map;
    public final Map<String, Kingdom> kingdoms = new LinkedHashMap<>();
    public final List<Settlement> settlements = new ArrayList<>();
    public final List<Unit> units = new ArrayList<>();
    public final List<Treaty> treaties = new ArrayList<>();
    public final List<Proposal> proposals = new ArrayList<>();

    public int turn = 1;
    public int maxTurns;
    public int foodPrice;
    public int nextId = 1;
    public boolean finished;
    public final List<String> winners = new ArrayList<>();
    public String winReason;

    /** A treaty offer waiting for an answer. */
    public record Proposal(String from, String to, TreatyType type, int turn) {
    }

    public GameState(long seed, Balance balance, GameMap map, int maxTurns) {
        this.seed = seed;
        this.balance = balance;
        this.map = map;
        this.maxTurns = maxTurns;
        this.foodPrice = balance.foodPriceStart;
    }

    public int newId() {
        return nextId++;
    }

    public Kingdom kingdom(String id) {
        return kingdoms.get(id);
    }

    public Optional<Settlement> settlementAt(Pos p) {
        return settlements.stream().filter(s -> s.pos.equals(p)).findFirst();
    }

    public Optional<Settlement> settlement(int id) {
        return settlements.stream().filter(s -> s.id == id).findFirst();
    }

    public Optional<Unit> unitAt(Pos p) {
        return units.stream().filter(u -> u.pos.equals(p)).findFirst();
    }

    public Optional<Unit> unit(int id) {
        return units.stream().filter(u -> u.id == id).findFirst();
    }

    public List<Unit> unitsOf(String k) {
        return units.stream().filter(u -> u.owner.equals(k)).toList();
    }

    public List<Settlement> settlementsOf(String k) {
        return settlements.stream().filter(s -> s.owner.equals(k)).toList();
    }

    public Optional<Settlement> castleOf(String k) {
        return settlements.stream().filter(s -> s.castle && s.owner.equals(k)).findFirst();
    }

    public boolean hasTreaty(String x, String y, TreatyType type) {
        return treaties.stream().anyMatch(t -> t.type == type && t.between(x, y) && t.activeIn(turn));
    }

    public boolean allied(String x, String y) {
        return x.equals(y) || hasTreaty(x, y, TreatyType.ALLIANCE);
    }

    /** True when two kingdoms may not fight: same kingdom, the opening peace, a pact or an alliance. */
    public boolean atPeace(String x, String y) {
        return x.equals(y)
                || turn <= balance.peaceTurns
                || hasTreaty(x, y, TreatyType.PACT)
                || hasTreaty(x, y, TreatyType.ALLIANCE);
    }

    public boolean hostile(String x, String y) {
        return !atPeace(x, y);
    }

    public List<String> alliesOf(String k) {
        List<String> out = new ArrayList<>();
        for (Treaty t : treaties) {
            if (t.type == TreatyType.ALLIANCE && t.involves(k) && t.activeIn(turn)) {
                out.add(t.other(k));
            }
        }
        return out;
    }

    public List<String> vassalsOf(String k) {
        return kingdoms.values().stream().filter(v -> k.equals(v.overlord)).map(v -> v.id).toList();
    }
}
