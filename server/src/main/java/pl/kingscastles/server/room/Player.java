package pl.kingscastles.server.room;

/**
 * A child in a room. The token is their secret key; they keep it in the browser to come back.
 * {@code realm} is the name the child gave their kingdom (null: the kingdom keeps its own name) and
 * {@code avatar} the picture they chose (0-9 kings, 10-19 queens).
 * {@code resumeCode} lets the child come back on another device (see {@link ResumeCodes}); {@code host} is true when
 * the host joined from their own screen, so the same code also gives back the host's buttons.
 */
public record Player(String token, String name, int seat, String realm, int avatar, String resumeCode, boolean host) {

    public static final int AVATARS = 20;
    public static final int REALM_MAX = 24;

    public Player(String token, String name, int seat) {
        this(token, name, seat, null, 0, null, false);
    }

    public Player(String token, String name, int seat, String realm, int avatar) {
        this(token, name, seat, realm, avatar, null, false);
    }

    Player withResumeCode(String code) {
        return new Player(token, name, seat, realm, avatar, code, host);
    }
}
