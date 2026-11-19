package pl.kingscastles.server.room;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.kingscastles.engine.Balance;
import pl.kingscastles.engine.model.Enums.Controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A game saved before a rules change is replayed by the rules it was played with, so it does not change under the children. */
class RulesVersionTest {
    @TempDir
    Path dir;

    private static final RoomSettings SETTINGS = new RoomSettings(0, 60, Collections.nCopies(7, Controller.HUMAN), Controller.AI_BEGINNER);

    @Test
    void aNewRoomIsPlayedByTheCurrentRules() {
        RoomService s = new RoomService(new RoomStore(dir.toString()));
        Room room = s.create(SETTINGS);
        assertEquals(Balance.VERSION, room.record.rulesVersion);
        Balance b = RoomService.setupOf(room.record).balance();
        assertEquals(1, b.ordersPerSettlement);
        assertEquals(15, b.palaceInstallments);
    }

    @Test
    void aRoomSavedBeforeTheRulesVersionKeepsTheOldRules() throws Exception {
        // what a room file looked like before: no rulesVersion
        Files.writeString(dir.resolve("123456.json"), """
                {"code":"123456","hostToken":"h","seed":7,"createdAt":1,"updatedAt":%d,"status":"LOBBY",
                 "settings":{"turnSeconds":0,"maxTurns":20,"seats":["HUMAN","HUMAN","HUMAN","HUMAN","HUMAN","HUMAN","HUMAN"],
                 "emptySeatLevel":"AI_BEGINNER"},"players":[],"turns":[]}
                """.formatted(System.currentTimeMillis()));
        List<RoomRecord> saved = new RoomStore(dir.toString()).loadAll();
        assertEquals(1, saved.size());
        RoomRecord r = saved.get(0);
        assertEquals(0, r.rulesVersion);
        assertEquals(20, r.settings.maxTurns());
        Balance b = RoomService.setupOf(r).balance();
        assertEquals(0, b.ordersPerSettlement);
        assertEquals(10, b.goldMineIncome);
        assertEquals(5, b.palaceInstallments);
        assertEquals(300, b.economicWinGold);
    }
}
