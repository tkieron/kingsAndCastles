package pl.kingscastles.engine;

import pl.kingscastles.engine.model.Enums.Controller;
import pl.kingscastles.engine.model.GameMap;
import pl.kingscastles.engine.model.GameState;
import pl.kingscastles.engine.model.Kingdom;
import pl.kingscastles.engine.model.Pos;
import pl.kingscastles.engine.model.Settlement;
import pl.kingscastles.engine.model.Terrain;
import pl.kingscastles.engine.model.Unit;
import pl.kingscastles.engine.model.UnitType;
import pl.kingscastles.engine.order.Order;
import pl.kingscastles.engine.turn.TurnReport;
import pl.kingscastles.engine.turn.TurnResolver;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A small hand-made world for rule tests: an all-meadow map, no random events. */
final class TestWorld {
    final GameState s;
    final Map<String, List<Order>> orders = new HashMap<>();

    TestWorld(int w, int h) {
        Balance b = new Balance();
        b.eventChancePercent = 0;
        b.helpEveryTurns = 1000;
        s = new GameState(42, b, new GameMap(w, h), 20);
    }

    static TestWorld meadow() {
        return new TestWorld(16, 12);
    }

    TestWorld war() {
        s.balance.peaceTurns = 0;
        s.balance.castleProtectedUntilTurn = 0;
        return this;
    }

    Kingdom kingdom(String id, int gold) {
        Kingdom k = new Kingdom(id, id, "#000000", Controller.HUMAN);
        k.gold = gold;
        k.food = 50;
        s.kingdoms.put(id, k);
        return k;
    }

    Settlement castle(String owner, int x, int y) {
        Settlement st = new Settlement(s.newId(), owner + "-castle", new Pos(x, y), true, owner, 3);
        s.settlements.add(st);
        return st;
    }

    Settlement village(String owner, int x, int y) {
        Settlement st = new Settlement(s.newId(), owner + "-village", new Pos(x, y), false, owner, 3);
        s.settlements.add(st);
        return st;
    }

    Unit unit(String owner, UnitType type, int x, int y) {
        Unit u = new Unit(s.newId(), owner, type, new Pos(x, y));
        s.units.add(u);
        return u;
    }

    void terrain(int x, int y, Terrain t) {
        s.map.tile(x, y).terrain = t;
    }

    TestWorld order(String kingdom, Order... list) {
        orders.computeIfAbsent(kingdom, k -> new java.util.ArrayList<>()).addAll(List.of(list));
        return this;
    }

    TurnReport endTurn() {
        TurnReport r = TurnResolver.resolve(s, new HashMap<>(orders));
        orders.clear();
        return r;
    }

    boolean alive(Unit u) {
        return s.units.contains(u);
    }

    static Pos p(int x, int y) {
        return new Pos(x, y);
    }
}
