package pl.kingscastles.server.room;

/**
 * A child in a room. The token is their secret key; they keep it in the browser to come back.
 * {@code realm} is the name the child gave their kingdom (null: the kingdom keeps its own name) and
 * {@code avatar} the picture they chose (0-9).
 */
public record Player(String token, String name, int seat, String realm, int avatar) {

    public static final int AVATARS = 10;
    public static final int REALM_MAX = 24;

    public Player(String token, String name, int seat) {
        this(token, name, seat, null, 0);
    }
}
