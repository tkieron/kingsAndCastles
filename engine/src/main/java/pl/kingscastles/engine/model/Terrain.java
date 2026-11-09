package pl.kingscastles.engine.model;

/** Map terrain. Numbers here are rules, tuned in {@link pl.kingscastles.engine.Balance} where they are economic. */
public enum Terrain {
    MEADOW(1, true, 0),
    FOREST(2, true, 1),
    HILLS(2, true, 1),
    MOUNTAIN(0, false, 0),
    RIVER(2, true, 0),
    LAKE(0, false, 0);

    /** Movement points needed to enter. A bridge makes a river cost 1. */
    public final int moveCost;
    public final boolean passable;
    /** Bonus added to a defender standing here. */
    public final int defenseBonus;

    Terrain(int moveCost, boolean passable, int defenseBonus) {
        this.moveCost = moveCost;
        this.passable = passable;
        this.defenseBonus = defenseBonus;
    }
}
