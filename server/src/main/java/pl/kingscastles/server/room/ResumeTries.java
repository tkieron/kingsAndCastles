package pl.kingscastles.server.room;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Counts wrong return codes in one room, so nobody can try codes one after another. Each device (by its internet
 * address) may get {@link #PER_CLIENT} wrong codes per {@link #WINDOW_MILLIS}; one device typing junk therefore
 * does not lock the other children out. All devices together may get {@link #PER_ROOM}, in case someone changes
 * addresses: even then 8.5 × 10^11 codes at 100 per 15 minutes would take millions of years.
 * Guarded by the room's lock.
 */
final class ResumeTries {
    static final int PER_CLIENT = 10;
    static final int PER_ROOM = 100;
    static final long WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(15);

    long windowStart;
    private int roomFailures;
    private final Map<String, Integer> byClient = new HashMap<>();

    boolean blocked(String client, long now) {
        roll(now);
        return roomFailures >= PER_ROOM || byClient.getOrDefault(client, 0) >= PER_CLIENT;
    }

    /** @return how many wrong codes this device has had in the window */
    int failed(String client, long now) {
        roll(now);
        roomFailures++;
        return byClient.merge(client, 1, Integer::sum);
    }

    private void roll(long now) {
        if (now - windowStart > WINDOW_MILLIS) {
            windowStart = now;
            roomFailures = 0;
            byClient.clear();
        }
    }
}
