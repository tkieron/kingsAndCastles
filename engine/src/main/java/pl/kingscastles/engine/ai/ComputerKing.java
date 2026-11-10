package pl.kingscastles.engine.ai;

import pl.kingscastles.engine.model.BuildingType;
import pl.kingscastles.engine.model.Enums.Controller;
import pl.kingscastles.engine.model.Enums.Mood;
import pl.kingscastles.engine.model.Enums.TaxRate;
import pl.kingscastles.engine.model.Enums.TreatyType;
import pl.kingscastles.engine.model.GameState;
import pl.kingscastles.engine.model.Kingdom;
import pl.kingscastles.engine.model.Pos;
import pl.kingscastles.engine.model.Settlement;
import pl.kingscastles.engine.model.Terrain;
import pl.kingscastles.engine.model.Territory;
import pl.kingscastles.engine.model.Unit;
import pl.kingscastles.engine.model.UnitType;
import pl.kingscastles.engine.order.Order;
import pl.kingscastles.engine.order.OrderValidator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A computer king. It gives the same orders a child can give, using simple priorities:
 * Beginner builds and almost never attacks, Advanced balances economy and army,
 * Pro also trades, makes alliances and attacks weak spots.
 */
public final class ComputerKing {

    private ComputerKing() {
    }

    /**
     * Orders for one kingdom this turn, already checked so every one of them is valid. A computer king
     * plays by the children's rule: answers to offers, plus one move (or {@code actionsPerTurn} moves of one kind).
     */
    public static List<Order> orders(GameState s, String kingdomId, Controller level) {
        List<Order> wishes = wishes(s, kingdomId, level);
        int armySize = s.unitsOf(kingdomId).size();
        boolean armyFirst = armySize == 0
                || (level != Controller.AI_BEGINNER && s.turn > s.balance.peaceTurns && s.turn % 2 == 0);
        return keepValid(s, kingdomId, oneMove(s, kingdomId, wishes, armyFirst));
    }

    /** Keeps the answers and the most useful move (and more moves of the same kind, if the rules allow). */
    static List<Order> oneMove(GameState s, String kingdomId, List<Order> wishes, boolean armyFirst) {
        Kingdom k = s.kingdom(kingdomId);
        List<Order> out = new ArrayList<>();
        List<Order> moves = new ArrayList<>();
        for (Order o : wishes) {
            (o.category() == Order.Category.ANSWER ? out : moves).add(o);
        }
        moves.sort(Comparator.comparingInt(o -> rank(o, k, armyFirst)));
        if (!moves.isEmpty()) {
            Order.Category kind = moves.get(0).category();
            moves.stream().filter(o -> o.category() == kind).limit(Math.max(0, s.balance.actionsPerTurn)).forEach(out::add);
        }
        return out;
    }

    /** Smaller is more urgent: fight back first, then hunger, sadness and a new friend, then building or the army. */
    private static int rank(Order o, Kingdom k, boolean armyFirst) {
        return switch (o) {
            case Order.Attack a -> 0;
            case Order.MarketTrade m when m.buyFood() > 0 -> 1;
            case Order.SetTax t when k.mood == Mood.SAD -> 1;
            case Order.Recruit r -> armyFirst ? 2 : 5;
            case Order.Move m -> armyFirst ? 3 : 6;
            case Order.BuildOnTile b -> armyFirst ? 4 : 2;
            case Order.Build b -> armyFirst ? 5 : 3;
            case Order.MarketTrade m -> 7;
            case Order.ProposeTreaty p -> 1;
            default -> 9;
        };
    }

