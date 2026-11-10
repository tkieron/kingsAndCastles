package pl.kingscastles.engine.order;

import pl.kingscastles.engine.model.BuildingType;
import pl.kingscastles.engine.model.Enums.TaxRate;
import pl.kingscastles.engine.model.Enums.TreatyType;
import pl.kingscastles.engine.model.GameState;
import pl.kingscastles.engine.model.Pos;
import pl.kingscastles.engine.model.UnitType;

import java.util.List;

/**
 * One command a player gives during a turn. Orders are hidden from others and are all carried out
 * together when the turn ends, in the fixed phase order of {@link pl.kingscastles.engine.turn.TurnResolver}.
 */
public sealed interface Order {

    /**
     * What kind of move an order is. A turn is one move: the child picks one kind and gives one order of it
     * ({@link pl.kingscastles.engine.Balance#actionsPerTurn}). Answers to a treaty offer are free.
     */
    enum Category { ARMY, BUILD, ECONOMY, DIPLOMACY, ANSWER }

    Category category();

    /** Gold this order needs. It is reserved when the turn is resolved, so the same gold cannot be spent twice. */
    default int goldCost(GameState s) {
        return 0;
    }

    default int foodCost() {
        return 0;
    }

    record SetTax(TaxRate rate) implements Order {
        @Override
        public Category category() {
            return Category.ECONOMY;
        }

    }

    record Build(int settlementId, BuildingType building) implements Order {
        @Override
        public Category category() {
            return Category.BUILD;
        }

        @Override
        public int goldCost(GameState s) {
            return building == BuildingType.GREAT_PALACE ? s.balance.palaceInstallment : building.cost;
        }
    }

    record BuildOnTile(Pos pos, BuildingType building) implements Order {
        @Override
        public Category category() {
            return Category.BUILD;
        }

        @Override
        public int goldCost(GameState s) {
            return building.cost;
        }
    }

    record Recruit(int settlementId, UnitType unit) implements Order {
        @Override
        public Category category() {
            return Category.ARMY;
        }

        @Override
        public int goldCost(GameState s) {
            return unit.cost;
        }
    }

    /** Path of fields to walk, not including the unit's current field. */
    record Move(int unitId, List<Pos> path) implements Order {
        @Override
        public Category category() {
            return Category.ARMY;
        }

        public Move {
            path = List.copyOf(path);
        }
    }

    record Attack(int unitId, Pos target) implements Order {
        @Override
        public Category category() {
            return Category.ARMY;
        }

    }

    record FoundVillage(int unitId, String name) implements Order {
        @Override
        public Category category() {
            return Category.ARMY;
        }

    }

    record Gift(String to, int gold, int food) implements Order {
        @Override
        public Category category() {
            return Category.DIPLOMACY;
        }

        @Override
        public int goldCost(GameState s) {
            return gold;
        }

        @Override
        public int foodCost() {
            return food;
        }
    }

    record MarketTrade(int buyFood, int sellFood) implements Order {
        @Override
        public Category category() {
            return Category.ECONOMY;
        }

        @Override
        public int goldCost(GameState s) {
            return buyFood * s.foodPrice;
        }

        @Override
        public int foodCost() {
            return sellFood;
        }
    }

    record TakeLoan() implements Order {
        @Override
        public Category category() {
            return Category.ECONOMY;
        }

    }

    record BuyFreedom() implements Order {
        @Override
        public Category category() {
            return Category.DIPLOMACY;
        }

        @Override
        public int goldCost(GameState s) {
            return s.balance.freedomPrice;
        }
    }

    record ProposeTreaty(String to, TreatyType type) implements Order {
        @Override
        public Category category() {
            return Category.DIPLOMACY;
        }

    }

    record AcceptTreaty(String from, TreatyType type) implements Order {
        @Override
        public Category category() {
            return Category.ANSWER;
        }

    }

    record BreakTreaty(String with, TreatyType type) implements Order {
        @Override
        public Category category() {
            return Category.DIPLOMACY;
        }

    }

    /** "No, thank you" to a treaty offer: the offer is gone and the kingdom that made it hears the answer. */
    record RejectTreaty(String from, TreatyType type) implements Order {
        @Override
        public Category category() {
            return Category.ANSWER;
        }
    }
}
