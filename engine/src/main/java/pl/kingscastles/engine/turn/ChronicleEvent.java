package pl.kingscastles.engine.turn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One line of the turn's Chronicle. {@code key} is a translation key (the client has EN and PL texts),
 * {@code params} fill the placeholders. {@code visibleTo} empty means everyone sees it.
 */
public record ChronicleEvent(int phase, String key, List<String> visibleTo, Map<String, Object> params) {

    public static ChronicleEvent of(int phase, String key, List<String> visibleTo, Object... kv) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            params.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return new ChronicleEvent(phase, key, List.copyOf(visibleTo), params);
    }

    public boolean visibleFor(String kingdom) {
        return visibleTo.isEmpty() || visibleTo.contains(kingdom);
    }
}