    /** Everything this king would like to do this turn, before choosing one move. */
    private static List<Order> wishes(GameState s, String kingdomId, Controller level) {
        Kingdom k = s.kingdom(kingdomId);
        List<Order> out = new ArrayList<>();
        int budget = k.gold - 20;

        // tax
        TaxRate tax = level == Controller.AI_BEGINNER || k.mood != Mood.HAPPY ? TaxRate.LOW : TaxRate.MEDIUM;
        if (tax != k.tax) {
            out.add(new Order.SetTax(tax));
        }

        // treaties offered to us
        for (GameState.Proposal p : s.proposals) {
            if (!p.to().equals(kingdomId)) {
                continue;
            }
            boolean accept = p.type() != TreatyType.ALLIANCE || level != Controller.AI_BEGINNER;
            if (accept) {
                out.add(new Order.AcceptTreaty(p.from(), p.type()));
            }
        }

        // food market
        if (k.food > 40 && s.foodPrice >= 3) {
            out.add(new Order.MarketTrade(0, k.food - 30));
        } else if (k.food < 5 && s.foodPrice <= 3 && budget >= 3 * s.foodPrice) {
            out.add(new Order.MarketTrade(3, 0));
            budget -= 3 * s.foodPrice;
        }

        // gold mine first: it pays back fastest
        Territory territory = Territory.of(s);
        if (budget >= BuildingType.GOLD_MINE.cost) {
            Optional<Pos> hills = freeHills(s, territory, kingdomId);
            if (hills.isPresent()) {
                out.add(new Order.BuildOnTile(hills.get(), BuildingType.GOLD_MINE));
                budget -= BuildingType.GOLD_MINE.cost;
            }
        }

        // one building per settlement
        for (Settlement st : s.settlementsOf(kingdomId)) {
            BuildingType next = nextBuilding(s, k, st, level);
            if (next == null) {
                continue;
            }
            int cost = next == BuildingType.GREAT_PALACE ? s.balance.palaceInstallment : next.cost;
            if (cost <= budget) {
                out.add(new Order.Build(st.id, next));
                budget -= cost;
            }
        }

        // army
        List<Unit> army = s.unitsOf(kingdomId);
        int wanted = switch (level) {
            case AI_BEGINNER -> 1;
            case AI_ADVANCED -> 2 + s.turn / 8;
            default -> 3 + s.turn / 6;
        };
        if (army.size() < wanted) {
            Optional<Settlement> castle = s.castleOf(kingdomId);
            if (castle.isPresent()) {
                UnitType type = castle.get().buildings.contains(BuildingType.BARRACKS) && level != Controller.AI_BEGINNER
                        ? (s.turn % 2 == 0 ? UnitType.KNIGHT : UnitType.ARCHER)
                        : UnitType.SPEARMAN;
                if (type.cost <= budget) {
                    out.add(new Order.Recruit(castle.get().id, type));
                    budget -= type.cost;
                }
            }
        }

        // war
        Set<Pos> claimed = new HashSet<>();
        for (Unit u : army) {
            if (!u.type.canFight()) {
                continue;
            }
            Optional<Pos> target = target(s, territory, u, level);
            if (target.isEmpty()) {
                continue;
            }
            if (u.pos.isNeighbour(target.get())) {
                out.add(new Order.Attack(u.id, target.get()));
            } else if (level != Controller.AI_BEGINNER) {
                stepTowards(s, u, target.get(), claimed).ifPresent(step -> out.add(new Order.Move(u.id, List.of(step))));
            }
        }

        // pro kings look for friends
        if (level == Controller.AI_PRO && s.turn % 4 == 1) {
            // the nearest neighbour, so that not every king writes to the same one
            Optional<Pos> home = s.castleOf(kingdomId).map(c -> c.pos);
            s.kingdoms.values().stream()
                    .filter(o -> !o.id.equals(kingdomId) && !s.hasTreaty(kingdomId, o.id, TreatyType.TRADE))
                    .min(Comparator.comparingInt(o -> home.flatMap(h -> s.castleOf(o.id).map(c -> c.pos.distance(h)))
                            .orElse(Integer.MAX_VALUE)))
                    .ifPresent(o -> out.add(new Order.ProposeTreaty(o.id, TreatyType.TRADE)));
        }
        // each wish on its own must be a valid order; the chosen move is checked again as a whole
        return out.stream().filter(o -> OrderValidator.validate(s, kingdomId, List.of(o)).isEmpty()).toList();
    }

