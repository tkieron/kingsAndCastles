package pl.kingscastles.engine.turn;

import pl.kingscastles.engine.Balance;
import pl.kingscastles.engine.model.BuildingType;
import pl.kingscastles.engine.model.Enums.Mood;
import pl.kingscastles.engine.model.Enums.TreatyType;
import pl.kingscastles.engine.model.GameState;
import pl.kingscastles.engine.model.Kingdom;
import pl.kingscastles.engine.model.Pos;
import pl.kingscastles.engine.model.Settlement;
import pl.kingscastles.engine.model.Terrain;
import pl.kingscastles.engine.model.Territory;
import pl.kingscastles.engine.model.Tile;
import pl.kingscastles.engine.model.Treaty;
import pl.kingscastles.engine.model.Unit;
import pl.kingscastles.engine.model.UnitType;
import pl.kingscastles.engine.order.Order;
import pl.kingscastles.engine.order.Order.*;
import pl.kingscastles.engine.turn.TurnReport.LedgerLine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Carries out everyone's orders when a turn ends. Everybody gives orders at the same time,
 * then the orders run in a fixed order of phases, so the result never depends on who clicked first:
 * <ol start="0">
 *   <li>reserve gold and food for every order (the same gold cannot be spent twice)</li>
 *   <li>deals: tax, treaties</li>
 *   <li>gifts and buying freedom</li>
 *   <li>market: all food trades at one shared price</li>
 *   <li>movement, step by step; units meeting on the way fight</li>
 *   <li>attacks, fastest units first</li>
 *   <li>capturing villages and castles</li>
 *   <li>building, recruiting, founding villages</li>
 *   <li>money: income, upkeep, loans, tribute, food, growth, mood, events</li>
 *   <li>save: next turn, victory check</li>
 * </ol>
 * All randomness comes from a generator seeded with the game seed and the turn, so a turn can be replayed.
 */
public final class TurnResolver {
    public static final int RESERVE = 0, DEALS = 1, GIFTS = 2, MARKET = 3, MOVE = 4, ATTACK = 5,
            CAPTURE = 6, BUILD = 7, MONEY = 8, SAVE = 9;

    private static final List<String> ALL = List.of();

    private final GameState s;
    private final Balance b;
    private final Random rnd;
    private final List<ChronicleEvent> log = new ArrayList<>();
    private final Map<String, List<LedgerLine>> ledgers = new LinkedHashMap<>();
    /** Orders that passed the reservation, in submission order, with their owner. */
    private final List<Owned> accepted = new ArrayList<>();
    /** Buildings finished this turn: they start working next turn. */
    private final Set<String> fresh = new HashSet<>();

    private record Owned(String kingdom, Order order, int seq) {
    }

    private TurnResolver(GameState s) {
        this.s = s;
        this.b = s.balance;
        this.rnd = new Random(s.seed * 1_000_003L + s.turn);
        for (String k : s.kingdoms.keySet()) {
            ledgers.put(k, new ArrayList<>());
        }
    }

    /** Resolves the current turn. {@code orders} maps kingdom id to its orders (already validated). */
    public static TurnReport resolve(GameState s, Map<String, List<Order>> orders) {
        if (s.finished) {
            throw new IllegalStateException("The game is over");
        }
        TurnResolver r = new TurnResolver(s);
        r.reserve(orders);
        r.deals();
        r.gifts();
        r.market();
        r.move();
        r.attack();
        r.capture();
        r.build();
        r.money();
        return r.save();
    }

    // ---------------------------------------------------------------- 0. reserve

    private void reserve(Map<String, List<Order>> orders) {
        int seq = 0;
        for (Kingdom k : s.kingdoms.values()) {
            List<Order> list = orders.getOrDefault(k.id, List.of());
            if (k.isHuman()) {
                k.idleTurns = list.isEmpty() ? k.idleTurns + 1 : 0;
                if (!list.isEmpty() && k.autopilot) {
                    k.autopilot = false;
                    event(RESERVE, "chronicle.playerBack", ALL, "kingdom", k.id);
                }
            }
            if (list.stream().anyMatch(o -> o instanceof TakeLoan) && k.loanLeft == 0) {
                k.gold += b.loanAmount;
                k.loanLeft = b.loanTotal;
                ledger(k.id, "ledger.loanTaken", b.loanAmount);
                event(RESERVE, "chronicle.loanTaken", List.of(k.id), "gold", b.loanAmount, "payBack", b.loanTotal);
            }
            for (Order o : list) {
                if (o instanceof TakeLoan) {
                    continue;
                }
                int gold = o.goldCost(s);
                int food = o.foodCost();
                if (gold <= k.gold && food <= k.food) {
                    k.gold -= gold;
                    k.food -= food;
                    accepted.add(new Owned(k.id, o, seq++));
                } else {
                    event(RESERVE, "chronicle.orderSkippedNoMoney", List.of(k.id), "order", o.getClass().getSimpleName());
                }
            }
        }
    }

