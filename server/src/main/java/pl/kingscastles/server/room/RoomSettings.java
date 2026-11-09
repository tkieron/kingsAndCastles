package pl.kingscastles.server.room;

import pl.kingscastles.engine.model.Enums.Controller;

import java.util.List;

/**
 * What the host chooses in the lobby.
 *
 * @param turnSeconds 30, 60, 90, 120, 180, or 0 for "no limit" (the turn ends when everyone is ready)
 * @param maxTurns    10 to 30
 * @param seats       seven entries in kingdom order: HUMAN = a seat for a child, or a computer level.
 *                    A child seat nobody took is played by the computer at {@code emptySeatLevel}.
 * @param emptySeatLevel computer level for child seats left empty when the game starts
 */
public record RoomSettings(int turnSeconds, int maxTurns, List<Controller> seats, Controller emptySeatLevel) {

    public static final List<Integer> TURN_LENGTHS = List.of(0, 30, 60, 90, 120, 180);

    public RoomSettings {
        if (!TURN_LENGTHS.contains(turnSeconds)) {
            throw new IllegalArgumentException("Turn length must be one of " + TURN_LENGTHS);
        }
        if (maxTurns < 10 || maxTurns > 30) {
            throw new IllegalArgumentException("A game has 10 to 30 turns");
        }
        if (seats == null || seats.size() != 7) {
            throw new IllegalArgumentException("Seven seats, one per kingdom");
        }
        if (emptySeatLevel == null || emptySeatLevel == Controller.HUMAN) {
            emptySeatLevel = Controller.AI_BEGINNER;
        }
        seats = List.copyOf(seats);
    }

    public static RoomSettings defaults() {
        return new RoomSettings(60, 20, List.of(
                Controller.HUMAN, Controller.HUMAN, Controller.HUMAN,
                Controller.AI_PRO, Controller.AI_BEGINNER, Controller.AI_ADVANCED, Controller.AI_ADVANCED),
                Controller.AI_BEGINNER);
    }
}
