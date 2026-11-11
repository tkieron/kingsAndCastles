package pl.kingscastles.browser;

import org.teavm.jso.JSExport;
import pl.kingscastles.engine.Balance;
import pl.kingscastles.engine.Game;
import pl.kingscastles.engine.model.BuildingType;
import pl.kingscastles.engine.model.Enums.Controller;
import pl.kingscastles.engine.model.Enums.TaxRate;
import pl.kingscastles.engine.model.Enums.TreatyType;
import pl.kingscastles.engine.model.GameState;
import pl.kingscastles.engine.model.Kingdom;
import pl.kingscastles.engine.model.Pos;
import pl.kingscastles.engine.model.Settlement;
import pl.kingscastles.engine.model.Tile;
import pl.kingscastles.engine.model.Treaty;
import pl.kingscastles.engine.model.Unit;
import pl.kingscastles.engine.model.UnitType;
import pl.kingscastles.engine.order.Order;
import pl.kingscastles.engine.order.OrderValidator.Problem;
import pl.kingscastles.engine.turn.ChronicleEvent;
import pl.kingscastles.engine.turn.Scoring;
import pl.kingscastles.engine.turn.TurnReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The game engine for the browser (compiled to JavaScript by TeaVM), so the game can be played with no server:
 * the room logic lives in the client (client/src/local), the rules and the computer kings run here.
 * Everything goes in and out as JSON text in the same shapes the server uses (see server/.../web/Views.java).
 */
public final class Engine {
    private static final String TERRAIN_CODES = "MFHARL";

    private static Game game;
    private static TurnReport lastReport;

    private Engine() {
    }

    public static void main(String[] args) {
        // nothing to do on load; the client calls the exported functions
    }

    /** Same content as GET /api/rules. */
    @JSExport
    public static String rules() {
        List<Object> buildings = new ArrayList<>();
        for (BuildingType b : BuildingType.values()) {
            buildings.add(Json.obj("type", b, "cost", b.cost, "place", b.place));
        }
        List<Object> units = new ArrayList<>();
        for (UnitType u : UnitType.values()) {
            units.add(Json.obj("type", u, "cost", u.cost, "upkeep", u.upkeep, "attack", u.attack,
                    "defense", u.defense, "move", u.move, "needsBarracks", u.needsBarracks));
        }
        List<Object> kingdoms = new ArrayList<>();
        for (Game.KingdomPreset p : Game.PRESETS) {
            kingdoms.add(Json.obj("id", p.id(), "name", p.name(), "color", p.color(), "castle", p.castle(),
                    "village", p.village()));
        }
        return Json.write(Json.obj("buildings", buildings, "units", units, "kingdoms", kingdoms,
                "balance", balance(new Balance())));
    }

    static Map<String, Object> balance(Balance b) {
        return Json.obj(
                "actionsPerTurn", b.actionsPerTurn,
                "startGold", b.startGold,
                "startFood", b.startFood,
                "startCastleFamilies", b.startCastleFamilies,
                "startVillageFamilies", b.startVillageFamilies,
                "newVillageFamilies", b.newVillageFamilies,
                "castleIncome", b.castleIncome,
                "forestGold", b.forestGold,
                "goldMineIncome", b.goldMineIncome,
                "marketIncome", b.marketIncome,
                "tradeDealIncome", b.tradeDealIncome,
                "interestPer100", b.interestPer100,
                "interestMax", b.interestMax,
                "sadTaxPenalty", b.sadTaxPenalty,
                "meadowFood", b.meadowFood,
                "riverFood", b.riverFood,
                "lakeFood", b.lakeFood,
                "farmFood", b.farmFood,
                "growthSurplus", b.growthSurplus,
                "loanAmount", b.loanAmount,
                "loanTotal", b.loanTotal,
                "loanInstallment", b.loanInstallment,
                "foodPriceStart", b.foodPriceStart,
                "foodPriceMin", b.foodPriceMin,
                "foodPriceMax", b.foodPriceMax,
                "priceStepVolume", b.priceStepVolume,
                "peaceTurns", b.peaceTurns,
                "castleProtectedUntilTurn", b.castleProtectedUntilTurn,
                "villageLoot", b.villageLoot,
                "vassalTribute", b.vassalTribute,
                "freedomPrice", b.freedomPrice,
                "pactTurns", b.pactTurns,
                "breakDelayTurns", b.breakDelayTurns,
                "brokenPromiseMarkTurns", b.brokenPromiseMarkTurns,
                "maxAllianceSize", b.maxAllianceSize,
                "territoryRadius", b.territoryRadius,
                "settlerMinDistance", b.settlerMinDistance,
                "ruinsGoldMin", b.ruinsGoldMin,
                "ruinsGoldMax", b.ruinsGoldMax,
                "helpEveryTurns", b.helpEveryTurns,
                "helpGold", b.helpGold,
                "eventChancePercent", b.eventChancePercent,
                "palaceInstallment", b.palaceInstallment,
                "palaceInstallments", b.palaceInstallments,
                "economicWinGold", b.economicWinGold,
                "militaryWinKingdoms", b.militaryWinKingdoms,
                "idleTurnsBeforeAi", b.idleTurnsBeforeAi,
                "prestigeGoldPer", b.prestigeGoldPer,
                "prestigePerFamily", b.prestigePerFamily,
                "prestigePerBuilding", b.prestigePerBuilding,
                "prestigePerSettlement", b.prestigePerSettlement,
                "prestigeHappy", b.prestigeHappy,
                "prestigePerAlly", b.prestigePerAlly,
                "prestigePalace", b.prestigePalace,
                "prestigeBrokenPromise", b.prestigeBrokenPromise);
    }

