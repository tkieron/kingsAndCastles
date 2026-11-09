package pl.kingscastles.engine.map;

import pl.kingscastles.engine.model.GameMap;
import pl.kingscastles.engine.model.Pos;
import pl.kingscastles.engine.model.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds a random map for the seven kingdoms (there are always seven: seats without a child are played
 * by the computer). Castles sit on a ring around the centre and every castle gets the same 7x7 home
 * area (same meadows, forests and hills), so nobody starts worse.
 */
public final class MapGenerator {

    public record Layout(GameMap map, List<Pos> castles, List<Pos> villages) {
    }

    public static final int KINGDOMS = 7;
    public static final int WIDTH = 34;
    public static final int HEIGHT = 26;

    /** Home area around a castle, rows from -3 to +3: . meadow, f forest, h hills. */
    private static final String[] HOME = {
            ".......",
            ".ff....",
            ".f.....",
            "...C...",
            ".....h.",
            "....hh.",
            "......."
    };

    private MapGenerator() {
    }

    public static Layout generate(long seed) {
        int kingdoms = KINGDOMS;
        Random rnd = new Random(seed);
        int w = WIDTH;
        int h = HEIGHT;
        GameMap map = new GameMap(w, h);
        double[][] height = noise(rnd, w, h, 5);
        double[][] wet = noise(rnd, w, h, 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                Terrain t = Terrain.MEADOW;
                if (height[y][x] > 0.80) {
                    t = Terrain.MOUNTAIN;
                } else if (height[y][x] > 0.66) {
                    t = Terrain.HILLS;
                } else if (height[y][x] < 0.12) {
                    t = Terrain.LAKE;
                } else if (wet[y][x] > 0.62) {
                    t = Terrain.FOREST;
                }
                map.tile(x, y).terrain = t;
            }
        }
        int rivers = 1 + kingdoms / 3;
        for (int i = 0; i < rivers; i++) {
            river(rnd, map);
        }

        List<Pos> castles = new ArrayList<>();
        List<Pos> villages = new ArrayList<>();
        double cx = (w - 1) / 2.0;
        double cy = (h - 1) / 2.0;
        double rx = w / 2.0 - 4;
        double ry = h / 2.0 - 4;
        double offset = rnd.nextDouble() * Math.PI * 2;
        for (int i = 0; i < kingdoms; i++) {
            double a = offset + 2 * Math.PI * i / kingdoms;
            int x = (int) Math.round(cx + rx * Math.cos(a));
            int y = (int) Math.round(cy + ry * Math.sin(a));
            Pos c = new Pos(x, y);
            stampHome(map, c);
            castles.add(c);
            double dx = cx - x;
            double dy = cy - y;
            double len = Math.max(1e-9, Math.sqrt(dx * dx + dy * dy));
            Pos v = new Pos((int) Math.round(x + 3 * dx / len), (int) Math.round(y + 3 * dy / len));
            if (c.distance(v) < 3) {
                v = new Pos(x + (dx >= 0 ? 3 : -3), y);
            }
            map.tile(v).terrain = Terrain.MEADOW;
            villages.add(v);
        }
        return new Layout(map, castles, villages);
    }

    /** Places treasure ruins on free land away from every castle. */
    public static List<Pos> placeRuins(long seed, GameMap map, List<Pos> taken, int count) {
        Random rnd = new Random(seed ^ 0x5DEECE66DL);
        List<Pos> out = new ArrayList<>();
        int tries = 0;
        while (out.size() < count && tries++ < 2000) {
            Pos p = new Pos(rnd.nextInt(map.width), rnd.nextInt(map.height));
            Terrain t = map.tile(p).terrain;
            if (!t.passable || t == Terrain.RIVER) {
                continue;
            }
            if (taken.stream().anyMatch(c -> c.distance(p) < 4) || out.stream().anyMatch(c -> c.distance(p) < 3)) {
                continue;
            }
            map.tile(p).ruins = true;
            out.add(p);
        }
        return out;
    }

    private static void stampHome(GameMap map, Pos c) {
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                Pos p = new Pos(c.x() + dx, c.y() + dy);
                if (!map.inBounds(p)) {
                    continue;
                }
                char ch = HOME[dy + 3].charAt(dx + 3);
                map.tile(p).terrain = switch (ch) {
                    case 'f' -> Terrain.FOREST;
                    case 'h' -> Terrain.HILLS;
                    default -> Terrain.MEADOW;
                };
            }
        }
    }

    private static void river(Random rnd, GameMap map) {
        boolean vertical = rnd.nextBoolean();
        int x = vertical ? 2 + rnd.nextInt(map.width - 4) : 0;
        int y = vertical ? 0 : 2 + rnd.nextInt(map.height - 4);
        while (map.inBounds(new Pos(x, y))) {
            if (map.tile(x, y).terrain != Terrain.MOUNTAIN) {
                map.tile(x, y).terrain = Terrain.RIVER;
            }
            int bend = rnd.nextInt(5) - 2;
            if (vertical) {
                y++;
                if (Math.abs(bend) == 2) {
                    x = Math.max(0, Math.min(map.width - 1, x + Integer.signum(bend)));
                }
            } else {
                x++;
                if (Math.abs(bend) == 2) {
                    y = Math.max(0, Math.min(map.height - 1, y + Integer.signum(bend)));
                }
            }
        }
    }

    /** Smooth random field in [0,1): random values on a coarse grid, blended between grid points. */
    private static double[][] noise(Random rnd, int w, int h, int cell) {
        int gw = w / cell + 2;
        int gh = h / cell + 2;
        double[][] g = new double[gh][gw];
        for (double[] row : g) {
            for (int i = 0; i < gw; i++) {
                row[i] = rnd.nextDouble();
            }
        }
        double[][] out = new double[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double fx = (double) x / cell;
                double fy = (double) y / cell;
                int x0 = (int) fx;
                int y0 = (int) fy;
                double tx = smooth(fx - x0);
                double ty = smooth(fy - y0);
                double a = g[y0][x0] * (1 - tx) + g[y0][x0 + 1] * tx;
                double c = g[y0 + 1][x0] * (1 - tx) + g[y0 + 1][x0 + 1] * tx;
                out[y][x] = a * (1 - ty) + c * ty;
            }
        }
        return out;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }
}
