package pl.kingscastles.engine.model;

public class GameMap {
    public final int width;
    public final int height;
    private final Tile[][] tiles;

    public GameMap(int width, int height) {
        this.width = width;
        this.height = height;
        this.tiles = new Tile[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                tiles[y][x] = new Tile(Terrain.MEADOW);
            }
        }
    }

    public boolean inBounds(Pos p) {
        return p.x() >= 0 && p.y() >= 0 && p.x() < width && p.y() < height;
    }

    public Tile tile(Pos p) {
        return tiles[p.y()][p.x()];
    }

    public Tile tile(int x, int y) {
        return tiles[y][x];
    }

    public boolean passable(Pos p) {
        return inBounds(p) && tile(p).terrain.passable;
    }
}
