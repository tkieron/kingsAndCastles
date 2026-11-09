# Kings & Castles

A simple turn-based strategy and economy game for 4th-grade kids, played in the browser (PWA) on a computer or a phone.

- `engine/` – game rules in pure Java 21 (map, economy, orders, 9-phase turn resolution)
- `server/` – Spring Boot server holding game state (coming next)
- `client/` – TypeScript + Canvas PWA (coming next)

## Engine

```
mvn test
```

`Game.create(setup)` makes a map for 2–7 kingdoms, `game.submit(kingdom, orders)` checks and stores a
kingdom's orders for the turn, `game.endTurn()` carries out everyone's orders together and returns the
turn report (Chronicle events with translation keys, each kingdom's Ledger, prestige).

A turn is resolved in fixed phases, so the result never depends on who clicked first:
0 reserve gold · 1 deals · 2 gifts · 3 market · 4 move (step by step, meetings fight) · 5 attacks (fastest first)
· 6 capture · 7 build and recruit · 8 money and food · 9 next turn and victory check.

All economic numbers are in `Balance.java`.
