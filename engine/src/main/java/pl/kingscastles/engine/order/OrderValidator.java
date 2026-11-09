package pl.kingscastles.engine.order;

import pl.kingscastles.engine.model.BuildingType;
import pl.kingscastles.engine.model.GameState;
import pl.kingscastles.engine.model.Kingdom;
import pl.kingscastles.engine.model.Pos;
import pl.kingscastles.engine.model.Settlement;
import pl.kingscastles.engine.model.Terrain;
import pl.kingscastles.engine.model.Territory;
import pl.kingscastles.engine.model.Tile;
import pl.kingscastles.engine.model.Unit;
import pl.kingscastles.engine.model.UnitType;
import pl.kingscastles.engine.order.Order.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Checks a kingdom's orders when they are submitted, so the player sees mistakes at once.
 * Errors are translation keys with the index of the order they belong to.
 * The resolver checks again when the turn ends, because the world may change in earlier phases.
 */
public final class OrderValidator {

    public record Problem(int orderIndex, String key) {
    }

    private OrderValidator() {
    }

    public static List<Problem> validate(GameState s, String kingdomId, List<Order> orders) {
        List<Problem> out = new ArrayList<>();
        Kingdom k = s.kingdom(kingdomId);
        if (k == null) {
            out.add(new Problem(-1, "error.unknownKingdom"));
            return out;
        }
        Territory territory = Territory.of(s);
        Set<Integer> builtIn = new HashSet<>();
        Set<Integer> recruitedIn = new HashSet<>();
        Set<Pos> builtOn = new HashSet<>();
        Set<Integer> moved = new HashSet<>();
        Set<Integer> attacked = new HashSet<>();
        Set<String> once = new HashSet<>();
        int gold = 0;
        int food = 0;
        boolean loan = false;

        for (int i = 0; i < orders.size(); i++) {
            Order o = orders.get(i);
            String err = check(s, k, territory, o, builtIn, recruitedIn, builtOn, moved, attacked, once);
            if (err != null) {
                out.add(new Problem(i, err));
                continue;
            }
            if (o instanceof TakeLoan) {
                loan = true;
            }
            gold += o.goldCost(s);
            food += o.foodCost();
        }
        int available = k.gold + (loan ? s.balance.loanAmount : 0);
        if (gold > available) {
            out.add(new Problem(-1, "error.notEnoughGold"));
        }
        if (food > k.food) {
            out.add(new Problem(-1, "error.notEnoughFood"));
        }
        return out;
    }

    private static String check(GameState s, Kingdom k, Territory territory, Order o,
                                Set<Integer> builtIn, Set<Integer> recruitedIn, Set<Pos> builtOn,
                                Set<Integer> moved, Set<Integer> attacked, Set<String> once) {
        return switch (o) {
            case SetTax t -> once.add("tax") ? null : "error.duplicate";
            case Build b -> checkBuild(s, k, b, builtIn);
            case BuildOnTile b -> checkBuildOnTile(s, k, territory, b, builtOn);
            case Recruit r -> {
                Optional<Settlement> st = s.settlement(r.settlementId());
                if (st.isEmpty() || !st.get().owner.equals(k.id)) {
                    yield "error.notYourSettlement";
                }
                if (r.unit().needsBarracks && !st.get().buildings.contains(BuildingType.BARRACKS)) {
                    yield "error.needsBarracks";
                }
                yield recruitedIn.add(r.settlementId()) ? null : "error.oneRecruitPerSettlement";
            }
            case Move m -> {
                Optional<Unit> u = s.unit(m.unitId());
                if (u.isEmpty() || !u.get().owner.equals(k.id)) {
                    yield "error.notYourUnit";
                }
                if (!moved.add(m.unitId())) {
                    yield "error.duplicate";
                }
                yield checkPath(s, u.get(), m.path());
            }
            case Attack a -> {
                Optional<Unit> u = s.unit(a.unitId());
                if (u.isEmpty() || !u.get().owner.equals(k.id)) {
                    yield "error.notYourUnit";
                }
                if (!u.get().type.canFight()) {
                    yield "error.cannotFight";
                }
                if (!s.map.inBounds(a.target())) {
                    yield "error.outsideMap";
                }
                yield attacked.add(a.unitId()) ? null : "error.duplicate";
            }
            case FoundVillage f -> {
                Optional<Unit> u = s.unit(f.unitId());
                if (u.isEmpty() || !u.get().owner.equals(k.id)) {
                    yield "error.notYourUnit";
                }
                yield u.get().type == UnitType.SETTLER ? null : "error.notASettler";
            }
            case Gift g -> {
                if (s.kingdom(g.to()) == null || g.to().equals(k.id)) {
                    yield "error.unknownKingdom";
                }
                yield g.gold() < 0 || g.food() < 0 || g.gold() + g.food() == 0 ? "error.badAmount" : null;
            }
            case MarketTrade m -> {
                if (m.buyFood() < 0 || m.sellFood() < 0 || m.buyFood() + m.sellFood() == 0) {
                    yield "error.badAmount";
                }
                yield once.add("market") ? null : "error.duplicate";
            }
            case TakeLoan l -> {
                if (k.loanLeft > 0) {
                    yield "error.loanAlreadyTaken";
                }
                yield once.add("loan") ? null : "error.duplicate";
            }
            case BuyFreedom f -> k.overlord == null ? "error.notAVassal" : (once.add("freedom") ? null : "error.duplicate");
            case ProposeTreaty p -> {
                if (s.kingdom(p.to()) == null || p.to().equals(k.id)) {
                    yield "error.unknownKingdom";
                }
                yield s.hasTreaty(k.id, p.to(), p.type()) ? "error.treatyExists" : null;
            }
            case AcceptTreaty a -> s.proposals.stream()
                    .anyMatch(p -> p.from().equals(a.from()) && p.to().equals(k.id) && p.type() == a.type())
                    ? null : "error.noSuchProposal";
            case BreakTreaty b -> s.hasTreaty(k.id, b.with(), b.type()) ? null : "error.noSuchTreaty";
        };
    }

