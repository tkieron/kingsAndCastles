package pl.kingscastles.engine.turn;

import pl.kingscastles.engine.Balance;
import pl.kingscastles.engine.model.Enums.Mood;
import pl.kingscastles.engine.model.GameState;
import pl.kingscastles.engine.model.Kingdom;
import pl.kingscastles.engine.model.Pos;
import pl.kingscastles.engine.model.Settlement;
import pl.kingscastles.engine.model.Territory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Prestige points and the three ways to win. */
public final class Scoring {

    private Scoring() {
    }

    public static int prestige(GameState s, String kingdomId) {
        Balance b = s.balance;
        Kingdom k = s.kingdom(kingdomId);
        int p = Math.max(0, k.gold) / b.prestigeGoldPer;
        for (Settlement st : s.settlementsOf(kingdomId)) {
            p += st.families * b.prestigePerFamily;
            p += st.buildings.size() * b.prestigePerBuilding;
            p += b.prestigePerSettlement;
        }
        p += countTileImprovements(s, kingdomId) * b.prestigePerBuilding;
        if (k.mood == Mood.HAPPY) {
            p += b.prestigeHappy;
        }
        p += s.alliesOf(kingdomId).size() * b.prestigePerAlly;
        if (k.palaceDone) {
            p += b.prestigePalace;
        }
        p += k.brokenPromises * b.prestigeBrokenPromise;
        p -= k.loanLeft / 10;
        return p;
    }

    private static int countTileImprovements(GameState s, String kingdomId) {
        Territory territory = Territory.of(s);
        int n = 0;
        for (int y = 0; y < s.map.height; y++) {
            for (int x = 0; x < s.map.width; x++) {
                Pos pos = new Pos(x, y);
                if (s.map.tile(pos).improvement != null && territory.owns(kingdomId, pos)) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * Checks for an early win (economic or military) or the end of the last turn.
     * Sets {@code finished}, {@code winners} and {@code winReason} on the state.
     */
    public static void checkVictory(GameState s) {
        Balance b = s.balance;
        for (Kingdom k : s.kingdoms.values()) {
            if (k.palaceDone && k.gold >= b.economicWinGold) {
                finish(s, k.id, "economic");
                return;
            }
        }
        for (Kingdom k : s.kingdoms.values()) {
            if (k.overlord == null && 1 + s.vassalsOf(k.id).size() >= b.militaryWinKingdoms) {
                finish(s, k.id, "military");
                return;
            }
        }
        if (s.turn > s.maxTurns) {
            Optional<Kingdom> best = s.kingdoms.values().stream()
                    .max(Comparator.comparingInt((Kingdom k) -> prestige(s, k.id)));
            best.ifPresent(k -> finish(s, k.id, "prestige"));
        }
    }

    private static void finish(GameState s, String winner, String reason) {
        s.finished = true;
        s.winReason = reason;
        List<String> w = new ArrayList<>();
        w.add(winner);
        w.addAll(s.alliesOf(winner));
        s.winners.clear();
        s.winners.addAll(w);
    }
}
