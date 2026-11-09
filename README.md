# Kings & Castles

A simple turn-based strategy and economy game for 4th-grade kids, played in the browser (PWA) on a computer or a phone.

- `engine/` – game rules in pure Java 21 (map, economy, orders, 9-phase turn resolution)
- `server/` – Spring Boot server holding game state (coming next)
- `client/` – TypeScript + Canvas PWA
- `browser/` – the engine compiled to JavaScript (TeaVM), for the offline version with no server

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

## Client

`client/` is the game in the browser: TypeScript and a Canvas map, no framework. It installs as an app (PWA)
on phones and computers. All pixel art is drawn in code (`client/src/game/sprites.ts`), so there are no image files
except the app icon, which `scripts/make-icons.mjs` draws during the build.

```bash
cd client
npm install
npm run dev          # http://localhost:5173, talks to the server on http://localhost:8080
npm test             # rules and texts
npm run build        # dist/ is ready for GitHub Pages
```

The server address comes from `?server=https://…` in the page address (remembered on the device), or from
`VITE_SERVER_URL` at build time.

The child is never left wondering what to do: Grosik, the treasurer, shows one next step at a time above the map
(build a market, a farm, a gold mine, walk to the ruins, end the turn), with a blinking yellow frame on the map and
"Show me", which selects the right castle, village, field or army. The suggestions come from `src/game/advisor.ts`
and are only hints: the child can give any order. The first game starts with three short cards (story, goal,
Grosik); the full tour of the screen is behind the "?" button. After every turn a short card shows gold and food
before and after, and what happened to the child's kingdom.

Languages: English with Polish tooltips (hover with the mouse, or hold a finger on a dotted word), or the full
Polish version whose tooltips show the English word. Every text the server can send has a translation; a test
reads the engine sources to make sure.

Orders are JSON with an `order` field, for example `{"order":"build","settlementId":3,"building":"MARKET"}`.

## Offline version (no server)

The whole game can also run inside one web page: the Java engine is compiled to JavaScript with
[TeaVM](https://teavm.org) (`browser/`), and `client/src/local/` plays the server's part (rooms, Ready, the turn
clock), so the rules and the computer kings are exactly the same as on the server. One child plays against six
computer kings; the game is kept on the device and comes back after a reload.

```bash
mvn -B -pl browser -am package     # browser/target/js/kc-engine.js
cd client
npm run build:offline              # dist-offline/kings-and-castles-offline.html
```

The result is a single HTML file that opens straight from the disk (double click), with no internet needed except
for the web fonts. Any build of the client that includes the engine also plays offline with `?server=local` in the
page address.
