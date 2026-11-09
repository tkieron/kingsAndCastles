package pl.kingscastles.server.web;

import pl.kingscastles.engine.model.BuildingType;
import pl.kingscastles.engine.model.Enums.Controller;
import pl.kingscastles.engine.model.Enums.Mood;
import pl.kingscastles.engine.model.Enums.TaxRate;
import pl.kingscastles.engine.model.Enums.TreatyType;
import pl.kingscastles.engine.model.Pos;
import pl.kingscastles.engine.model.UnitType;
import pl.kingscastles.engine.order.Order;
import pl.kingscastles.engine.turn.ChronicleEvent;
import pl.kingscastles.engine.turn.TurnReport;
import pl.kingscastles.server.room.RoomRecord;
import pl.kingscastles.server.room.RoomSettings;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** What the client receives. Only the player's own gold, food and ledger are shown; everything else is public. */
public final class Views {
    private Views() {
    }

    public record Created(String code, String hostToken) {
    }

    public record Joined(String token, int seat, String kingdom) {
    }

    public record Error(String error) {
    }

    // ------------------------------------------------------------------ lobby

    public record Lobby(String code, RoomRecord.Status status, RoomSettings settings, List<Seat> seats) {
    }

    /** One of the seven kingdoms in the lobby: who sits there (a child's name, or nobody yet). */
    public record Seat(int seat, String kingdom, String name, String color, Controller controller, String player) {
    }

    // ------------------------------------------------------------------ game

    public record State(String code,
                        RoomRecord.Status status,
                        int turn,
                        int maxTurns,
                        Long turnEndsAt,
                        int turnSeconds,
                        int foodPrice,
                        String me,
                        MapView map,
                        List<KingdomView> kingdoms,
                        List<SettlementView> settlements,
                        List<UnitView> units,
                        List<TreatyView> treaties,
                        List<ProposalView> proposals,
                        List<Order> myOrders,
                        Set<String> ready,
                        boolean finished,
                        List<String> winners,
                        String winReason,
                        ReportView report) {
    }

    /**
     * Terrain as one string per row: M meadow, F forest, H hills, A mountain, R river, L lake.
     * Tile buildings (gold mine, bridge) and ruins are listed separately.
     */
    public record MapView(int width, int height, List<String> rows, List<TileThing> improvements, List<Pos> ruins) {
    }

    public record TileThing(Pos pos, BuildingType building) {
    }

    /** gold, food, loanLeft and tax are filled in only for the player's own kingdom. */
    public record KingdomView(String id, String name, String color, String playerName, Controller controller,
                              boolean autopilot, String overlord, Mood mood, int prestige, boolean palaceDone,
                              int palacePaid, boolean promiseBreaker,
                              Integer gold, Integer food, Integer loanLeft, TaxRate tax) {
    }

    public record SettlementView(int id, String name, Pos pos, boolean castle, String owner, int families,
                                 int capacity, Set<BuildingType> buildings) {
    }

    public record UnitView(int id, String owner, UnitType type, Pos pos) {
    }

    public record TreatyView(TreatyType type, String a, String b, int signedTurn, int endsAfterTurn) {
    }

    public record ProposalView(String from, String to, TreatyType type) {
    }

    /** Last turn's Chronicle (only events this player may see) and this player's Ledger. */
    public record ReportView(int turn, List<ChronicleEvent> events, List<TurnReport.LedgerLine> ledger,
                             Map<String, Integer> prestige) {
    }
}