    private <T extends Order> List<Owned> ordersOf(Class<T> type) {
        return accepted.stream().filter(o -> type.isInstance(o.order)).toList();
    }

    private void refund(Owned o) {
        Kingdom k = s.kingdom(o.kingdom);
        k.gold += o.order.goldCost(s);
        k.food += o.order.foodCost();
    }

    // ---------------------------------------------------------------- 1. deals

    private void deals() {
        s.treaties.removeIf(t -> !t.activeIn(s.turn));
        for (Owned o : ordersOf(SetTax.class)) {
            s.kingdom(o.kingdom).tax = ((SetTax) o.order).rate();
        }
        for (Owned o : ordersOf(BreakTreaty.class)) {
            BreakTreaty br = (BreakTreaty) o.order;
            for (Treaty t : s.treaties) {
                if (t.type == br.type() && t.between(o.kingdom, br.with()) && t.endsAfterTurn > s.turn + b.breakDelayTurns) {
                    t.endsAfterTurn = s.turn + b.breakDelayTurns;
                    if (t.type != TreatyType.TRADE) {
                        Kingdom k = s.kingdom(o.kingdom);
                        k.brokenPromises++;
                        k.brokenPromiseUntil = s.turn + b.brokenPromiseMarkTurns;
                    }
                    event(DEALS, "chronicle.treatyBroken", ALL, "by", o.kingdom, "with", br.with(),
                            "type", br.type(), "lastTurn", t.endsAfterTurn);
                }
            }
        }
        for (Owned o : ordersOf(AcceptTreaty.class)) {
            AcceptTreaty a = (AcceptTreaty) o.order;
            Optional<GameState.Proposal> p = s.proposals.stream()
                    .filter(x -> x.from().equals(a.from()) && x.to().equals(o.kingdom) && x.type() == a.type())
                    .findFirst();
            if (p.isEmpty() || s.hasTreaty(a.from(), o.kingdom, a.type())) {
                continue;
            }
            s.proposals.remove(p.get());
            if (a.type() == TreatyType.ALLIANCE && (s.alliesOf(a.from()).size() + 1 >= b.maxAllianceSize
                    || s.alliesOf(o.kingdom).size() + 1 >= b.maxAllianceSize)) {
                event(DEALS, "chronicle.allianceTooBig", List.of(a.from(), o.kingdom), "max", b.maxAllianceSize);
                continue;
            }
            int ends = a.type() == TreatyType.PACT ? s.turn + b.pactTurns : Integer.MAX_VALUE;
            s.treaties.add(new Treaty(a.type(), a.from(), o.kingdom, s.turn, ends));
            event(DEALS, "chronicle.treatySigned", ALL, "a", a.from(), "b", o.kingdom, "type", a.type());
        }
        s.proposals.removeIf(p -> p.turn() < s.turn);
        for (Owned o : ordersOf(ProposeTreaty.class)) {
            ProposeTreaty p = (ProposeTreaty) o.order;
            s.proposals.removeIf(x -> x.from().equals(o.kingdom) && x.to().equals(p.to()) && x.type() == p.type());
            s.proposals.add(new GameState.Proposal(o.kingdom, p.to(), p.type(), s.turn));
            event(DEALS, "chronicle.treatyProposed", List.of(o.kingdom, p.to()), "from", o.kingdom, "to", p.to(), "type", p.type());
        }
    }

    // ---------------------------------------------------------------- 2. gifts

