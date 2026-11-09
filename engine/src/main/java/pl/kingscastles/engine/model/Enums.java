package pl.kingscastles.engine.model;

/** Small enums kept together. */
public final class Enums {
    private Enums() {
    }

    public enum TaxRate {
        LOW(2, 1), MEDIUM(3, 0), HIGH(4, -1);

        public final int goldPerFamily;
        public final int moodEffect;

        TaxRate(int goldPerFamily, int moodEffect) {
            this.goldPerFamily = goldPerFamily;
            this.moodEffect = moodEffect;
        }
    }

    public enum Mood { HAPPY, NORMAL, SAD }

    public enum Controller { HUMAN, AI_BEGINNER, AI_ADVANCED, AI_PRO }

    public enum TreatyType { PACT, TRADE, ALLIANCE }
}
