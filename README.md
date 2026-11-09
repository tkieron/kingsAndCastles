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

## Server

`server/` is a small Spring Boot app: rooms with a 6-digit code, the turn clock, and a WebSocket for live play.

```bash
mvn -B package
java -Xmx256m -jar server/target/server-0.1.0-SNAPSHOT.jar
```

Settings (environment variables):

| Variable | Default | What it does |
|---|---|---|
| `PORT` | `8080` | HTTP and WebSocket port |
| `KC_DATA_DIR` | `./data` | Folder where each room is saved as `<code>.json` after every change |
| `KC_ALLOWED_ORIGINS` | `*` | Where the PWA is served from, e.g. `https://tkieron.github.io` |

A saved room keeps only the seed and the children's orders, so after a restart every game is rebuilt
exactly by replaying them (computer kings decide the same way again).

### REST

| Call | Who | What |
|---|---|---|
| `POST /api/rooms` (body: settings, optional) | host | Creates a room, returns `code` and `hostToken` |
| `GET /api/rooms/{code}` | anyone | Lobby: the seven seats and who sits where |
| `POST /api/rooms/{code}/join` `{"name":"Ania"}` | child | Takes the first free child seat, returns the player `token` |
| `POST /api/rooms/{code}/start` | host (`X-Host-Token`) | Starts the game; empty child seats go to the computer |
| `POST /api/rooms/{code}/end-turn` | host | Ends the turn now |
| `GET /api/rooms/{code}/state` | child (`X-Player-Token`) | The game as this child sees it |
| `POST /api/rooms/{code}/orders` | child | Replaces this turn's orders, returns problems (empty = OK) |
| `POST /api/rooms/{code}/ready` `{"ready":true}` | child | "I'm done"; the turn ends when every child is ready |

Errors come back as `400 {"error":"error.key"}`; the client translates the key.

### WebSocket `/ws`

The client sends `{"type":"hello","code":"123456","token":"…"}` (or `hostToken` for the host's screen), then
`orders`, `ready` and `endTurn`. The server pushes `lobby`, `state` (after every turn, with the Chronicle
and the child's own Ledger), `ready`, `problems`, `ordersOk` and `error`. Details are in `GameSocket.java`.
