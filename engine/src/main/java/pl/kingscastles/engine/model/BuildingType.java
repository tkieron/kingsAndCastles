package pl.kingscastles.engine.model;

public enum BuildingType {
    FARM(30, Place.VILLAGE),
    MARKET(50, Place.SETTLEMENT),
    GOLD_MINE(60, Place.HILLS_TILE),
    INN(40, Place.VILLAGE),
    TREASURY(80, Place.CASTLE),
    BARRACKS(50, Place.SETTLEMENT),
    WALLS(70, Place.SETTLEMENT),
    BRIDGE(20, Place.RIVER_TILE),
    /** Paid in installments: one installment per turn, see Balance.palaceInstallment. */
    GREAT_PALACE(500, Place.CASTLE);

    public enum Place { VILLAGE, CASTLE, SETTLEMENT, HILLS_TILE, RIVER_TILE }

    public final int cost;
    public final Place place;

    BuildingType(int cost, Place place) {
        this.cost = cost;
        this.place = place;
    }

    public boolean onTile() {
        return place == Place.HILLS_TILE || place == Place.RIVER_TILE;
    }
}
