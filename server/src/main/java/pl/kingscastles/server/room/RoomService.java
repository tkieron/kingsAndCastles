package pl.kingscastles.server.room;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    public enum Change { LOBBY, ORDERS, READY, TURN, PAUSE }

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
    private final int maxRooms;
    private final long idleMillis;
    private final long pausedIdleMillis;
    static final int PAUSES_PER_WINDOW = 10;
    static final long PAUSE_WINDOW_MILLIS = 60_000;
    private final long autoPauseMillis;

    /** For tests: generous limits. */
    public RoomService(RoomStore store) {
        this(store, 500, 14, 30, 90);
    }

    /**
     * The server is open to anyone on the internet, so it keeps at most {@code maxRooms} rooms and forgets rooms
     * nobody touched for {@code idleDays} days. Otherwise creating rooms in a loop would fill the memory and the disk.
     */
    public RoomService(RoomStore store, int maxRooms, int idleDays) {
        this(store, maxRooms, idleDays, 30, 90);
    }

    /**
     * @param pausedIdleDays a frozen game waits this long for the children to come back (a holiday is longer than 14 days)
     * @param autoPauseSeconds a timed game freezes itself when no screen has been connected for this long
     */
    @Autowired
    public RoomService(RoomStore store, @Value("${kc.max-rooms:500}") int maxRooms, @Value("${kc.room-idle-days:14}") int idleDays,
                       @Value("${kc.paused-room-days:30}") int pausedIdleDays,
                       @Value("${kc.auto-pause-seconds:90}") int autoPauseSeconds) {
        this.store = store;
        this.maxRooms = maxRooms;
        this.idleMillis = TimeUnit.DAYS.toMillis(idleDays);
        this.pausedIdleMillis = TimeUnit.DAYS.toMillis(Math.max(idleDays, pausedIdleDays));
        this.autoPauseMillis = TimeUnit.SECONDS.toMillis(Math.max(1, autoPauseSeconds));
        clock.scheduleWithFixedDelay(() -> {
            try {
                removeIdleRooms(System.currentTimeMillis());
            } catch (RuntimeException e) {
                log.warn("Cleaning idle rooms failed", e);
            }
        }, 1, 1, TimeUnit.HOURS);
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    @PostConstruct
    void loadSavedRooms() {
        long now = System.currentTimeMillis();
        for (RoomRecord r : store.loadAll()) {
            if (isIdle(r, now)) {
                store.delete(r.code);
                log.info("Removed idle room {}", r.code);
                continue;
            }
            Room room = new Room(r);
            try {
                if (r.status != RoomRecord.Status.LOBBY) {
                    room.game = replay(r);
                }
                if (r.paused && r.pausedOrders != null) {
                    r.pausedOrders.forEach(room.game::submit);
                }
                rooms.put(r.code, room);
                if (r.status == RoomRecord.Status.PLAYING && !r.paused) {
                    startTurnClock(room);
                    // after a restart nobody is connected yet: if nobody comes back, the game freezes
                    scheduleAutoPause(room);
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

    public synchronized Room create(RoomSettings settings) {
        if (rooms.size() >= maxRooms) {
            removeIdleRooms(System.currentTimeMillis());
            if (rooms.size() >= maxRooms) {
                log.warn("Room limit reached ({} rooms)", rooms.size());
                throw new GameException("error.serverFull");
            }
        }
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
        return join(code, name, null, 0);
    }

    /** @param realm the kingdom's name chosen by the child; empty keeps the kingdom's own name */
    public Player join(String code, String name, String realm, int avatar) {
        Room room = room(code);
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > 20) {
            throw new GameException("error.badName");
        }
        String realmName = realm == null ? "" : realm.strip();
        if (realmName.length() > Player.REALM_MAX) {
            throw new GameException("error.badRealm");
        }
        if (avatar < 0 || avatar >= Player.AVATARS) {
            throw new GameException("error.badRequest");
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
            p = new Player(UUID.randomUUID().toString(), clean, seat, realmName.isEmpty() ? null : realmName, avatar);
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
            if (room.record.paused) {
                throw new GameException("error.paused");
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
            if (room.record.status != RoomRecord.Status.PLAYING || room.record.paused || room.game.state().turn != turn) {
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
        long run = ++room.clockRun;
        room.timer = clock.schedule(() -> {
            try {
                synchronized (room) {
                    // a pause and a resume in between started a new clock for the same turn: this one is stale
                    if (room.clockRun != run) {
                        return;
                    }
                }
                endTurn(room, turn);
            } catch (RuntimeException e) {
                log.error("Turn end failed in room {}", room.code(), e);
            }
        }, seconds, TimeUnit.SECONDS);
    }

    private static void stopTurnClock(Room room) {
        room.clockRun++;
        if (room.timer != null) {
            room.timer.cancel(false);
            room.timer = null;
        }
        room.turnEndsAt = null;
    }

    // ------------------------------------------------------------------ freezing a game

    /**
     * Freezes or unfreezes the game. Any child in the room or the host may do it. While frozen the turn clock stands
     * still, orders and Ready are refused, and the room is kept for longer. Resume gives the turn its full time again.
     * Children who were ready have to press Ready again, so nobody's turn ends while they are away.
     *
     * @param token a player token or the host token
     */
    public void pause(String code, String token, boolean pause) {
        Room room = room(code);
        synchronized (room) {
            if (room.record.status != RoomRecord.Status.PLAYING || room.game == null) {
                throw new GameException("error.notPlaying");
            }
            String by;
            if (token != null && room.isHost(token)) {
                by = RoomRecord.BY_HOST;
            } else {
                by = kingdomOf(room, token).orElseThrow(() -> new GameException("error.notInRoom"));
            }
            if (pause == room.record.paused) {
                return;
            }
            // every change is written to the disk: pressing the button in a loop must not keep the disk busy
            long now = System.currentTimeMillis();
            if (now - room.pauseWindowStart > PAUSE_WINDOW_MILLIS) {
                room.pauseWindowStart = now;
                room.pauseChanges = 0;
            }
            if (++room.pauseChanges > PAUSES_PER_WINDOW) {
                throw new GameException("error.tooFast");
            }
            if (pause) {
                freeze(room, by);
            } else {
                room.record.paused = false;
                room.record.pausedBy = null;
                room.record.pausedOrders = null;
                startTurnClock(room);
                if (room.connections == 0) {
                    scheduleAutoPause(room);
                }
            }
            store.save(room.record);
        }
        fire(room, Change.PAUSE);
    }

    /** Call while holding the room's lock; the caller saves. */
    private void freeze(Room room, String by) {
        stopTurnClock(room);
        Map<String, List<Order>> orders = new HashMap<>();
        for (Kingdom k : room.game.state().kingdoms.values()) {
            List<Order> o = room.game.ordersOf(k.id);
            if (k.isHuman() && !o.isEmpty()) {
                orders.put(k.id, List.copyOf(o));
            }
        }
        room.record.paused = true;
        room.record.pausedBy = by;
        room.record.pausedOrders = orders.isEmpty() ? null : orders;
        room.ready.clear();
    }

    /** A screen connected to the room: a planned automatic freeze is called off. */
    public void connected(String code) {
        Room room = rooms.get(code);
        if (room == null) {
            return;
        }
        synchronized (room) {
            room.connections++;
            cancelAutoPause(room);
        }
    }

    /** A screen left the room. When the last one goes, a timed game freezes itself after a short wait. */
    public void disconnected(String code) {
        Room room = rooms.get(code);
        if (room == null) {
            return;
        }
        synchronized (room) {
            room.connections = Math.max(0, room.connections - 1);
            if (room.connections == 0) {
                scheduleAutoPause(room);
            }
        }
    }

    /** Call while holding the room's lock. Only a running game with a turn clock needs it: without one nothing happens anyway. */
    private void scheduleAutoPause(Room room) {
        cancelAutoPause(room);
        if (room.record.status != RoomRecord.Status.PLAYING || room.record.paused || room.record.settings.turnSeconds() <= 0) {
            return;
        }
        room.autoPause = clock.schedule(() -> {
            try {
                autoPause(room);
            } catch (RuntimeException e) {
                log.error("Automatic pause failed in room {}", room.code(), e);
            }
        }, autoPauseMillis, TimeUnit.MILLISECONDS);
    }

    private static void cancelAutoPause(Room room) {
        if (room.autoPause != null) {
            room.autoPause.cancel(false);
            room.autoPause = null;
        }
    }

    void autoPause(Room room) {
        synchronized (room) {
            room.autoPause = null;
            if (room.connections > 0 || room.record.status != RoomRecord.Status.PLAYING || room.record.paused
                    || rooms.get(room.code()) != room) {
                return;
            }
            freeze(room, RoomRecord.BY_NOBODY);
            store.save(room.record);
            log.info("Room {} froze itself: nobody is connected", room.code());
        }
        fire(room, Change.PAUSE);
    }

    // ------------------------------------------------------------------ helpers

    public Optional<String> kingdomOf(Room room, String token) {
        return room.player(token).map(p -> Room.kingdomOfSeat(p.seat()));
    }

    private String playingKingdom(Room room, String token) {
        if (room.record.status != RoomRecord.Status.PLAYING || room.game == null) {
            throw new GameException("error.notPlaying");
        }
        if (room.record.paused) {
            throw new GameException("error.paused");
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

    private boolean isIdle(RoomRecord r, long now) {
        long last = Math.max(r.updatedAt, r.createdAt);
        return now - last > (r.paused ? pausedIdleMillis : idleMillis);
    }

    /** Forgets rooms nobody touched for a long time: from memory and from the disk. */
    synchronized int removeIdleRooms(long now) {
        int removed = 0;
        for (Room room : List.copyOf(rooms.values())) {
            synchronized (room) {
                if (!isIdle(room.record, now)) {
                    continue;
                }
                stopTurnClock(room);
                cancelAutoPause(room);
                rooms.remove(room.code());
                store.delete(room.code());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Removed {} idle rooms", removed);
        }
        return removed;
    }

    int roomCount() {
        return rooms.size();
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
