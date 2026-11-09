package pl.kingscastles.server.room;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pl.kingscastles.engine.Balance;
import pl.kingscastles.engine.Game;
import pl.kingscastles.engine.model.Enums.Controller;
import pl.kingscastles.engine.model.Kingdom;
import pl.kingscastles.engine.order.Order;
import pl.kingscastles.engine.order.OrderValidator.Problem;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Rooms: create with a 6-digit code, children join, the host starts, everyone gives orders at the same time,
 * the turn ends when time runs out or every child is ready. The room is saved after every change.
 */
@Service
public class RoomService {
    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    /** What changed in a room, so connected players can be told. */
    public enum Change { LOBBY, ORDERS, READY, TURN }

    public interface Listener {
        void roomChanged(Room room, Change change);
    }

    public static class GameException extends RuntimeException {
        public final String key;

        public GameException(String key) {
            super(key);
            this.key = key;
        }
    }

    private final RoomStore store;
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "turn-clock");
        t.setDaemon(true);
        return t;
    });
    private final SecureRandom random = new SecureRandom();

    public RoomService(RoomStore store) {
        this.store = store;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    @PostConstruct
    void loadSavedRooms() {
        for (RoomRecord r : store.loadAll()) {
            Room room = new Room(r);
            try {
                if (r.status != RoomRecord.Status.LOBBY) {
                    room.game = replay(r);
                }
                rooms.put(r.code, room);
                if (r.status == RoomRecord.Status.PLAYING) {
                    startTurnClock(room);
                }
                log.info("Loaded room {} ({}, turn {})", r.code, r.status, r.turns.size() + 1);
            } catch (RuntimeException e) {
                log.warn("Could not restore room {}", r.code, e);
            }
        }
    }

    @PreDestroy
    void stop() {
        clock.shutdownNow();
    }

    // ------------------------------------------------------------------ lobby

    public Room create(RoomSettings settings) {
        RoomRecord r = new RoomRecord();
        r.code = newCode();
        r.hostToken = UUID.randomUUID().toString();
        r.seed = random.nextLong();
        r.createdAt = System.currentTimeMillis();
        r.settings = settings;
        Room room = new Room(r);
        rooms.put(r.code, room);
        store.save(r);
        return room;
    }

    public Room room(String code) {
        Room room = rooms.get(code);
        if (room == null) {
            throw new GameException("error.noSuchRoom");
        }
        return room;
    }

    public Player join(String code, String name) {
        Room room = room(code);
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > 20) {
            throw new GameException("error.badName");
        }
        Player p;
        synchronized (room) {
            if (room.record.status != RoomRecord.Status.LOBBY) {
                throw new GameException("error.gameStarted");
            }
            List<Controller> seats = room.record.settings.seats();
            int seat = -1;
            for (int i = 0; i < seats.size() && seat < 0; i++) {
                int idx = i;
                if (seats.get(i) == Controller.HUMAN && room.record.players.stream().noneMatch(x -> x.seat() == idx)) {
                    seat = i;
                }
            }
            if (seat < 0) {
                throw new GameException("error.roomFull");
            }
            p = new Player(UUID.randomUUID().toString(), clean, seat);
            room.record.players.add(p);
            store.save(room.record);
        }
        fire(room, Change.LOBBY);
        return p;
    }

    public void start(String code, String hostToken) {
        Room room = room(code);
        synchronized (room) {
            requireHost(room, hostToken);
            if (room.record.status != RoomRecord.Status.LOBBY) {
                throw new GameException("error.gameStarted");
            }
            room.record.status = RoomRecord.Status.PLAYING;
            room.game = Game.create(setupOf(room.record));
            store.save(room.record);
            startTurnClock(room);
        }
        fire(room, Change.TURN);
    }

    // ------------------------------------------------------------------ playing

    public List<Problem> submitOrders(String code, String token, List<Order> orders) {
        Room room = room(code);
        List<Problem> problems;
        synchronized (room) {
            String kingdom = playingKingdom(room, token);
            problems = room.game.submit(kingdom, orders);
        }
        if (problems.isEmpty()) {
            fire(room, Change.ORDERS);
        }
        return problems;
    }

    /** The child says "I'm done". When every child who is here is ready, the turn ends at once. */
    public void ready(String code, String token, boolean isReady) {
        Room room = room(code);
        boolean endNow;
        int turn;
        synchronized (room) {
            String kingdom = playingKingdom(room, token);
            turn = room.game.state().turn;
            room.game.markPresent(kingdom);
            if (isReady) {
                room.ready.add(kingdom);
            } else {
                room.ready.remove(kingdom);
            }
            endNow = allReady(room);
        }
        fire(room, Change.READY);
        if (endNow) {
            endTurn(room, turn);
        }
    }

    /** The host can end a turn early, e.g. in "no limit" mode when a child has left. */
    public void hostEndTurn(String code, String hostToken) {
        Room room = room(code);
        int turn;
        synchronized (room) {
            requireHost(room, hostToken);
            if (room.record.status != RoomRecord.Status.PLAYING) {
                throw new GameException("error.notPlaying");
            }
            turn = room.game.state().turn;
        }
        endTurn(room, turn);
    }

    private boolean allReady(Room room) {
        List<String> waitingFor = new ArrayList<>();
        for (Kingdom k : room.game.state().kingdoms.values()) {
            if (k.isHuman() && !k.autopilot && !room.ready.contains(k.id)) {
                waitingFor.add(k.id);
            }
        }
        return waitingFor.isEmpty();
    }

    /**
     * Ends the given turn. The clock, the last Ready and the host can all try at the same moment;
     * only the first one counts, the others see the turn number has moved on and do nothing.
     */
    void endTurn(Room room, int turn) {
        synchronized (room) {
            if (room.record.status != RoomRecord.Status.PLAYING || room.game.state().turn != turn) {
                return;
            }
            if (room.timer != null) {
                room.timer.cancel(false);
                room.timer = null;
            }
            Map<String, List<Order>> human = new HashMap<>();
            for (Kingdom k : room.game.state().kingdoms.values()) {
                List<Order> o = room.game.ordersOf(k.id);
                if (k.isHuman() && !o.isEmpty()) {
                    human.put(k.id, o);
                }
            }
            room.record.turns.add(new TurnRecord(human, room.game.presentPlayers()));
            room.lastReport = room.game.endTurn();
            room.ready.clear();
            if (room.game.state().finished) {
                room.record.status = RoomRecord.Status.FINISHED;
                room.turnEndsAt = null;
            } else {
                startTurnClock(room);
            }
            store.save(room.record);
        }
        fire(room, Change.TURN);
    }

    private void startTurnClock(Room room) {
        int seconds = room.record.settings.turnSeconds();
        if (seconds <= 0) {
            room.turnEndsAt = null;
            return;
        }
        room.turnEndsAt = System.currentTimeMillis() + seconds * 1000L;
        int turn = room.game.state().turn;
        room.timer = clock.schedule(() -> {
            try {
                endTurn(room, turn);
            } catch (RuntimeException e) {
                log.error("Turn end failed in room {}", room.code(), e);
            }
        }, seconds, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ helpers

    public Optional<String> kingdomOf(Room room, String token) {
        return room.player(token).map(p -> Room.kingdomOfSeat(p.seat()));
    }

    private String playingKingdom(Room room, String token) {
        if (room.record.status != RoomRecord.Status.PLAYING || room.game == null) {
            throw new GameException("error.notPlaying");
        }
        return kingdomOf(room, token).orElseThrow(() -> new GameException("error.notInRoom"));
    }

    private static void requireHost(Room room, String token) {
        if (!room.isHost(token)) {
            throw new GameException("error.notHost");
        }
    }

    static Game.Setup setupOf(RoomRecord r) {
        List<Game.Seat> seats = new ArrayList<>();
        List<Controller> plan = r.settings.seats();
        for (int i = 0; i < plan.size(); i++) {
            int idx = i;
            Optional<Player> p = r.players.stream().filter(x -> x.seat() == idx).findFirst();
            if (plan.get(i) == Controller.HUMAN && p.isPresent()) {
                seats.add(new Game.Seat(p.get().name(), Controller.HUMAN));
            } else if (plan.get(i) == Controller.HUMAN) {
                seats.add(new Game.Seat("Computer", r.settings.emptySeatLevel()));
            } else {
                seats.add(new Game.Seat("Computer", plan.get(i)));
            }
        }
        return new Game.Setup(r.seed, r.settings.maxTurns(), seats, Controller.AI_BEGINNER, new Balance());
    }

    /** Rebuilds a game from its seed and the children's saved orders. */
    static Game replay(RoomRecord r) {
        Game g = Game.create(setupOf(r));
        for (TurnRecord t : r.turns) {
            t.orders().forEach(g::submit);
            t.present().forEach(g::markPresent);
            g.endTurn();
        }
        return g;
    }

    private String newCode() {
        for (int i = 0; i < 1000; i++) {
            String code = String.format("%06d", random.nextInt(1_000_000));
            if (!rooms.containsKey(code)) {
                return code;
            }
        }
        throw new IllegalStateException("No free room code");
    }

    private void fire(Room room, Change change) {
        for (Listener l : listeners) {
            try {
                l.roomChanged(room, change);
            } catch (RuntimeException e) {
                log.warn("Listener failed", e);
            }
        }
    }
}
