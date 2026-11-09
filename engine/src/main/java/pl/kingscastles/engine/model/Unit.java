package pl.kingscastles.engine.model;

public class Unit {
    public final int id;
    public final String owner;
    public final UnitType type;
    public Pos pos;

    public Unit(int id, String owner, UnitType type, Pos pos) {
        this.id = id;
        this.owner = owner;
        this.type = type;
        this.pos = pos;
    }
}
