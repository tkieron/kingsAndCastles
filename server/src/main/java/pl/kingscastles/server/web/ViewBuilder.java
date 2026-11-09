package pl.kingscastles.server.web;

import pl.kingscastles.engine.Game;
import pl.kingscastles.engine.model.GameState;
import pl.kingscastles.engine.model.Kingdom;
import pl.kingscastles.engine.model.Pos;
import pl.kingscastles.engine.model.Terrain;
import pl.kingscastles.engine.model.Tile;
import pl.kingscastles.engine.order.Order;
import pl.kingscastles.engine.turn.Scoring;
import pl.kingscastles.engine.turn.TurnReport;
import pl.kingscastles.server.room.Player;
import pl.kingscastles.server.room.Room;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Turns a room into what one player may see. Call it while holding the room's lock. */
public final class ViewBuilder {
    private static final String TERRAIN_CODES = "MFHARL";

    static {
        if (Terrain.values().length != TERRAIN_CODES.length()) {
            throw new IllegalStateException("Every terrain needs a map letter");
        }
    }

    private ViewBuilder() {
    }

    public static Views.Lobby lobby(Room room) {
        List<Views.Seat> seats = new ArrayList<>();
        var plan = room.record.settings.seats();
        for (int i = 0; i < plan.size(); i++) {
            int idx = i;
            Game.KingdomPreset preset = Game.PRESETS.get(i);
            String player = room.record.players.stream().filter(p -> p.seat() == idx)
                    .map(Player::name).findFirst().orElse(null);
            seats.add(new Views.Seat(i, preset.id(), preset.name(), preset.color(), plan.get(i), player));
        }
        return new Views.Lobby(room.code(), room.record.status, room.record.settings, seats);
    }

    /** @param me the player's kingdom, or null for the host's screen (sees no secrets) */
    public static Views.State state(Room room, String me) {
        Game game = room.game().orElseThrow();
        GameState s = game.state();

        List<Views.KingdomView> kingdoms = new ArrayList<>();
        for (Kingdom k : s.kingdoms.values()) {
            boolean mine = k.id.equals(me);
            kingdoms.add(new Views.KingdomView(k.id, k.name, k.color, k.playerName, k.controller, k.autopilot,
                    k.overlord, k.mood, Scoring.prestige(s, k.id), k.palaceDone, k.palacePaid,
                    k.brokenPromiseUntil >= s.turn,
                    mine ? k.gold : null, mine ? k.food : null, mine ? k.loanLeft : null, mine ? k.tax : null));
        }
        List<Views.SettlementView> settlements = s.settlements.stream()
                .map(st -> new Views.SettlementView(st.id, st.name, st.pos, st.castle, st.owner, st.families,
                        st.capacity(), Set.copyOf(st.buildings)))
                .toList();
        List<Views.UnitView> units = s.units.stream()
                .map(u -> new Views.UnitView(u.id, u.owner, u.type, u.pos)).toList();
        List<Views.TreatyView> treaties = s.treaties.stream()
                .filter(t -> t.activeIn(s.turn))
                .map(t -> new Views.TreatyView(t.type, t.a, t.b, t.signedTurn, t.endsAfterTurn)).toList();
        List<Views.ProposalView> proposals = s.proposals.stream()
                .filter(p -> me != null && (p.from().equals(me) || p.to().equals(me)))
                .map(p -> new Views.ProposalView(p.from(), p.to(), p.type())).toList();
        List<Order> myOrders = me == null ? List.of() : game.ordersOf(me);

        return new Views.State(room.code(), room.record.status, s.turn, s.maxTurns, room.turnEndsAt(),
                room.record.settings.turnSeconds(), s.foodPrice, me, map(s), kingdoms, settlements, units,
                treaties, proposals, myOrders, room.ready(), s.finished, List.copyOf(s.winners), s.winReason,
                room.lastReport().map(r -> report(r, me)).orElse(null));
    }

    static Views.MapView map(GameState s) {
        List<String> rows = new ArrayList<>();
        List<Views.TileThing> things = new ArrayList<>();
        List<Pos> ruins = new ArrayList<>();
        for (int y = 0; y < s.map.height; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < s.map.width; x++) {
                Tile t = s.map.tile(x, y);
                row.append(TERRAIN_CODES.charAt(t.terrain.ordinal()));
                if (t.improvement != null) {
                    things.add(new Views.TileThing(new Pos(x, y), t.improvement));
                }
                if (t.ruins) {
                    ruins.add(new Pos(x, y));
                }
            }
            rows.add(row.toString());
        }
        return new Views.MapView(s.map.width, s.map.height, rows, things, ruins);
    }

    static Views.ReportView report(TurnReport r, String me) {
        var events = r.events().stream().filter(e -> me == null ? e.visibleTo().isEmpty() : e.visibleFor(me)).toList();
        var ledger = me == null ? List.<TurnReport.LedgerLine>of() : r.ledgers().getOrDefault(me, List.of());
        return new Views.ReportView(r.turn(), events, ledger, Map.copyOf(r.prestige()));
    }
}
