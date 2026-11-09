package pl.kingscastles.server.room;

/** A child in a room. The token is their secret key; they keep it in the browser to come back. */
public record Player(String token, String name, int seat) {
}