    private void gifts() {
        for (Owned o : ordersOf(Gift.class)) {
            Gift g = (Gift) o.order;
            Kingdom to = s.kingdom(g.to());
            to.gold += g.gold();
            to.food += g.food();
            if (g.gold() > 0) {
                ledger(o.kingdom, "ledger.giftSent", -g.gold(), "to", g.to());
                ledger(g.to(), "ledger.giftReceived", g.gold(), "from", o.kingdom);
            }
            event(GIFTS, "chronicle.gift", List.of(o.kingdom, g.to()), "from", o.kingdom, "to", g.to(),
                    "gold", g.gold(), "food", g.food());
        }
        for (Owned o : ordersOf(BuyFreedom.class)) {
            Kingdom k = s.kingdom(o.kingdom);
            if (k.overlord == null) {
                refund(o);
                continue;
            }
            s.kingdom(k.overlord).gold += b.freedomPrice;
            ledger(o.kingdom, "ledger.freedom", -b.freedomPrice);
            ledger(k.overlord, "ledger.freedomPaid", b.freedomPrice, "from", o.kingdom);
            event(GIFTS, "chronicle.freedomBought", ALL, "kingdom", o.kingdom, "from", k.overlord);
            k.overlord = null;
        }
    }

    // ---------------------------------------------------------------- 3. market

    private void market() {
        int price = s.foodPrice;
        int sold = 0;
        int bought = 0;
        for (Owned o : ordersOf(MarketTrade.class)) {
            MarketTrade m = (MarketTrade) o.order;
            Kingdom k = s.kingdom(o.kingdom);
            k.food += m.buyFood();
            k.gold += m.sellFood() * price;
            sold += m.sellFood();
            bought += m.buyFood();
            if (m.buyFood() > 0) {
                ledger(o.kingdom, "ledger.foodBought", -m.buyFood() * price, "food", m.buyFood(), "price", price);
            }
            if (m.sellFood() > 0) {
                ledger(o.kingdom, "ledger.foodSold", m.sellFood() * price, "food", m.sellFood(), "price", price);
            }
        }
        int net = sold - bought;
        int next = price - net / b.priceStepVolume;
        next = Math.max(b.foodPriceMin, Math.min(b.foodPriceMax, next));
        if (next != price) {
            event(MARKET, next < price ? "chronicle.priceDown" : "chronicle.priceUp", ALL, "from", price, "to", next);
        }
        s.foodPrice = next;
    }

    // ---------------------------------------------------------------- 4. move

    private void move() {
        Map<Integer, Deque<Pos>> paths = new LinkedHashMap<>();
        Map<Integer, Integer> seq = new HashMap<>();
        Map<Integer, Integer> points = new HashMap<>();
        for (Owned o : ordersOf(Move.class)) {
            Move m = (Move) o.order;
            s.unit(m.unitId()).ifPresent(u -> {
                paths.put(u.id, new ArrayDeque<>(m.path()));
                seq.put(u.id, o.seq);
                points.put(u.id, u.type.move);
            });
        }
        int maxTicks = 0;
        for (UnitType t : UnitType.values()) {
            maxTicks = Math.max(maxTicks, t.move);
        }
        for (int tick = 0; tick < maxTicks; tick++) {
            Map<Integer, Pos> want = new LinkedHashMap<>();
            for (var e : paths.entrySet()) {
                Optional<Unit> u = s.unit(e.getKey());
                Pos next = e.getValue().peek();
                if (u.isEmpty() || next == null) {
                    continue;
                }
                int cost = s.map.tile(next).moveCost();
                if (tick == 0 || points.get(u.get().id) >= cost) {
                    want.put(u.get().id, next);
                } else {
                    e.getValue().clear();
                }
            }
            // settlements that may not be entered
            for (Integer id : new ArrayList<>(want.keySet())) {
                Unit u = s.unit(id).orElseThrow();
                Optional<Settlement> st = s.settlementAt(want.get(id));
                if (st.isPresent() && !mayEnter(u.owner, st.get())) {
                    stop(id, want, paths);
                }
            }
            // two hostile units passing each other
            for (Integer a : new ArrayList<>(want.keySet())) {
                for (Integer c : new ArrayList<>(want.keySet())) {
                    if (a >= c || !want.containsKey(a) || !want.containsKey(c)) {
                        continue;
                    }
                    Unit ua = s.unit(a).orElseThrow();
                    Unit uc = s.unit(c).orElseThrow();
                    if (want.get(a).equals(uc.pos) && want.get(c).equals(ua.pos) && s.hostile(ua.owner, uc.owner)) {
                        meet(ua, uc, want, paths);
                    }
                }
            }
            // several units heading for the same field
            Map<Pos, List<Integer>> byDest = new LinkedHashMap<>();
            for (var e : want.entrySet()) {
                byDest.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
            }
            for (List<Integer> group : byDest.values()) {
                if (group.size() < 2) {
                    continue;
                }
                group.sort(Comparator.comparingInt(seq::get));
                Integer champion = group.get(0);
                for (int i = 1; i < group.size(); i++) {
                    Integer other = group.get(i);
                    Optional<Unit> uc = s.unit(champion);
                    Optional<Unit> uo = s.unit(other);
                    if (uc.isEmpty() || uo.isEmpty()) {
                        continue;
                    }
                    if (s.hostile(uc.get().owner, uo.get().owner)) {
                        Integer winner = meet(uc.get(), uo.get(), want, paths);
                        if (winner == null) {
                            break;
                        }
                        champion = winner;
                    } else {
                        stop(other, want, paths);
                    }
                }
            }
            // fields still occupied by someone who stays
            boolean changed = true;
            while (changed) {
                changed = false;
                for (Integer id : new ArrayList<>(want.keySet())) {
                    Pos dest = want.get(id);
                    Optional<Unit> occ = s.unitAt(dest);
                    if (occ.isPresent() && occ.get().id != id && !want.containsKey(occ.get().id)) {
                        stop(id, want, paths);
                        changed = true;
                    }
                }
            }
            for (var e : want.entrySet()) {
                Unit u = s.unit(e.getKey()).orElseThrow();
                int cost = s.map.tile(e.getValue()).moveCost();
                u.pos = e.getValue();
                points.put(u.id, points.get(u.id) - cost);
                paths.get(u.id).poll();
                ruins(u);
            }
        }
    }

