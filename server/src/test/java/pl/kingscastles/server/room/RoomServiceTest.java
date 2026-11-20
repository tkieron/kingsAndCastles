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

    @Test
    void theServerKeepsAtMostSoManyRoomsAndForgetsIdleOnes() throws Exception {
        RoomService s = new RoomService(new RoomStore(dir.toString()), 2, 14);
        s.loadSavedRooms();
        Room a = s.create(TWO_CHILDREN_NO_CLOCK);
        s.create(TWO_CHILDREN_NO_CLOCK);
        assertEquals("error.serverFull", assertThrows(GameException.class, () -> s.create(TWO_CHILDREN_NO_CLOCK)).key);

        long inFifteenDays = System.currentTimeMillis() + java.util.concurrent.TimeUnit.DAYS.toMillis(15);
        assertEquals(2, s.removeIdleRooms(inFifteenDays));
        assertEquals(0, s.roomCount());
        assertFalse(Files.exists(dir.resolve(a.code() + ".json")), "the saved file goes too");
        assertNotNull(s.create(TWO_CHILDREN_NO_CLOCK), "there is room again");
    }

    @Test
    void aRoomThatIsStillPlayedIsNotForgotten() {
        RoomService s = new RoomService(new RoomStore(dir.toString()), 10, 14);
        s.loadSavedRooms();
        Room room = s.create(TWO_CHILDREN_NO_CLOCK);
        long inTenDays = System.currentTimeMillis() + java.util.concurrent.TimeUnit.DAYS.toMillis(10);
        assertEquals(0, s.removeIdleRooms(inTenDays));
        assertSame(room, s.room(room.code()));
    }

    @Test
    void idleRoomsOnTheDiskAreNotLoadedAfterARestart() throws Exception {
        RoomService first = service();
        Room room = first.create(TWO_CHILDREN_NO_CLOCK);
        Path file = dir.resolve(room.code() + ".json");
        String old = Files.readString(file).replaceAll("\"updatedAt\":\\d+", "\"updatedAt\":1")
                .replaceAll("\"createdAt\":\\d+", "\"createdAt\":1");
        Files.writeString(file, old);
        RoomService second = service();
        assertEquals(0, second.roomCount());
        assertFalse(Files.exists(file));
    }

    // ------------------------------------------------------------------ freezing a game

    private static final RoomSettings TWO_CHILDREN_30_SECONDS =
            new RoomSettings(30, 10, TWO_CHILDREN_NO_CLOCK.seats(), Controller.AI_BEGINNER);

    @Test
    void aFrozenGameStopsTheClockAndWaitsUntilSomeoneCarriesOn() {
        RoomService s = service();
        Room room = s.create(TWO_CHILDREN_30_SECONDS);
        Player ania = s.join(room.code(), "Ania");
        Player bartek = s.join(room.code(), "Bartek");
        s.start(room.code(), room.record.hostToken);
        s.submitOrders(room.code(), ania.token(), List.of(new Order.SetTax(TaxRate.HIGH)));
        s.ready(room.code(), bartek.token(), true);
        List<RoomService.Change> changes = new ArrayList<>();
        s.addListener((r, c) -> changes.add(c));

        assertEquals("error.notInRoom", assertThrows(GameException.class, () -> s.pause(room.code(), "stranger", true)).key);
        s.pause(room.code(), ania.token(), true);
        assertTrue(room.paused());
        assertEquals("fox", room.record.pausedBy);
        assertNull(room.turnEndsAt(), "the clock stands still");
        assertNull(room.timer);
        assertTrue(room.ready().isEmpty(), "Bartek presses Ready again after the break");
        assertEquals(List.of(RoomService.Change.PAUSE), changes);

        assertEquals("error.paused", assertThrows(GameException.class, () -> s.ready(room.code(), ania.token(), true)).key);
        assertEquals("error.paused", assertThrows(GameException.class,
                () -> s.submitOrders(room.code(), ania.token(), List.of(new Order.SetTax(TaxRate.LOW)))).key);
        assertEquals("error.paused", assertThrows(GameException.class,
                () -> s.hostEndTurn(room.code(), room.record.hostToken)).key);
        s.endTurn(room, 1);
        assertEquals(1, room.game.state().turn, "a clock that was already running does not end a frozen turn");

        s.pause(room.code(), ania.token(), true);
        assertEquals(1, changes.size(), "freezing a frozen game changes nothing");

        long before = System.currentTimeMillis();
        s.pause(room.code(), room.record.hostToken, false);
        assertFalse(room.paused());
        assertNull(room.record.pausedBy);
        assertTrue(room.turnEndsAt() >= before + 29_000, "the turn gets its full time again");
        assertNotNull(room.timer);
        assertEquals(List.of(new Order.SetTax(TaxRate.HIGH)), room.game.ordersOf("fox"), "Ania's move is still there");
        s.stop();
    }

    @Test
    void aFrozenGameStaysFrozenAfterARestartWithItsOrders() {
        RoomService s = service();
        Room room = s.create(TWO_CHILDREN_30_SECONDS);
        Player ania = s.join(room.code(), "Ania");
        s.start(room.code(), room.record.hostToken);
        s.hostEndTurn(room.code(), room.record.hostToken);
        s.submitOrders(room.code(), ania.token(), List.of(new Order.SetTax(TaxRate.LOW)));
        s.pause(room.code(), room.record.hostToken, true);
        assertEquals(RoomRecord.BY_HOST, room.record.pausedBy);
        s.stop();

        RoomService restarted = service();
        Room back = restarted.room(room.code());
        assertTrue(back.paused());
        assertEquals(2, back.game.state().turn);
        assertNull(back.turnEndsAt());
        assertNull(back.timer);
        assertNull(back.autoPause, "a frozen game needs no automatic pause");
        assertEquals(List.of(new Order.SetTax(TaxRate.LOW)), back.game.ordersOf("fox"));

        restarted.pause(room.code(), ania.token(), false);
        assertNotNull(back.turnEndsAt());
        assertNull(back.record.pausedOrders, "nothing is kept twice once the game runs again");
        restarted.stop();
    }

    @Test
    void aFrozenGameIsKeptLongerThanAnIdleOne() {
        RoomService s = new RoomService(new RoomStore(dir.toString()), 10, 14, 30, 90);
        s.loadSavedRooms();
        Room frozen = s.create(TWO_CHILDREN_30_SECONDS);
        s.join(frozen.code(), "Ania");
        s.start(frozen.code(), frozen.record.hostToken);
        s.pause(frozen.code(), frozen.record.hostToken, true);
        Room idle = s.create(TWO_CHILDREN_NO_CLOCK);

        long day = java.util.concurrent.TimeUnit.DAYS.toMillis(1);
        assertEquals(1, s.removeIdleRooms(System.currentTimeMillis() + 15 * day));
        assertEquals("error.noSuchRoom", assertThrows(GameException.class, () -> s.room(idle.code())).key);
        assertSame(frozen, s.room(frozen.code()), "a two-week break does not lose the game");
        assertEquals(1, s.removeIdleRooms(System.currentTimeMillis() + 31 * day));
        s.stop();
    }

    @Test
    void aTimedGameFreezesItselfWhenEveryoneHasLeft() throws Exception {
        RoomService s = new RoomService(new RoomStore(dir.toString()), 10, 14, 30, 1);
        s.loadSavedRooms();
        Room room = s.create(TWO_CHILDREN_30_SECONDS);
        Player ania = s.join(room.code(), "Ania");
        s.start(room.code(), room.record.hostToken);
        s.connected(room.code());
        s.connected(room.code());
        s.disconnected(room.code());
        assertNull(room.autoPause, "one screen is still here");

        // a reload: the screen goes and comes back within the wait, nothing freezes
        s.disconnected(room.code());
        assertNotNull(room.autoPause);
        s.connected(room.code());
        assertNull(room.autoPause);

        s.disconnected(room.code());
        long until = System.currentTimeMillis() + 5_000;
        while (!room.paused() && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        assertTrue(room.paused());
        assertEquals(RoomRecord.BY_NOBODY, room.record.pausedBy);
        assertNull(room.turnEndsAt());

        // when someone comes back they press Resume, and the clock starts again
        s.connected(room.code());
        assertTrue(room.paused(), "coming back does not start the clock by itself");
        s.pause(room.code(), ania.token(), false);
        assertNotNull(room.turnEndsAt());
        s.stop();
    }

    @Test
    void aGameWithoutAClockNeverFreezesByItself() {
        RoomService s = new RoomService(new RoomStore(dir.toString()), 10, 14, 30, 1);
        s.loadSavedRooms();
        Room room = s.create(TWO_CHILDREN_NO_CLOCK);
        s.join(room.code(), "Ania");
        s.start(room.code(), room.record.hostToken);
        s.connected(room.code());
        s.disconnected(room.code());
        assertNull(room.autoPause, "without a turn clock nothing happens while everyone is away");
        s.stop();
    }

    @Test
    void freezingInALoopIsStoppedSoTheDiskStaysCalm() {
        RoomService s = service();
        Room room = s.create(TWO_CHILDREN_NO_CLOCK);
        Player ania = s.join(room.code(), "Ania");
        s.start(room.code(), room.record.hostToken);
        for (int i = 0; i < RoomService.PAUSES_PER_WINDOW; i++) {
            s.pause(room.code(), ania.token(), i % 2 == 0);
        }
        assertEquals("error.tooFast", assertThrows(GameException.class, () -> s.pause(room.code(), ania.token(), true)).key);
        room.pauseWindowStart -= RoomService.PAUSE_WINDOW_MILLIS + 1;
        s.pause(room.code(), ania.token(), true);
        assertTrue(room.paused(), "a minute later it works again");
        s.stop();
    }

    // ------------------------------------------------------------------ another device

    @Test
    void everyChildGetsTheirOwnReturnCodeAndItBringsThemBackOnAnotherDevice() {
        RoomService s = service();
        Room room = s.create(TWO_CHILDREN_NO_CLOCK);
        Player ania = s.join(room.code(), "Ania");
        Player bartek = s.join(room.code(), "Bartek");
        assertNotNull(ania.resumeCode());
        assertEquals(ResumeCodes.LENGTH, ania.resumeCode().length());
        assertTrue(ania.resumeCode().chars().allMatch(c -> ResumeCodes.ALPHABET.indexOf(c) >= 0));
        assertNotEquals(ania.resumeCode(), bartek.resumeCode());
        assertNotEquals(room.record.hostResumeCode, ania.resumeCode());

        // typed on a phone: small letters, a dash and spaces
        String typed = " " + ania.resumeCode().substring(0, 4).toLowerCase() + "-" + ania.resumeCode().substring(4) + " ";
        RoomService.Resumed back = s.resume(room.code(), typed);
        assertEquals(ania.token(), back.token());
        assertEquals("fox", back.kingdom());
        assertNull(back.hostToken(), "a child does not get the host's buttons");

        assertEquals(bartek.token(), s.resume(room.code(), bartek.resumeCode()).token());
    }

    @Test
    void theHostsCodeGivesTheHostsScreenAndAHostWhoPlaysNeedsOnlyOneCode() {
        RoomService s = service();
        Room room = s.create(TWO_CHILDREN_NO_CLOCK);
        RoomService.Resumed host = s.resume(room.code(), room.record.hostResumeCode);
        assertEquals(room.record.hostToken, host.hostToken());
        assertNull(host.token());

        Player teacher = s.join(room.code(), "Pani Ola", "", 0, room.record.hostToken);
        Player stranger = s.join(room.code(), "Bartek", "", 0, "not-the-host");
        assertTrue(teacher.host());
        assertFalse(stranger.host(), "a wrong host token is not enough");
        RoomService.Resumed both = s.resume(room.code(), teacher.resumeCode());
        assertEquals(teacher.token(), both.token());
        assertEquals(room.record.hostToken, both.hostToken());
    }

    @Test
    void aScreenSeesOnlyItsOwnCodes() {
        RoomService s = service();
        Room room = s.create(TWO_CHILDREN_NO_CLOCK);
        Player ania = s.join(room.code(), "Ania");
        s.join(room.code(), "Bartek");

        RoomService.Codes child = s.resumeCodes(room.code(), ania.token(), null);
        assertEquals(ania.resumeCode(), child.player());
        assertNull(child.host());
        RoomService.Codes child2 = s.resumeCodes(room.code(), ania.token(), "guess");
        assertNull(child2.host(), "a wrong host token shows no host code");

        RoomService.Codes host = s.resumeCodes(room.code(), null, room.record.hostToken);
        assertEquals(room.record.hostResumeCode, host.host());
        assertNull(host.player());

        // a host who joined without the host's screen being tied: asking from a screen with both ties them
        Player host2 = room.record.players.get(0);
        assertFalse(host2.host());
        RoomService.Codes both = s.resumeCodes(room.code(), host2.token(), room.record.hostToken);
        assertEquals(host2.resumeCode(), both.player());
        assertNull(both.host(), "one code is enough");
        assertEquals(room.record.hostToken, s.resume(room.code(), host2.resumeCode()).hostToken());

        assertEquals("error.notInRoom", assertThrows(GameException.class, () -> s.resumeCodes(room.code(), "x", null)).key);
        assertEquals("error.notInRoom", assertThrows(GameException.class, () -> s.resumeCodes(room.code(), null, null)).key);
    }

    @Test
    void guessingIsStoppedAfterTenWrongCodesFromOneDevice() {
        RoomService s = service();
        Room room = s.create(TWO_CHILDREN_NO_CLOCK);
        Player ania = s.join(room.code(), "Ania");
        // not a return code at all: refused, but not counted as a guess
        for (String junk : new String[] {null, "", "abc", "AAAA-AAA1", "x".repeat(40)}) {
            for (int i = 0; i < 20; i++) {
                assertEquals("error.badResumeCode", assertThrows(GameException.class, () -> s.resume(room.code(), junk, "1.1.1.1")).key);
            }
        }
        for (int i = 0; i < ResumeTries.PER_CLIENT; i++) {
            assertEquals("error.badResumeCode", assertThrows(GameException.class, () -> s.resume(room.code(), "AAAA-AAAA", "1.1.1.1")).key);
        }
        assertEquals("error.tooManyTries", assertThrows(GameException.class, () -> s.resume(room.code(), ania.resumeCode(), "1.1.1.1")).key,
                "after ten wrong codes even the right one waits");
        assertEquals(ania.token(), s.resume(room.code(), ania.resumeCode(), "2.2.2.2").token(),
                "another device in the room is not locked out");

        // another room is not blocked either
        Room other = s.create(TWO_CHILDREN_NO_CLOCK);
        Player ola = s.join(other.code(), "Ola");
        assertEquals(ola.token(), s.resume(other.code(), ola.resumeCode(), "1.1.1.1").token());

        // fifteen minutes later the device may try again
        room.resumeTries.windowStart -= ResumeTries.WINDOW_MILLIS + 1;
        assertEquals(ania.token(), s.resume(room.code(), ania.resumeCode(), "1.1.1.1").token());
        assertEquals("error.noSuchRoom", assertThrows(GameException.class, () -> s.resume("999999x", ania.resumeCode())).key);
    }

    @Test
    void manyDevicesTogetherAreStoppedTooSoChangingAddressesDoesNotHelp() {
        RoomService s = service();
        Room room = s.create(TWO_CHILDREN_NO_CLOCK);
        Player ania = s.join(room.code(), "Ania");
        for (int i = 0; i < ResumeTries.PER_ROOM; i++) {
            String device = "10.0.0." + i;
            assertEquals("error.badResumeCode", assertThrows(GameException.class, () -> s.resume(room.code(), "AAAA-AAAA", device)).key);
        }
        assertEquals("error.tooManyTries", assertThrows(GameException.class, () -> s.resume(room.code(), ania.resumeCode(), "10.9.9.9")).key);
    }

    @Test
    void returnCodesStayTheSameAfterARestartAndOldRoomsGetThem() throws Exception {
        RoomService s = service();
        Room room = s.create(TWO_CHILDREN_NO_CLOCK);
        Player ania = s.join(room.code(), "Ania");
        s.start(room.code(), room.record.hostToken);
        s.stop();

        RoomService again = service();
        assertEquals(ania.token(), again.resume(room.code(), ania.resumeCode()).token());
        again.stop();

        // a room saved before return codes existed
        Path file = dir.resolve(room.code() + ".json");
        String old = Files.readString(file).replaceAll(",?\\s*\"(resumeCode|hostResumeCode)\"\\s*:\\s*\"[A-Z0-9]+\"", "");
        assertFalse(old.contains("esumeCode\":\""), old);
        Files.writeString(file, old);
        RoomService third = service();
        Room loaded = third.room(room.code());
        Player p = loaded.record.players.get(0);
        assertNotNull(p.resumeCode());
        assertNotNull(loaded.record.hostResumeCode);
        assertEquals(ania.token(), third.resume(room.code(), p.resumeCode()).token());
        third.stop();
        RoomService fourth = service();
        assertEquals(p.resumeCode(), fourth.room(room.code()).record.players.get(0).resumeCode(), "the new code was saved");
        fourth.stop();
    }
}
