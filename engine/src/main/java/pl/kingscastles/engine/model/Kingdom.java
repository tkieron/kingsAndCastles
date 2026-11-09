package pl.kingscastles.engine.model;

import pl.kingscastles.engine.model.Enums.Controller;
import pl.kingscastles.engine.model.Enums.Mood;
import pl.kingscastles.engine.model.Enums.TaxRate;

public class Kingdom {
    public final String id;
    public final String name;
    public final String color;
    public String playerName;
    public Controller controller;

    public int gold;
    public int food;
    public TaxRate tax = TaxRate.LOW;
    public Mood mood = Mood.NORMAL;

    /** Gold still to pay back on the loan (0 = no loan). */
    public int loanLeft;
    /** Kingdom this one is a vassal of, or null. */
    public String overlord;
    /** Turn until which the kingdom carries the "broken promise" mark. */
    public int brokenPromiseUntil;
    public int brokenPromises;
    /** Great Palace installments paid so far. */
    public int palacePaid;
    public boolean palaceDone;
    /** Turns in a row with no orders, for the absent-player rule. */
    public int idleTurns;
    /** A computer plays for an absent player until they come back. */
    public boolean autopilot;

    public Kingdom(String id, String name, String color, Controller controller) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.controller = controller;
    }

    public boolean isHuman() {
        return controller == Controller.HUMAN;
    }
}
