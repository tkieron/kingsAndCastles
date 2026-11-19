package pl.kingscastles.engine;

/**
 * Every economic number in one place, so balancing after test games means editing one file.
 * Unit and building costs live on {@link pl.kingscastles.engine.model.UnitType} and
 * {@link pl.kingscastles.engine.model.BuildingType}. Values follow the game design document.
 */
public class Balance {
    /**
     * The rules a new game is played by. A game saved earlier is replayed from its orders, so it keeps the
     * rules it started with ({@link #forVersion}); change this number whenever a change here alters replays.
     */
    public static final int VERSION = 1;

    /** The numbers a game of this rules version was played with. 0 = before longer games (November 2026). */
    public static Balance forVersion(int version) {
        Balance b = new Balance();
        if (version < 1) {
            b.ordersPerSettlement = 0;
            b.goldMineIncome = 10;
            b.palaceInstallments = 5;
            b.economicWinGold = 300;
        }
        return b;
    }

    // the turn
    /**
     * Orders a kingdom may give in one turn, all of one kind (army, buildings, economy or diplomacy).
     * Answers to treaty offers do not count. One move per turn keeps the game simple for children.
     */
    public int actionsPerTurn = 1;
    /**
     * In a Buildings or Army move every settlement (castle or village) may give this many orders, so a rich
     * kingdom with more settlements has more to spend its gold on. 0 keeps a move to {@link #actionsPerTurn} orders.
     */
    public int ordersPerSettlement = 1;

    // start
    public int startGold = 100;
    public int startFood = 10;
    public int startCastleFamilies = 3;
    public int startVillageFamilies = 3;
    public int newVillageFamilies = 2;

    // income
    public int castleIncome = 10;
    public int forestGold = 1;
    public int goldMineIncome = 5;
    public int marketIncome = 5;
    public int tradeDealIncome = 5;
    public int interestPer100 = 5;
    public int interestMax = 20;
    public int sadTaxPenalty = 1;

    // food
    public int meadowFood = 2;
    public int riverFood = 1;
    public int lakeFood = 1;
    public int farmFood = 3;
    public int growthSurplus = 5;

    // loans
    public int loanAmount = 100;
    public int loanTotal = 120;
    public int loanInstallment = 20;

    // market
    public int foodPriceStart = 3;
    public int foodPriceMin = 1;
    public int foodPriceMax = 5;
    /** Net volume (sold minus bought) that moves the price by 1. */
    public int priceStepVolume = 5;

    // war and peace
    public int peaceTurns = 5;
    public int castleProtectedUntilTurn = 9;
    public int villageLoot = 20;
    public int vassalTribute = 10;
    public int freedomPrice = 150;
    public int pactTurns = 5;
    public int breakDelayTurns = 2;
    public int brokenPromiseMarkTurns = 5;
    public int maxAllianceSize = 3;

    // settlements
    public int territoryRadius = 2;
    public int settlerMinDistance = 3;

    // ruins
    public int ruinsGoldMin = 30;
    public int ruinsGoldMax = 50;

    // help for the weakest
    public int helpEveryTurns = 5;
    public int helpGold = 20;

    // events
    public int eventChancePercent = 20;

    // great palace and victory
    public int palaceInstallment = 100;
    public int palaceInstallments = 15;
    public int economicWinGold = 900;
    public int militaryWinKingdoms = 4;

    // absent players
    public int idleTurnsBeforeAi = 3;

    // prestige
    public int prestigeGoldPer = 10;
    public int prestigePerFamily = 2;
    public int prestigePerBuilding = 5;
    public int prestigePerSettlement = 10;
    public int prestigeHappy = 15;
    public int prestigePerAlly = 10;
    public int prestigePalace = 50;
    public int prestigeBrokenPromise = -20;
}
