package pl.kingscastles.server.room;

import java.util.ArrayList;
import java.util.List;

/** Everything saved about a room: settings, players and the children's orders turn by turn. */
public class RoomRecord {
    public enum Status { LOBBY, PLAYING, FINISHED }

    public String code;
    public String hostToken;
    public long seed;
    public long createdAt;
    public Status status = Status.LOBBY;
    public RoomSettings settings;
    public List<Player> players = new ArrayList<>();
    public List<TurnRecord> turns = new ArrayList<>();
}
