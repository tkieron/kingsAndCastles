package pl.kingscastles.server.room;

import pl.kingscastles.engine.Game;
import pl.kingscastles.engine.turn.TurnReport;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

/** A room while the server runs: the saved record plus the live game and turn timer. Guarded by its own lock. */
public class Room {
    public final RoomRecord record;
    Game game;
    final Set<String> ready = new HashSet<>();
    Long turnEndsAt;
    ScheduledFuture<?> timer;
    TurnReport lastReport;
    /** Counts the turn clocks started, so a clock left over from before a pause never ends the new turn. */
    long clockRun;
    /** Open live connections (screens) to this room. */
    int connections;
    /** Freezes a timed game when nobody has been connected for a while. */
    ScheduledFuture<?> autoPause;
    /** Freezing and unfreezing saves the room, so a room may do it only so often (see RoomService.pause). */
    long pauseWindowStart;
    int pauseChanges;

    Room(RoomRecord record) {
        this.record = record;
    }

    public String code() {
        return record.code;
    }

    public Optional<Game> game() {
        return Optional.ofNullable(game);
    }

    public Set<String> ready() {
        return Set.copyOf(ready);
    }

    public Long turnEndsAt() {
        return turnEndsAt;
    }

    public boolean paused() {
        return record.paused;
    }

    public Optional<TurnReport> lastReport() {
        return Optional.ofNullable(lastReport);
    }

    public Optional<Player> player(String token) {
        return record.players.stream().filter(p -> p.token().equals(token)).findFirst();
    }

    public boolean isHost(String token) {
        return record.hostToken.equals(token);
    }

    public static String kingdomOfSeat(int seat) {
        return Game.PRESETS.get(seat).id();
    }
}
