package pl.kingscastles.server.room;

import pl.kingscastles.engine.order.Order;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Everything saved about a room: settings, players and the children's orders turn by turn. */
public class RoomRecord {
    public enum Status { LOBBY, PLAYING, FINISHED }

    public String code;
    public String hostToken;
    public long seed;
    public long createdAt;
    /** When the room was last saved; rooms nobody touched for a long time are removed. */
    public long updatedAt;
    public Status status = Status.LOBBY;
    public RoomSettings settings;
    public List<Player> players = new ArrayList<>();
    public List<TurnRecord> turns = new ArrayList<>();
    /** Frozen: the turn clock stands still until someone presses Resume. Survives a restart. */
    public boolean paused;
    /** Who froze the game: a kingdom id, {@link #BY_HOST}, or {@link #BY_NOBODY} when everyone left. */
    public String pausedBy;
    /** This turn's orders given before the freeze, so they survive a restart during the break. */
    public Map<String, List<Order>> pausedOrders;

    public static final String BY_HOST = "host";
    public static final String BY_NOBODY = "auto";
}
