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
