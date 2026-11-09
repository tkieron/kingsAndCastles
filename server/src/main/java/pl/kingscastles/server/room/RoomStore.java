package pl.kingscastles.server.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pl.kingscastles.server.json.OrderJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Saves each room as one JSON file. Small enough for a tiny VPS, and survives a server restart. */
@Component
public class RoomStore {
    private static final Logger log = LoggerFactory.getLogger(RoomStore.class);

    private final Path dir;
    private final ObjectMapper json = OrderJson.configure(new ObjectMapper().findAndRegisterModules());

    public RoomStore(@Value("${kc.data-dir}") String dir) {
        this.dir = Path.of(dir);
    }

    public synchronized void save(RoomRecord r) {
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(r.code + ".json.tmp");
            json.writeValue(tmp.toFile(), r);
            Files.move(tmp, dir.resolve(r.code + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save room " + r.code, e);
        }
    }

    public List<RoomRecord> loadAll() {
        List<RoomRecord> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                try {
                    out.add(json.readValue(f.toFile(), RoomRecord.class));
                } catch (IOException e) {
                    log.warn("Skipping unreadable room file {}", f, e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