    /**
     * Starts a new game. {@code setupJson}: {"seed":123,"maxTurns":20,"seats":[{"name":"Ania","controller":"HUMAN"},…]}
     * with seven seats in kingdom order.
     */
    @JSExport
    public static void newGame(String setupJson) {
        Map<?, ?> m = (Map<?, ?>) Json.read(setupJson);
        List<Game.Seat> seats = new ArrayList<>();
        for (Object o : (List<?>) m.get("seats")) {
            Map<?, ?> seat = (Map<?, ?>) o;
            seats.add(new Game.Seat((String) seat.get("name"), Controller.valueOf((String) seat.get("controller"))));
        }
        long seed = (long) num(m.get("seed"));
        game = Game.create(new Game.Setup(seed, (int) num(m.get("maxTurns")), seats, Controller.AI_BEGINNER,
                new Balance()));
        lastReport = null;
    }

    /** Replaces a kingdom's orders; returns the problems as JSON (empty list = accepted). */
    @JSExport
    public static String submit(String kingdom, String ordersJson) {
        List<Order> orders = new ArrayList<>();
        try {
            for (Object o : (List<?>) Json.read(ordersJson)) {
                orders.add(order((Map<?, ?>) o));
            }
        } catch (RuntimeException e) {
            return Json.write(List.of(Json.obj("orderIndex", -1, "key", "error.badRequest")));
        }
        List<Object> out = new ArrayList<>();
        for (Problem p : game.submit(kingdom, orders)) {
            out.add(Json.obj("orderIndex", p.orderIndex(), "key", p.key()));
        }
        return Json.write(out);
    }

    @JSExport
    public static void markPresent(String kingdom) {
        game.markPresent(kingdom);
    }

    /** Ends the turn: computer kings decide, then everything is carried out. */
    @JSExport
    public static void endTurn() {
        lastReport = game.endTurn();
    }

    /** Kingdoms that the turn waits for: children not on autopilot. */
    @JSExport
    public static String waitingFor() {
        List<String> out = new ArrayList<>();
        for (Kingdom k : game.state().kingdoms.values()) {
            if (k.isHuman() && !k.autopilot) {
                out.add(k.id);
            }
        }
        return Json.write(out);
    }

    @JSExport
    public static int turn() {
        return game.state().turn;
    }

    @JSExport
    public static boolean finished() {
        return game.state().finished;
    }

    /** The children's orders this turn, {"fox":[…]}, so a saved game can be replayed exactly like the server does. */
    @JSExport
    public static String humanOrders() {
        Map<String, Object> out = Json.obj();
        for (Kingdom k : game.state().kingdoms.values()) {
            List<Order> o = game.ordersOf(k.id);
            if (k.isHuman() && !o.isEmpty()) {
                out.put(k.id, orders(o));
            }
        }
        return Json.write(out);
    }

    @JSExport
    public static String present() {
        return Json.write(new ArrayList<>(game.presentPlayers()));
    }