    private static String checkBuild(GameState s, Kingdom k, Build b, Set<Integer> builtIn) {
        Optional<Settlement> opt = s.settlement(b.settlementId());
        if (opt.isEmpty() || !opt.get().owner.equals(k.id)) {
            return "error.notYourSettlement";
        }
        Settlement st = opt.get();
        BuildingType t = b.building();
        if (t.onTile()) {
            return "error.buildOnTile";
        }
        if (t.place == BuildingType.Place.VILLAGE && st.castle) {
            return "error.villageOnly";
        }
        if (t.place == BuildingType.Place.CASTLE && !st.castle) {
            return "error.castleOnly";
        }
        if (t == BuildingType.GREAT_PALACE ? k.palaceDone : st.buildings.contains(t)) {
            return "error.alreadyBuilt";
        }
        if (t == BuildingType.FARM && st.pos.neighbours().stream()
                .noneMatch(p -> s.map.inBounds(p) && s.map.tile(p).terrain == Terrain.MEADOW)) {
            return "error.farmNeedsMeadow";
        }
        return builtIn.add(st.id) ? null : "error.oneBuildPerSettlement";
    }

    private static String checkBuildOnTile(GameState s, Kingdom k, Territory territory, BuildOnTile b, Set<Pos> builtOn) {
        if (!b.building().onTile()) {
            return "error.buildInSettlement";
        }
        if (!s.map.inBounds(b.pos()) || !territory.owns(k.id, b.pos())) {
            return "error.notYourLand";
        }
        Tile tile = s.map.tile(b.pos());
        Terrain need = b.building().place == BuildingType.Place.HILLS_TILE ? Terrain.HILLS : Terrain.RIVER;
        if (tile.terrain != need) {
            return b.building() == BuildingType.GOLD_MINE ? "error.mineNeedsHills" : "error.bridgeNeedsRiver";
        }
        if (tile.improvement != null || s.settlementAt(b.pos()).isPresent()) {
            return "error.alreadyBuilt";
        }
        return builtOn.add(b.pos()) ? null : "error.duplicate";
    }

    /** A unit may always take one step; further steps must fit into its movement points. */
    static String checkPath(GameState s, Unit u, List<Pos> path) {
        if (path.isEmpty()) {
            return "error.emptyPath";
        }
        Pos at = u.pos;
        int points = u.type.move;
        for (int i = 0; i < path.size(); i++) {
            Pos next = path.get(i);
            if (!at.isNeighbour(next)) {
                return "error.pathNotConnected";
            }
            if (!s.map.passable(next)) {
                return "error.cannotEnter";
            }
            int cost = s.map.tile(next).moveCost();
            if (i > 0 && cost > points) {
                return "error.tooFar";
            }
            points -= cost;
            at = next;
        }
        return null;
    }
}