    /** Own and allied settlements are open; an enemy's only when at war (a castle only after the protected turns). */
    private boolean mayEnter(String who, Settlement st) {
        if (s.allied(who, st.owner)) {
            return true;
        }
        if (!s.hostile(who, st.owner)) {
            return false;
        }
        return !st.castle || s.turn > b.castleProtectedUntilTurn;
    }

    private void stop(Integer id, Map<Integer, Pos> want, Map<Integer, Deque<Pos>> paths) {
        want.remove(id);
        Deque<Pos> p = paths.get(id);
        if (p != null) {
            p.clear();
        }
    }

    /** A fight between two moving units. Returns the winner's id, or null on a tie (both stop). */
    private Integer meet(Unit first, Unit second, Map<Integer, Pos> want, Map<Integer, Deque<Pos>> paths) {
        Combat.Result r = Combat.meeting(first, second, rnd);
        if (r == null) {
            stop(first.id, want, paths);
            stop(second.id, want, paths);
            event(MOVE, "chronicle.meetingTie", List.of(first.owner, second.owner), "a", first.owner, "b", second.owner);
            return null;
        }
        battleEvent(MOVE, "chronicle.meeting", r);
        kill(r.loserId(), want, paths);
        return r.winnerId();
    }

    private void kill(int unitId, Map<Integer, Pos> want, Map<Integer, Deque<Pos>> paths) {
        s.units.removeIf(u -> u.id == unitId);
        if (want != null) {
            want.remove(unitId);
        }
        if (paths != null) {
            paths.remove(unitId);
        }
    }

    private void ruins(Unit u) {
        Tile t = s.map.tile(u.pos);
        if (t.ruins) {
            t.ruins = false;
            int gold = b.ruinsGoldMin + rnd.nextInt(b.ruinsGoldMax - b.ruinsGoldMin + 1);
            s.kingdom(u.owner).gold += gold;
            ledger(u.owner, "ledger.ruins", gold);
            event(MOVE, "chronicle.ruinsFound", ALL, "kingdom", u.owner, "gold", gold, "pos", u.pos.toString());
        }
    }

    // ---------------------------------------------------------------- 5. attack

