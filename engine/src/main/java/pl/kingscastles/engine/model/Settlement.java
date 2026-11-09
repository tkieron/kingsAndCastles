package pl.kingscastles.engine.model;

import java.util.EnumSet;
import java.util.Set;

/** A castle (capital, one per kingdom) or a village. */
public class Settlement {
    public final int id;
    public final String name;
    public final Pos pos;
    public final boolean castle;
    /** The kingdom the castle belongs to never changes; a village can change hands. */
    public String owner;
    public int families;
    public final Set<BuildingType> buildings = EnumSet.noneOf(BuildingType.class);

    public Settlement(int id, String name, Pos pos, boolean castle, String owner, int families) {
        this.id = id;
        this.name = name;
        this.pos = pos;
        this.castle = castle;
        this.owner = owner;
        this.families = families;
    }

    /** Room for families: 5, or 10 once the village has a Farm and an Inn (castles always 10). */
    public int capacity() {
        if (castle || (buildings.contains(BuildingType.FARM) && buildings.contains(BuildingType.INN))) {
            return 10;
        }
        return 5;
    }
}
