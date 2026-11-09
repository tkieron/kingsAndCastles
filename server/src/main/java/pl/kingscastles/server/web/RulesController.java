package pl.kingscastles.server.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.kingscastles.engine.Balance;
import pl.kingscastles.engine.Game;
import pl.kingscastles.engine.model.BuildingType;
import pl.kingscastles.engine.model.UnitType;

import java.util.Arrays;
import java.util.List;

/** The numbers of the game, so the client shows the same prices and strengths the server uses. */
@RestController
public class RulesController {

    public record Building(BuildingType type, int cost, BuildingType.Place place) {
    }

    public record Unit(UnitType type, int cost, int upkeep, int attack, int defense, int move, boolean needsBarracks) {
    }

    public record Rules(List<Building> buildings, List<Unit> units, List<Game.KingdomPreset> kingdoms, Balance balance) {
    }

    private static final Rules RULES = new Rules(
            Arrays.stream(BuildingType.values()).map(b -> new Building(b, b.cost, b.place)).toList(),
            Arrays.stream(UnitType.values())
                    .map(u -> new Unit(u, u.cost, u.upkeep, u.attack, u.defense, u.move, u.needsBarracks)).toList(),
            Game.PRESETS,
            new Balance());

    @GetMapping("/api/rules")
    public Rules rules() {
        return RULES;
    }
}