    private void attack() {
        List<Owned> attacks = new ArrayList<>(ordersOf(Attack.class));
        Collections.shuffle(attacks, rnd);
        attacks.sort(Comparator.comparingInt(o -> s.unit(((Attack) o.order).unitId())
                .map(u -> u.type.attackPriority()).orElse(99)));
        for (Owned o : attacks) {
            Attack a = (Attack) o.order;
            Optional<Unit> attacker = s.unit(a.unitId());
            if (attacker.isEmpty()) {
                continue;
            }
            Unit att = attacker.get();
            if (!att.pos.isNeighbour(a.target())) {
                event(ATTACK, "chronicle.attackOutOfReach", List.of(att.owner), "unit", att.type);
                continue;
            }
            Optional<Unit> defender = s.unitAt(a.target());
            Optional<Settlement> place = s.settlementAt(a.target());
            if (defender.isPresent()) {
                Unit def = defender.get();
                if (!s.hostile(att.owner, def.owner)) {
                    event(ATTACK, "chronicle.attackAtPeace", List.of(att.owner), "target", def.owner);
                    continue;
                }
                Combat.Result r = Combat.attack(s, att, def, rnd);
                battleEvent(ATTACK, "chronicle.battle", r);
                kill(r.loserId(), null, null);
                if (r.attackerWins() && place.isPresent() && mayEnter(att.owner, place.get())
                        && !place.get().owner.equals(att.owner)) {
                    att.pos = a.target();
                }
            } else if (place.isPresent() && !place.get().owner.equals(att.owner) && mayEnter(att.owner, place.get())
                    && s.hostile(att.owner, place.get().owner)) {
                att.pos = a.target();
            } else {
                event(ATTACK, "chronicle.attackNothing", List.of(att.owner), "unit", att.type);
            }
        }
    }

    // ---------------------------------------------------------------- 6. capture

