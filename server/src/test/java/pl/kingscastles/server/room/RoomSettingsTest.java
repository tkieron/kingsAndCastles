package pl.kingscastles.server.room;

import org.junit.jupiter.api.Test;
import pl.kingscastles.engine.model.Enums.Controller;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoomSettingsTest {

    private static final List<Controller> SEATS = Collections.nCopies(7, Controller.HUMAN);

    @Test
    void longGamesUpToNinetyTurnsAreAllowed() {
        for (int turns : List.of(30, 45, 60, 75, 90)) {
            assertEquals(turns, new RoomSettings(60, turns, SEATS, Controller.AI_BEGINNER).maxTurns());
        }
    }

    @Test
    void shortGamesSavedEarlierStillLoad() {
        assertEquals(20, new RoomSettings(60, 20, SEATS, Controller.AI_BEGINNER).maxTurns());
    }

    @Test
    void tooShortOrTooLongIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new RoomSettings(60, 9, SEATS, Controller.AI_BEGINNER));
        assertThrows(IllegalArgumentException.class, () -> new RoomSettings(60, 91, SEATS, Controller.AI_BEGINNER));
    }

    @Test
    void theDefaultGameIsSixtyTurns() {
        assertEquals(60, RoomSettings.defaults().maxTurns());
    }
}
