package pl.kingscastles.engine.model;

/**
 * Which kingdom owns each field: every field within 2 of a settlement belongs to the nearest
 * settlement's owner (ties go to the older settlement).
 */
public final class Territory {
    private final String[][] owner;
    private final int[][] settlementId;

    private Territory(GameMap map) {
        owner = new String[map.height][map.width];
        settlementId = new int[map.height][map.width];
    }

    public static Territory of(GameState s) {
        Territory t = new Territory(s.map);
        int r = s.balance.territoryRadius;
        int[][] best = new int[s.map.height][s.map.width];
        for (int[] row : best) {
            java.util.Arrays.fill(row, Integer.MAX_VALUE);
        }
        for (Settlement st : s.settlements) {
            for (int y = st.pos.y() - r; y <= st.pos.y() + r; y++) {
                for (int x = st.pos.x() - r; x <= st.pos.x() + r; x++) {
                    Pos p = new Pos(x, y);
                    if (!s.map.inBounds(p)) {
                        continue;
                    }
                    int d = p.distance(st.pos);
                    if (d < best[y][x] || (d == best[y][x] && st.id < t.settlementId[y][x])) {
                        best[y][x] = d;
                        t.owner[y][x] = st.owner;
                        t.settlementId[y][x] = st.id;
                    }
                }
            }
        }
        return t;
    }

    /** Owning kingdom id, or null for no man's land. */
    public String ownerOf(Pos p) {
        return owner[p.y()][p.x()];
    }

    public boolean owns(String kingdom, Pos p) {
        return kingdom.equals(ownerOf(p));
    }
}