    /**
     * The game as one kingdom sees it (null = everyone's view, no secrets), like ViewBuilder.state on the server.
     * Room fields (code, status, timer, ready) are added by the client.
     */
    @JSExport
    public static String view(String me) {
        GameState s = game.state();
        List<Object> kingdoms = new ArrayList<>();
        for (Kingdom k : s.kingdoms.values()) {
            boolean mine = k.id.equals(me);
            kingdoms.add(Json.obj("id", k.id, "name", k.name, "color", k.color, "playerName", k.playerName,
                    "controller", k.controller, "autopilot", k.autopilot, "overlord", k.overlord, "mood", k.mood,
                    "prestige", Scoring.prestige(s, k.id), "palaceDone", k.palaceDone, "palacePaid", k.palacePaid,
                    "promiseBreaker", k.brokenPromiseUntil >= s.turn,
                    "gold", mine ? k.gold : null, "food", mine ? k.food : null,
                    "loanLeft", mine ? k.loanLeft : null, "tax", mine ? k.tax : null));
        }
        List<Object> settlements = new ArrayList<>();
        for (Settlement st : s.settlements) {
            settlements.add(Json.obj("id", st.id, "name", st.name, "pos", st.pos, "castle", st.castle,
                    "owner", st.owner, "families", st.families, "capacity", st.capacity(),
                    "buildings", new ArrayList<>(st.buildings)));
        }
        List<Object> units = new ArrayList<>();
        for (Unit u : s.units) {
            units.add(Json.obj("id", u.id, "owner", u.owner, "type", u.type, "pos", u.pos));
        }
        List<Object> treaties = new ArrayList<>();
        for (Treaty t : s.treaties) {
            if (t.activeIn(s.turn)) {
                treaties.add(Json.obj("type", t.type, "a", t.a, "b", t.b, "signedTurn", t.signedTurn,
                        "endsAfterTurn", t.endsAfterTurn));
            }
        }
        List<Object> proposals = new ArrayList<>();
        for (GameState.Proposal p : s.proposals) {
            if (me != null && (p.from().equals(me) || p.to().equals(me))) {
                proposals.add(Json.obj("from", p.from(), "to", p.to(), "type", p.type()));
            }
        }
        return Json.write(Json.obj("turn", s.turn, "maxTurns", s.maxTurns, "foodPrice", s.foodPrice, "me", me,
                "map", map(s), "kingdoms", kingdoms, "settlements", settlements, "units", units,
                "treaties", treaties, "proposals", proposals,
                "myOrders", me == null ? List.of() : orders(game.ordersOf(me)),
                "finished", s.finished, "winners", new ArrayList<>(s.winners), "winReason", s.winReason,
                "report", lastReport == null ? null : report(lastReport, me)));
    }

