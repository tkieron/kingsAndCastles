package pl.kingscastles.engine;

import pl.kingscastles.engine.map.MapGenerator;
import pl.kingscastles.engine.model.Enums.Controller;
import pl.kingscastles.engine.model.GameState;
import pl.kingscastles.engine.model.Kingdom;
import pl.kingscastles.engine.model.Pos;
import pl.kingscastles.engine.model.Settlement;
import pl.kingscastles.engine.model.Unit;
import pl.kingscastles.engine.model.UnitType;
import pl.kingscastles.engine.order.Order;
import pl.kingscastles.engine.order.OrderValidator;
import pl.kingscastles.engine.order.OrderValidator.Problem;
import pl.kingscastles.engine.turn.TurnReport;
import pl.kingscastles.engine.turn.TurnResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point of the engine: create a game, collect orders during a turn, resolve the turn.
 * The server keeps one Game per room; the turn timer lives in the server.
 */
public class Game {

    /** The seven kingdoms of the Land of Seven Rivers, in the order seats are filled. */
    public record KingdomPreset(String id, String name, String color, String castle, String village) {
    }

    public static final List<KingdomPreset> PRESETS = List.of(
            new KingdomPreset("fox", "Fox", "#e0414b", "Foxhold", "Oakvale"),
            new KingdomPreset("owl", "Owl", "#3f6fe0", "Owlspire", "Mistbrook"),
            new KingdomPreset("bear", "Bear", "#8e44c9", "Bearden", "Honeyfield"),
            new KingdomPreset("stag", "Stag", "#e8b923", "Stagcrest", "Fernwood"),
            new KingdomPreset("turtle", "Turtle", "#14a89a", "Shellmoor", "Reedbank"),
            new KingdomPreset("raven", "Raven", "#4a4a6a", "Ravenrock", "Ashford"),
            new KingdomPreset("wolf", "Wolf", "#f08a24", "Wolfgate", "Pinecross"));

    /** One seat at the table: a child (HUMAN) or a computer king with its level. */
    public record Seat(String playerName, Controller controller) {
    }

    public record Setup(long seed, int maxTurns, List<Seat> seats, Balance balance) {
        public Setup(long seed, int maxTurns, List<Seat> seats) {
            this(seed, maxTurns, seats, new Balance());
        }
    }

    private final GameState state;
    private final Map<String, List<Order>> pending = new HashMap<>();

    private Game(GameState state) {
        this.state = state;
    }

    public static Game create(Setup setup) {
        int n = setup.seats().size();
        MapGenerator.Layout layout = MapGenerator.generate(setup.seed(), n);
        Balance b = setup.balance();
        GameState s = new GameState(setup.seed(), b, layout.map(), setup.maxTurns());
        List<Pos> taken = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            KingdomPreset p = PRESETS.get(i);
            Seat seat = setup.seats().get(i);
            Kingdom k = new Kingdom(p.id(), p.name(), p.color(), seat.controller());
            k.playerName = seat.playerName();
            k.gold = b.startGold;
            k.food = b.startFood;
            s.kingdoms.put(k.id, k);
            Pos castle = layout.castles().get(i);
            Pos village = layout.villages().get(i);
            s.settlements.add(new Settlement(s.newId(), p.castle(), castle, true, k.id, b.startCastleFamilies));
            s.settlements.add(new Settlement(s.newId(), p.village(), village, false, k.id, b.startVillageFamilies));
            s.units.add(new Unit(s.newId(), k.id, UnitType.SPEARMAN, castle));
            taken.add(castle);
            taken.add(village);
        }
        MapGenerator.placeRuins(setup.seed(), s.map, taken, n);
        return new Game(s);
    }

    /** Wraps a state loaded from the save file. */
    public static Game of(GameState state) {
        return new Game(state);
    }

    public GameState state() {
        return state;
    }

    /**
     * Replaces a kingdom's orders for this turn (players can change their mind until time runs out).
     * Returns the problems found; when there are any, the previous orders stay.
     */
    public synchronized List<Problem> submit(String kingdomId, List<Order> orders) {
        List<Problem> problems = OrderValidator.validate(state, kingdomId, orders);
        if (problems.isEmpty()) {
            pending.put(kingdomId, List.copyOf(orders));
        }
        return problems;
    }

    public synchronized List<Order> ordersOf(String kingdomId) {
        return pending.getOrDefault(kingdomId, List.of());
    }

    public synchronized TurnReport endTurn() {
        TurnReport report = TurnResolver.resolve(state, new HashMap<>(pending));
        pending.clear();
        return report;
    }
}
