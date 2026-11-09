package pl.kingscastles.engine.model;

import pl.kingscastles.engine.model.Enums.TreatyType;

/**
 * An agreement between two kingdoms. A pact ends on its own after a few turns;
 * a broken treaty still holds until {@code endsAfterTurn}.
 */
public class Treaty {
    public final TreatyType type;
    public final String a;
    public final String b;
    public final int signedTurn;
    /** Last turn the treaty is in force, or Integer.MAX_VALUE. */
    public int endsAfterTurn;

    public Treaty(TreatyType type, String a, String b, int signedTurn, int endsAfterTurn) {
        this.type = type;
        this.a = a;
        this.b = b;
        this.signedTurn = signedTurn;
        this.endsAfterTurn = endsAfterTurn;
    }

    public boolean involves(String k) {
        return a.equals(k) || b.equals(k);
    }

    public boolean between(String x, String y) {
        return (a.equals(x) && b.equals(y)) || (a.equals(y) && b.equals(x));
    }

    public String other(String k) {
        return a.equals(k) ? b : a;
    }

    public boolean activeIn(int turn) {
        return turn <= endsAfterTurn;
    }
}