    private static Map<String, Object> map(GameState s) {
        List<String> rows = new ArrayList<>();
        List<Object> things = new ArrayList<>();
        List<Pos> ruins = new ArrayList<>();
        for (int y = 0; y < s.map.height; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < s.map.width; x++) {
                Tile t = s.map.tile(x, y);
                row.append(TERRAIN_CODES.charAt(t.terrain.ordinal()));
                if (t.improvement != null) {
                    things.add(Json.obj("pos", new Pos(x, y), "building", t.improvement));
                }
                if (t.ruins) {
                    ruins.add(new Pos(x, y));
                }
            }
            rows.add(row.toString());
        }
        return Json.obj("width", s.map.width, "height", s.map.height, "rows", rows, "improvements", things,
                "ruins", ruins);
    }

    private static Map<String, Object> report(TurnReport r, String me) {
        List<Object> events = new ArrayList<>();
        for (ChronicleEvent e : r.events()) {
            if (me == null ? e.visibleTo().isEmpty() : e.visibleFor(me)) {
                events.add(Json.obj("phase", e.phase(), "key", e.key(), "visibleTo", e.visibleTo(),
                        "params", e.params()));
            }
        }
        List<Object> ledger = new ArrayList<>();
        if (me != null) {
            for (TurnReport.LedgerLine l : r.ledgers().getOrDefault(me, List.of())) {
                ledger.add(Json.obj("key", l.key(), "gold", l.gold(), "params", l.params()));
            }
        }
        return Json.obj("turn", r.turn(), "events", events, "ledger", ledger, "prestige", r.prestige());
    }

    // ------------------------------------------------------------------ orders <-> JSON (names as in OrderJson)

    static List<Object> orders(List<Order> orders) {
        List<Object> out = new ArrayList<>();
        for (Order o : orders) {
            out.add(order(o));
        }
        return out;
    }

    static Map<String, Object> order(Order o) {
        return switch (o) {
            case Order.SetTax t -> Json.obj("order", "setTax", "rate", t.rate());
            case Order.Build b -> Json.obj("order", "build", "settlementId", b.settlementId(), "building", b.building());
            case Order.BuildOnTile b -> Json.obj("order", "buildOnTile", "pos", b.pos(), "building", b.building());
            case Order.Recruit r -> Json.obj("order", "recruit", "settlementId", r.settlementId(), "unit", r.unit());
            case Order.Move m -> Json.obj("order", "move", "unitId", m.unitId(), "path", m.path());
            case Order.Attack a -> Json.obj("order", "attack", "unitId", a.unitId(), "target", a.target());
            case Order.FoundVillage f -> Json.obj("order", "foundVillage", "unitId", f.unitId(), "name", f.name());
            case Order.Gift g -> Json.obj("order", "gift", "to", g.to(), "gold", g.gold(), "food", g.food());
            case Order.MarketTrade m -> Json.obj("order", "market", "buyFood", m.buyFood(), "sellFood", m.sellFood());
            case Order.TakeLoan l -> Json.obj("order", "takeLoan");
            case Order.BuyFreedom f -> Json.obj("order", "buyFreedom");
            case Order.ProposeTreaty p -> Json.obj("order", "proposeTreaty", "to", p.to(), "type", p.type());
            case Order.AcceptTreaty a -> Json.obj("order", "acceptTreaty", "from", a.from(), "type", a.type());
            case Order.RejectTreaty r -> Json.obj("order", "rejectTreaty", "from", r.from(), "type", r.type());
            case Order.BreakTreaty b -> Json.obj("order", "breakTreaty", "with", b.with(), "type", b.type());
        };
    }

    static Order order(Map<?, ?> m) {
        String kind = (String) m.get("order");
        return switch (kind) {
            case "setTax" -> new Order.SetTax(TaxRate.valueOf(str(m, "rate")));
            case "build" -> new Order.Build(integer(m, "settlementId"), BuildingType.valueOf(str(m, "building")));
            case "buildOnTile" -> new Order.BuildOnTile(pos(m.get("pos")), BuildingType.valueOf(str(m, "building")));
            case "recruit" -> new Order.Recruit(integer(m, "settlementId"), UnitType.valueOf(str(m, "unit")));
            case "move" -> {
                List<Pos> path = new ArrayList<>();
                for (Object p : (List<?>) m.get("path")) {
                    path.add(pos(p));
                }
                yield new Order.Move(integer(m, "unitId"), path);
            }
            case "attack" -> new Order.Attack(integer(m, "unitId"), pos(m.get("target")));
            case "foundVillage" -> new Order.FoundVillage(integer(m, "unitId"), str(m, "name"));
            case "gift" -> new Order.Gift(str(m, "to"), integer(m, "gold"), integer(m, "food"));
            case "market" -> new Order.MarketTrade(integer(m, "buyFood"), integer(m, "sellFood"));
            case "takeLoan" -> new Order.TakeLoan();
            case "buyFreedom" -> new Order.BuyFreedom();
            case "proposeTreaty" -> new Order.ProposeTreaty(str(m, "to"), TreatyType.valueOf(str(m, "type")));
            case "acceptTreaty" -> new Order.AcceptTreaty(str(m, "from"), TreatyType.valueOf(str(m, "type")));
            case "rejectTreaty" -> new Order.RejectTreaty(str(m, "from"), TreatyType.valueOf(str(m, "type")));
            case "breakTreaty" -> new Order.BreakTreaty(str(m, "with"), TreatyType.valueOf(str(m, "type")));
            default -> throw new IllegalArgumentException("Unknown order " + kind);
        };
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return (String) v;
    }

    private static int integer(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? 0 : (int) num(v);
    }

    private static double num(Object v) {
        return ((Number) v).doubleValue();
    }

    private static Pos pos(Object v) {
        Map<?, ?> m = (Map<?, ?>) v;
        return new Pos(integer(m, "x"), integer(m, "y"));
    }
}
