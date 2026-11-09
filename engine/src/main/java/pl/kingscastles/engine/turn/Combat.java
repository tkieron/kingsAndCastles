package pl.kingscastles.engine.turn;

import pl.kingscastles.engine.model.BuildingType;
import pl.kingscastles.engine.model.GameState;
import pl.kingscastles.engine.model.Settlement;
import pl.kingscastles.engine.model.Unit;
import pl.kingscastles.engine.model.UnitType;

import java.util.Optional;
import java.util.Random;

/**
 * Battle rules, simple enough for a child to re-count on screen:
 * attacker = attack + 2 if strong against the defender (+6 for a Ram against walls) + dice 1–6,
 * defender = defense + 1 for forest/hills + 3 for walls + dice 1–6. Higher wins, a tie goes to the defender.
 */
public final class Combat {

    public record Side(int unitId, String owner, UnitType type, int base, int bonus, int dice, int total) {
    }

    public record Result(Side attacker, Side defender, boolean attackerWins) {
        public int loserId() {
            return attackerWins ? defender.unitId() : attacker.unitId();
        }

        public int winnerId() {
            return attackerWins ? attacker.unitId() : defender.unitId();
        }
    }

    private Combat() {
    }

    public static Result attack(GameState s, Unit att, Unit def, Random rnd) {
        Optional<Settlement> place = s.settlementAt(def.pos);
        boolean walls = place.isPresent() && place.get().buildings.contains(BuildingType.WALLS)
                && place.get().owner.equals(def.owner);
        int attBonus = att.type.strongAgainst(def.type) ? 2 : 0;
        if (att.type == UnitType.RAM && walls) {
            attBonus += 6;
        }
        int defBonus = s.map.tile(def.pos).terrain.defenseBonus + (walls ? 3 : 0);
        int d1 = dice(rnd);
        int d2 = dice(rnd);
        Side a = new Side(att.id, att.owner, att.type, att.type.attack, attBonus, d1, att.type.attack + attBonus + d1);
        Side d = new Side(def.id, def.owner, def.type, def.type.defense, defBonus, d2, def.type.defense + defBonus + d2);
        return new Result(a, d, a.total() > d.total());
    }

    /**
     * Two units meeting on the way (same field, or passing each other): both use attack.
     * Returns null on a tie: nobody is beaten and both stop.
     */
    public static Result meeting(Unit first, Unit second, Random rnd) {
        int b1 = first.type.strongAgainst(second.type) ? 2 : 0;
        int b2 = second.type.strongAgainst(first.type) ? 2 : 0;
        int d1 = dice(rnd);
        int d2 = dice(rnd);
        Side a = new Side(first.id, first.owner, first.type, first.type.attack, b1, d1, first.type.attack + b1 + d1);
        Side d = new Side(second.id, second.owner, second.type, second.type.attack, b2, d2, second.type.attack + b2 + d2);
        if (a.total() == d.total()) {
            return null;
        }
        return new Result(a, d, a.total() > d.total());
    }

    static int dice(Random rnd) {
        return 1 + rnd.nextInt(6);
    }
}
