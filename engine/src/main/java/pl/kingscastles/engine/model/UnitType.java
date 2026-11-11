package pl.kingscastles.engine.model;

/** Army units. Rock-paper-scissors: Spearman beats Knight, Knight beats Archer, Archer beats Spearman. */
public enum UnitType {
    SPEARMAN(20, 2, 2, 3, 2, false),
    ARCHER(30, 3, 3, 2, 2, true),
    KNIGHT(50, 5, 5, 3, 3, true),
    RAM(60, 4, 1, 1, 2, true),
    SETTLER(60, 2, 0, 0, 2, false);

    public final int cost;
    public final int upkeep;
    public final int attack;
    public final int defense;
    public final int move;
    public final boolean needsBarracks;

    UnitType(int cost, int upkeep, int attack, int defense, int move, boolean needsBarracks) {
        this.cost = cost;
        this.upkeep = upkeep;
        this.attack = attack;
        this.defense = defense;
        this.move = move;
        this.needsBarracks = needsBarracks;
    }

    public boolean canFight() {
        return this != SETTLER;
    }

    public boolean strongAgainst(UnitType other) {
        return switch (this) {
            case SPEARMAN -> other == KNIGHT;
            case ARCHER -> other == SPEARMAN;
            case KNIGHT -> other == ARCHER;
            default -> false;
        };
    }

    /** Order in which attacks are resolved in phase 5: "faster first". */
    public int attackPriority() {
        return switch (this) {
            case KNIGHT -> 0;
            case ARCHER -> 1;
            case SPEARMAN -> 2;
            case RAM -> 3;
            case SETTLER -> 4;
        };
    }
}