    private void capture() {
        for (Settlement st : s.settlements) {
            Optional<Unit> occ = s.unitAt(st.pos);
            if (occ.isEmpty()) {
                continue;
            }
            Unit u = occ.get();
            if (u.owner.equals(st.owner) || !s.hostile(u.owner, st.owner)) {
                continue;
            }
            Kingdom loser = s.kingdom(st.owner);
            Kingdom winner = s.kingdom(u.owner);
            if (!st.castle) {
                int loot = Math.min(b.villageLoot, Math.max(0, loser.gold));
                loser.gold -= loot;
                winner.gold += loot;
                if (loot > 0) {
                    ledger(loser.id, "ledger.lootLost", -loot, "village", st.name);
                    ledger(winner.id, "ledger.loot", loot, "village", st.name);
                }
                st.owner = winner.id;
                event(CAPTURE, "chronicle.villageCaptured", ALL, "village", st.name, "by", winner.id, "from", loser.id, "loot", loot);
            } else {
                if (winner.id.equals(loser.overlord)) {
                    continue;
                }
                loser.overlord = winner.id;
                if (loser.id.equals(winner.overlord)) {
                    winner.overlord = null;
                }
                event(CAPTURE, "chronicle.castleCaptured", ALL, "castle", st.name, "by", winner.id, "vassal", loser.id,
                        "tribute", b.vassalTribute);
                // the castle stays the vassal's home: the winner's unit steps out
                for (Pos p : st.pos.neighbours()) {
                    if (s.map.passable(p) && s.unitAt(p).isEmpty() && s.settlementAt(p).isEmpty()) {
                        u.pos = p;
                        break;
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 7. build

    private void build() {
        Territory territory = Territory.of(s);
        for (Owned o : accepted) {
            switch (o.order) {
                case Build bo -> buildInSettlement(o, bo);
                case BuildOnTile bt -> {
                    Tile t = s.map.tile(bt.pos());
                    if (!territory.owns(o.kingdom, bt.pos()) || t.improvement != null) {
                        refund(o);
                        event(BUILD, "chronicle.buildFailed", List.of(o.kingdom), "building", bt.building());
                    } else {
                        t.improvement = bt.building();
                        fresh.add(bt.pos() + ":" + bt.building());
                        ledger(o.kingdom, "ledger.build", -bt.building().cost, "building", bt.building());
                        event(BUILD, "chronicle.built", List.of(o.kingdom), "building", bt.building(), "pos", bt.pos().toString());
                    }
                }
                case Recruit r -> recruit(o, r);
                case FoundVillage f -> foundVillage(o, f);
                default -> {
                }
            }
        }
    }

    private void buildInSettlement(Owned o, Build bo) {
        Optional<Settlement> st = s.settlement(bo.settlementId());
        Kingdom k = s.kingdom(o.kingdom);
        if (st.isEmpty() || !st.get().owner.equals(o.kingdom)) {
            refund(o);
            event(BUILD, "chronicle.buildFailed", List.of(o.kingdom), "building", bo.building());
            return;
        }
        int cost = bo.goldCost(s);
        ledger(o.kingdom, "ledger.build", -cost, "building", bo.building());
        if (bo.building() == BuildingType.GREAT_PALACE) {
            k.palacePaid++;
            if (k.palacePaid >= b.palaceInstallments) {
                k.palaceDone = true;
                event(BUILD, "chronicle.palaceDone", ALL, "kingdom", k.id);
            } else {
                event(BUILD, "chronicle.palaceProgress", List.of(k.id), "paid", k.palacePaid, "of", b.palaceInstallments);
            }
            return;
        }
        st.get().buildings.add(bo.building());
        fresh.add(st.get().id + ":" + bo.building());
        event(BUILD, "chronicle.built", List.of(o.kingdom), "building", bo.building(), "settlement", st.get().name);
    }

    private void recruit(Owned o, Recruit r) {
        Optional<Settlement> st = s.settlement(r.settlementId());
        if (st.isEmpty() || !st.get().owner.equals(o.kingdom)) {
            refund(o);
            return;
        }
        Pos spawn = null;
        if (s.unitAt(st.get().pos).isEmpty()) {
            spawn = st.get().pos;
        } else {
            for (Pos p : st.get().pos.neighbours()) {
                Optional<Settlement> other = s.settlementAt(p);
                if (s.map.passable(p) && s.unitAt(p).isEmpty()
                        && (other.isEmpty() || other.get().owner.equals(o.kingdom))) {
                    spawn = p;
                    break;
                }
            }
        }
        if (spawn == null) {
            refund(o);
            event(BUILD, "chronicle.recruitNoRoom", List.of(o.kingdom), "unit", r.unit(), "settlement", st.get().name);
            return;
        }
        s.units.add(new Unit(s.newId(), o.kingdom, r.unit(), spawn));
        ledger(o.kingdom, "ledger.recruit", -r.unit().cost, "unit", r.unit());
        event(BUILD, "chronicle.recruited", List.of(o.kingdom), "unit", r.unit(), "settlement", st.get().name);
    }

    private void foundVillage(Owned o, FoundVillage f) {
        Optional<Unit> settler = s.unit(f.unitId());
        if (settler.isEmpty()) {
            return;
        }
        Pos p = settler.get().pos;
        Terrain t = s.map.tile(p).terrain;
        boolean tooClose = s.settlements.stream().anyMatch(st -> st.pos.distance(p) < b.settlerMinDistance);
        if (tooClose || t == Terrain.RIVER || !t.passable) {
            event(BUILD, "chronicle.villageNotHere", List.of(o.kingdom), "pos", p.toString());
            return;
        }
        String name = f.name() == null || f.name().isBlank() ? "Village " + (s.settlements.size() + 1) : f.name();
        s.settlements.add(new Settlement(s.newId(), name, p, false, o.kingdom, b.newVillageFamilies));
        s.units.remove(settler.get());
        event(BUILD, "chronicle.villageFounded", ALL, "kingdom", o.kingdom, "village", name);
    }

    // ---------------------------------------------------------------- 8. money

    private void money() {
        randomEvent();
        String helped = null;
        if (s.turn % b.helpEveryTurns == 0 && s.kingdoms.size() > 1) {
            helped = s.kingdoms.values().stream()
                    .min(Comparator.comparingInt((Kingdom k) -> Scoring.prestige(s, k.id)))
                    .map(k -> k.id).orElse(null);
        }
        Territory territory = Territory.of(s);
        for (Kingdom k : s.kingdoms.values()) {
            List<Settlement> own = s.settlementsOf(k.id);
            Mood moodBefore = k.mood;

            if (own.stream().anyMatch(st -> st.castle)) {
                gain(k, "ledger.castle", b.castleIncome);
            }
            int families = own.stream().mapToInt(st -> st.families).sum();
            int perFamily = Math.max(0, k.tax.goldPerFamily - (moodBefore == Mood.SAD ? b.sadTaxPenalty : 0));
            gain(k, "ledger.tax", families * perFamily, "families", families, "each", perFamily);

            int forests = 0;
            int mines = 0;
            for (int y = 0; y < s.map.height; y++) {
                for (int x = 0; x < s.map.width; x++) {
                    Pos p = new Pos(x, y);
                    if (!territory.owns(k.id, p)) {
                        continue;
                    }
                    Tile t = s.map.tile(p);
                    if (t.terrain == Terrain.FOREST) {
                        forests++;
                    }
                    if (t.improvement == BuildingType.GOLD_MINE && !fresh.contains(p + ":" + BuildingType.GOLD_MINE)) {
                        mines++;
                    }
                }
            }
            gain(k, "ledger.forests", forests * b.forestGold, "fields", forests);
            gain(k, "ledger.mines", mines * b.goldMineIncome, "mines", mines);
            long markets = own.stream().filter(st -> works(st, BuildingType.MARKET)).count();
            gain(k, "ledger.markets", (int) markets * b.marketIncome, "markets", markets);
            if (markets > 0) {
                for (Treaty t : s.treaties) {
                    if (t.type == TreatyType.TRADE && t.involves(k.id) && t.activeIn(s.turn)
                            && s.settlementsOf(t.other(k.id)).stream().anyMatch(st -> st.buildings.contains(BuildingType.MARKET))) {
                        gain(k, "ledger.tradeDeal", b.tradeDealIncome, "with", t.other(k.id));
                    }
                }
            }
            if (own.stream().anyMatch(st -> st.castle && works(st, BuildingType.TREASURY))) {
                gain(k, "ledger.interest", Math.min(b.interestMax, b.interestPer100 * (Math.max(0, k.gold) / 100)));
            }
            if (k.id.equals(helped)) {
                gain(k, "ledger.oldKingsGift", b.helpGold);
                event(MONEY, "chronicle.oldKingsGift", ALL, "kingdom", k.id, "gold", b.helpGold);
            }
            if (k.overlord != null) {
                int tribute = Math.min(b.vassalTribute, Math.max(0, k.gold));
                k.gold -= tribute;
                s.kingdom(k.overlord).gold += tribute;
                ledger(k.id, "ledger.tributePaid", -tribute, "to", k.overlord);
                ledger(k.overlord, "ledger.tributeReceived", tribute, "from", k.id);
            }
            payUpkeep(k);
            if (k.loanLeft > 0) {
                int rate = Math.min(b.loanInstallment, k.loanLeft);
                if (k.gold >= rate) {
                    k.gold -= rate;
                    k.loanLeft -= rate;
                    ledger(k.id, "ledger.loanInstallment", -rate, "left", k.loanLeft);
                } else {
                    event(MONEY, "chronicle.loanPostponed", List.of(k.id), "rate", rate);
                }
            }
            food(k, own);
            if (k.isHuman() && !k.autopilot && k.idleTurns >= b.idleTurnsBeforeAi) {
                k.autopilot = true;
                event(MONEY, "chronicle.autopilot", ALL, "kingdom", k.id);
            }
        }
    }

    private void payUpkeep(Kingdom k) {
        List<Unit> army = new ArrayList<>(s.unitsOf(k.id));
        int upkeep = army.stream().mapToInt(u -> u.type.upkeep).sum();
        while (upkeep > k.gold && !army.isEmpty()) {
            Unit gone = army.remove(army.size() - 1);
            s.units.remove(gone);
            upkeep -= gone.type.upkeep;
            event(MONEY, "chronicle.unitWentHome", List.of(k.id), "unit", gone.type);
        }
        if (upkeep > 0) {
            k.gold -= upkeep;
            ledger(k.id, "ledger.upkeep", -upkeep, "units", army.size());
        }
    }

    private void food(Kingdom k, List<Settlement> own) {
        int produced = 0;
        for (Settlement st : own) {
            for (Pos p : st.pos.neighbours()) {
                if (!s.map.inBounds(p)) {
                    continue;
                }
                Terrain t = s.map.tile(p).terrain;
                produced += switch (t) {
                    case MEADOW -> b.meadowFood;
                    case RIVER -> b.riverFood;
                    case LAKE -> b.lakeFood;
                    default -> 0;
                };
            }
            if (works(st, BuildingType.FARM)) {
                produced += b.farmFood;
            }
        }
        int eaten = own.stream().mapToInt(st -> st.families).sum() + s.unitsOf(k.id).size();
        int surplus = produced - eaten;
        k.food += surplus;
        boolean hungry = false;
        if (k.food < 0) {
            k.food = 0;
            hungry = true;
            own.stream().filter(st -> st.families > 1).max(Comparator.comparingInt(st -> st.families))
                    .ifPresent(st -> st.families--);
            event(MONEY, "chronicle.hunger", List.of(k.id));
        }
        int moodScore = k.tax.moodEffect
                + (own.stream().anyMatch(st -> works(st, BuildingType.INN)) ? 1 : 0)
                - (hungry ? 1 : 0);
        k.mood = moodScore >= 1 ? Mood.HAPPY : moodScore == 0 ? Mood.NORMAL : Mood.SAD;
        if (surplus >= b.growthSurplus && k.mood != Mood.SAD) {
            own.stream().filter(st -> st.families < st.capacity()).min(Comparator.comparingInt(st -> st.families))
                    .ifPresent(st -> {
                        st.families++;
                        event(MONEY, "chronicle.grew", List.of(k.id), "settlement", st.name, "families", st.families);
                    });
        }
    }

    private void randomEvent() {
        if (rnd.nextInt(100) >= b.eventChancePercent) {
            return;
        }
        List<Kingdom> all = new ArrayList<>(s.kingdoms.values());
        switch (rnd.nextInt(4)) {
            case 0 -> {
                all.forEach(k -> k.food += 5);
                event(MONEY, "event.goodHarvest", ALL, "food", 5);
            }
            case 1 -> {
                all.forEach(k -> k.food = Math.max(0, k.food - 3));
                event(MONEY, "event.drought", ALL, "food", 3);
            }
            case 2 -> {
                Kingdom lucky = all.get(rnd.nextInt(all.size()));
                lucky.gold += 10;
                ledger(lucky.id, "ledger.merchant", 10);
                event(MONEY, "event.merchant", ALL, "kingdom", lucky.id, "gold", 10);
            }
            default -> {
                s.foodPrice = b.foodPriceStart;
                event(MONEY, "event.fair", ALL, "price", b.foodPriceStart);
            }
        }
    }

    // ---------------------------------------------------------------- 9. save

    private TurnReport save() {
        int resolved = s.turn;
        s.turn++;
        Scoring.checkVictory(s);
        if (s.finished) {
            event(SAVE, "chronicle.gameOver", ALL, "reason", s.winReason, "winners", String.join(",", s.winners));
        }
        Map<String, Integer> prestige = new LinkedHashMap<>();
        for (String k : s.kingdoms.keySet()) {
            prestige.put(k, Scoring.prestige(s, k));
        }
        return new TurnReport(resolved, List.copyOf(log), ledgers, prestige, s.foodPrice);
    }

    // ---------------------------------------------------------------- helpers

    private boolean works(Settlement st, BuildingType t) {
        return st.buildings.contains(t) && !fresh.contains(st.id + ":" + t);
    }

    private void gain(Kingdom k, String key, int gold, Object... params) {
        if (gold == 0) {
            return;
        }
        k.gold += gold;
        ledger(k.id, key, gold, params);
    }

    private void ledger(String kingdom, String key, int gold, Object... kv) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            params.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        ledgers.get(kingdom).add(new LedgerLine(key, gold, params));
    }

    private void event(int phase, String key, List<String> visibleTo, Object... kv) {
        log.add(ChronicleEvent.of(phase, key, visibleTo, kv));
    }

    private void battleEvent(int phase, String key, Combat.Result r) {
        Set<String> who = new HashSet<>(List.of(r.attacker().owner(), r.defender().owner()));
        log.add(ChronicleEvent.of(phase, key, List.copyOf(who),
                "attacker", r.attacker().owner(), "attackerUnit", r.attacker().type(),
                "attackerBase", r.attacker().base(), "attackerBonus", r.attacker().bonus(),
                "attackerDice", r.attacker().dice(), "attackerTotal", r.attacker().total(),
                "defender", r.defender().owner(), "defenderUnit", r.defender().type(),
                "defenderBase", r.defender().base(), "defenderBonus", r.defender().bonus(),
                "defenderDice", r.defender().dice(), "defenderTotal", r.defender().total(),
                "attackerWins", r.attackerWins()));
    }
}