    /** Drops orders the validator rejects (and the last costly ones if money runs short). */
    public static List<Order> keepValid(GameState s, String kingdomId, List<Order> orders) {
        List<Order> list = new ArrayList<>(orders);
        for (int guard = 0; guard < 50; guard++) {
            List<OrderValidator.Problem> problems = OrderValidator.validate(s, kingdomId, list);
            if (problems.isEmpty()) {
                return list;
            }
            OrderValidator.Problem p = problems.get(0);
            if (p.orderIndex() >= 0) {
                list.remove(p.orderIndex());
            } else {
                for (int i = list.size() - 1; i >= 0; i--) {
                    if (list.get(i).goldCost(s) > 0 || list.get(i).foodCost() > 0) {
                        list.remove(i);
                        break;
                    }
                }
            }
        }
        return List.of();
    }

    private static BuildingType nextBuilding(GameState s, Kingdom k, Settlement st, Controller level) {
        List<BuildingType> wish = new ArrayList<>();
        if (st.castle) {
            wish.add(BuildingType.MARKET);
            if (level != Controller.AI_BEGINNER) {
                wish.add(BuildingType.BARRACKS);
            }
            wish.add(BuildingType.TREASURY);
            if (level == Controller.AI_PRO) {
                wish.add(BuildingType.WALLS);
            }
            if (level != Controller.AI_BEGINNER && !k.palaceDone && k.gold > 250) {
                wish.add(BuildingType.GREAT_PALACE);
            }
        } else {
            boolean meadow = st.pos.neighbours().stream()
                    .anyMatch(p -> s.map.inBounds(p) && s.map.tile(p).terrain == Terrain.MEADOW);
            if (meadow) {
                wish.add(BuildingType.FARM);
            }
            wish.add(BuildingType.MARKET);
            wish.add(BuildingType.INN);
        }
        return wish.stream()
                .filter(t -> t == BuildingType.GREAT_PALACE || !st.buildings.contains(t))
                .findFirst().orElse(null);
    }

    private static Optional<Pos> freeHills(GameState s, Territory territory, String kingdomId) {
        for (int y = 0; y < s.map.height; y++) {
            for (int x = 0; x < s.map.width; x++) {
                Pos p = new Pos(x, y);
                if (s.map.tile(p).terrain == Terrain.HILLS && s.map.tile(p).improvement == null
                        && territory.owns(kingdomId, p) && s.settlementAt(p).isEmpty()) {
                    return Optional.of(p);
                }
            }
        }
        return Optional.empty();
    }

    /** Beginner only hits enemies standing in its own land; others look up to 6 fields away. */
    private static Optional<Pos> target(GameState s, Territory territory, Unit u, Controller level) {
        int range = level == Controller.AI_BEGINNER ? 1 : (level == Controller.AI_ADVANCED ? 4 : 6);
        List<Pos> targets = new ArrayList<>();
        for (Unit e : s.units) {
            if (s.hostile(u.owner, e.owner) && u.pos.distance(e.pos) <= range
                    && (level != Controller.AI_BEGINNER || territory.owns(u.owner, e.pos))) {
                targets.add(e.pos);
            }
        }
        if (level != Controller.AI_BEGINNER) {
            for (Settlement st : s.settlements) {
                boolean castleOpen = !st.castle || s.turn > s.balance.castleProtectedUntilTurn;
                if (castleOpen && s.hostile(u.owner, st.owner) && u.pos.distance(st.pos) <= range
                        && s.unitAt(st.pos).isEmpty()) {
                    targets.add(st.pos);
                }
            }
        }
        return targets.stream().min(Comparator.comparingInt(p -> u.pos.distance(p)));
    }

    private static Optional<Pos> stepTowards(GameState s, Unit u, Pos goal, Set<Pos> claimed) {
        Optional<Pos> best = u.pos.neighbours().stream()
                .filter(s.map::passable)
                .filter(p -> s.unitAt(p).isEmpty() && !claimed.contains(p))
                .filter(p -> s.settlementAt(p).map(st -> st.owner.equals(u.owner)).orElse(true))
                .filter(p -> p.distance(goal) < u.pos.distance(goal))
                .min(Comparator.comparingInt(p -> p.distance(goal)));
        best.ifPresent(claimed::add);
        return best;
    }
}
