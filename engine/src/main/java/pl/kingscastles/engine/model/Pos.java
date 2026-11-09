package pl.kingscastles.engine.model;

import java.util.ArrayList;
import java.util.List;

/** A field on the map. Distance is "king's move" distance: diagonal steps count as 1. */
public record Pos(int x, int y) {

    public int distance(Pos o) {
        return Math.max(Math.abs(x - o.x), Math.abs(y - o.y));
    }

    public boolean isNeighbour(Pos o) {
        return distance(o) == 1;
    }

    /** The 8 surrounding fields (some may be outside the map). */
    public List<Pos> neighbours() {
        List<Pos> out = new ArrayList<>(8);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx != 0 || dy != 0) {
                    out.add(new Pos(x + dx, y + dy));
                }
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return x + "," + y;
    }
}
