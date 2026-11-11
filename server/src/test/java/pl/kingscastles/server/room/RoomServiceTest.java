package pl.kingscastles.server.room;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.kingscastles.engine.model.Enums.Controller;
import pl.kingscastles.engine.model.Enums.TaxRate;
import pl.kingscastles.engine.model.Kingdom;
import pl.kingscastles.engine.order.Order;
import pl.kingscastles.server.room.RoomService.GameException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoomServiceTest {
    @TempDir
    Path dir;

    private static final RoomSettings TWO_CHILDREN_NO_CLOCK = new RoomSettings(0, 10, List.of(
            Controller.HUMAN, Controller.HUMAN, Controller.AI_BEGINNER, Controller.AI_ADVANCED,
            Controller.AI_PRO, Controller.AI_BEGINNER, Controller.AI_ADVANCED), Controller.AI_BEGINNER);

    private RoomService service() {
        RoomService s = new RoomService(new RoomStore(dir.toString()));
        s.loadSavedRooms();
        return s;
    }

    @Test
    void childrenJoinFreeChildSeatsOnlyAndTheRoomFillsUp() {
        RoomService s = service();
        Room room = s.create(TWO_CHILDREN_NO_CLOCK);
        assertEquals(6, room.code().length());
        Player ania = s.join(room.code(), " Ania ");
        Player bartek = s.join(room.code(), "Bartek");
        assertEquals("Ania", ania.name());
        assertEquals(0, ania.seat());
        assertEquals(1, bartek.seat());
        assertEquals("error.roomFull", assertThrows(GameException.class, () -> s.join(room.code(), "Ola")).key);
        assertEquals("error.badName", assertThrows(GameException.class, () -> s.join(room.code(), "  ")).key);
        assertEquals("error.noSuchRoom", assertThrows(GameException.class, () -> s.join("000000x", "Ola")).key);
    }

    @Test
    void theKingdomNameIsOptionalAndThePictureIsOneOfTwenty() {
        RoomService s = service();
        Room room = s.create(TWO_CHILDREN_NO_CLOCK);
        assertEquals("error.badRealm", assertThrows(GameException.class,
                () -> s.join(room.code(), "Ania", "x".repeat(25), 0)).key);
        assertEquals("error.badRequest", assertThrows(GameException.class,
                () -> s.join(room.code(), "Ania", "", 20)).key);
        Player ania = s.join(room.code(), "Ania", "  Słoneczna Dolina ", 7);
        Player bartek = s.join(room.code(), "Bartek", "   ", 2);
        assertEquals("Słoneczna Dolina", ania.realm());
        assertEquals(7, ania.avatar());
        assertNull(bartek.realm(), "an empty name keeps the kingdom's own name");
    }

    @Test
    void onlyTheHostStartsAndNobodyJoinsAfterwards() {
        RoomService s = service();
        Room room = s.create(TWO_CHILDREN_NO_CLOCK);
        s.join(room.code(), "Ania");
        assertEquals("error.notHost", assertThrows(GameException.class, () -> s.start(room.code(), "nope")).key);
        s.start(room.code(), room.record.hostToken);
        assertEquals(RoomRecord.Status.PLAYING, room.record.status);
        assertEquals("error.gameStarted", assertThrows(GameException.class, () -> s.join(room.code(), "Ola")).key);

        // the empty child seat is played by the computer, so there are still seven kingdoms
        assertEquals(7, room.game.state().kingdoms.size());
        assertEquals(1, room.game.state().kingdoms.values().stream().filter(Kingdom::isHuman).count());
    }

    @Test
    void theTurnEndsWhenEveryChildIsReady() {
        RoomService s = service();
        Room room = s.create(TWO_CHILDREN_NO_CLOCK);
        Player ania = s.join(room.code(), "Ania");
        Player bartek = s.join(room.code(), "Bartek");
        s.start(room.code(), room.record.hostToken);
        List<RoomService.Change> changes = new ArrayList<>();
        s.addListener((r, c) -> changes.add(c));

        assertTrue(s.submitOrders(room.code(), ania.token(), List.of(new Order.SetTax(TaxRate.HIGH))).isEmpty());
        s.ready(room.code(), ania.token(), true);
        assertEquals(1, room.game.state().turn, "still waiting for Bartek");

        s.ready(room.code(), bartek.token(), true);
        assertEquals(2, room.game.state().turn);
        assertTrue(room.ready().isEmpty());
        assertEquals(TaxRate.HIGH, room.game.state().kingdom("fox").tax);
        assertTrue(room.lastReport().isPresent());
        assertEquals(List.of(RoomService.Change.ORDERS, RoomService.Change.READY,
                RoomService.Change.READY, RoomService.Change.TURN), changes);
    }

    @Test
    void wrongOrdersComeBackAsProblemsAndAreNotKept() {
        RoomService s = service();
        Room room = s.create(TWO_CHILDREN_NO_CLOCK);
        Player ania = s.join(room.code(), "Ania");
        s.start(room.code(), room.record.hostToken);
        var problems = s.submitOrders(room.code(), ania.token(), List.of(new Order.Build(9999, pl.kingscastles.engine.model.BuildingType.MARKET)));
        assertFalse(problems.isEmpty());
        assertTrue(room.game.ordersOf("fox").isEmpty());
        assertEquals("error.notInRoom",
                assertThrows(GameException.class, () -> s.ready(room.code(), "stranger", true)).key);
    }

    @Test
    void aTurnEndsOnlyOnceWhenTheClockAndTheHostHitAtTheSameMoment() {
        RoomService s = service();
        Room room = s.create(TWO_CHILDREN_NO_CLOCK);
        s.join(room.code(), "Ania");
        s.start(room.code(), room.record.hostToken);
        s.endTurn(room, 1);
        s.endTurn(room, 1);
        assertEquals(2, room.game.state().turn);
        assertEquals(1, room.record.turns.size());
    }

    @Test
    void theClockEndsTheTurn() throws Exception {
        RoomService s = service();
        Room room = s.create(new RoomSettings(30, 10, TWO_CHILDREN_NO_CLOCK.seats(), Controller.AI_BEGINNER));
        s.join(room.code(), "Ania");
        s.start(room.code(), room.record.hostToken);
        assertNotNull(room.turnEndsAt());
        assertNotNull(room.timer);
        assertTrue(room.timer.getDelay(java.util.concurrent.TimeUnit.SECONDS) > 25);
        room.timer.cancel(false);
        // run what the clock would run
        s.endTurn(room, 1);
        assertEquals(2, room.game.state().turn);
        assertNotNull(room.timer, "a new clock starts for turn 2");
        s.stop();
    }

    @Test
    void aSavedGameComesBackExactlyAfterARestart() throws Exception {
        RoomService s = service();
        Room room = s.create(TWO_CHILDREN_NO_CLOCK);
        Player ania = s.join(room.code(), "Ania");
        Player bartek = s.join(room.code(), "Bartek");
        s.start(room.code(), room.record.hostToken);
        for (int t = 0; t < 4; t++) {
            s.submitOrders(room.code(), ania.token(), List.of(new Order.SetTax(t % 2 == 0 ? TaxRate.HIGH : TaxRate.LOW)));
            s.ready(room.code(), ania.token(), true);
            s.ready(room.code(), bartek.token(), true);
        }
        assertTrue(Files.exists(dir.resolve(room.code() + ".json")));

        RoomService restarted = service();
        Room back = restarted.room(room.code());
        assertEquals(RoomRecord.Status.PLAYING, back.record.status);
        assertEquals(room.game.state().turn, back.game.state().turn);
        for (Kingdom k : room.game.state().kingdoms.values()) {
            Kingdom k2 = back.game.state().kingdom(k.id);
            assertEquals(k.gold, k2.gold, k.id + " gold");
            assertEquals(k.food, k2.food, k.id + " food");
            assertEquals(k.tax, k2.tax, k.id + " tax");
            assertEquals(k.overlord, k2.overlord, k.id + " overlord");
        }
        assertEquals(room.game.state().units.size(), back.game.state().units.size());
        assertEquals(bartek.name(), back.player(bartek.token()).orElseThrow().name());
    }

    @Test
    void aWholeGamePlaysToTheEnd() {
        RoomService s = service();
        Room room = s.create(TWO_CHILDREN_NO_CLOCK);
        Player ania = s.join(room.code(), "Ania");
        s.start(room.code(), room.record.hostToken);
        for (int t = 0; t < 10 && room.record.status == RoomRecord.Status.PLAYING; t++) {
            s.hostEndTurn(room.code(), room.record.hostToken);
        }
        assertEquals(RoomRecord.Status.FINISHED, room.record.status);
        assertFalse(room.game.state().winners.isEmpty());
        assertEquals("error.notPlaying",
                assertThrows(GameException.class, () -> s.ready(room.code(), ania.token(), true)).key);
    }
}
