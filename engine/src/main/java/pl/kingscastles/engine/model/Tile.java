package pl.kingscastles.engine.model;

/** One map field: terrain plus an optional improvement built on it (Gold mine, Bridge) and ruins with treasure. */
public class Tile {
    public Terrain terrain;
    public BuildingType improvement;
    public boolean ruins;

    public Tile(Terrain terrain) {
        this.terrain = terrain;
    }

    public int moveCost() {
        if (terrain == Terrain.RIVER && improvement == BuildingType.BRIDGE) {
            return 1;
        }
        return terrain.moveCost;
    }
}
