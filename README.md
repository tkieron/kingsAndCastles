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

One turn is one move. At the start of a turn a kingdom first answers treaty offers (accept or decline, free), then
picks one kind of move (army, buildings, economy or diplomacy) and gives one order of that kind
(`Balance.actionsPerTurn`, 1). An Army move also walks or attacks with every army at once; training an army or
founding a village is the counted move. Computer kings play by the same rule. The client shows the offers, and news that
someone broke a treaty with you, as a card before the move.

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
| `KC_ALLOWED_ORIGINS` | `*` | Other addresses allowed to call the API and WebSocket, e.g. `https://kc.pointsapp.pl` |
| `KC_CLIENT_DIR` | (empty) | Folder with the built game page (`client/dist`); the server then serves the game too |
| `KC_MAX_ROOMS` | `500` | At most this many rooms at once |
| `KC_ROOM_IDLE_DAYS` | `14` | A room nobody touched for this many days is removed |
| `KC_PAUSED_ROOM_DAYS` | `30` | A frozen (paused) game is kept this many days instead |
| `KC_AUTO_PAUSE_SECONDS` | `90` | A game with a turn clock freezes itself when no screen has been connected this long |

### Freezing a game

Any child in the room or the host can freeze a running game (❄ in the top bar). The turn clock stops, the
game is saved with this turn's orders, and Ready and new orders wait. Pressing Resume gives the turn its full time
again; children who were ready press Ready once more. A game with a turn clock also freezes itself when no screen
has been connected for `KC_AUTO_PAUSE_SECONDS`, so nobody's turns run out while everyone is away (a reload is
shorter than that and changes nothing). A frozen game is kept for `KC_PAUSED_ROOM_DAYS` instead of `KC_ROOM_IDLE_DAYS`.

A saved room keeps only the seed and the children's orders, so after a restart every game is rebuilt
exactly by replaying them (computer kings decide the same way again).

### REST

| Call | Who | What |
|---|---|---|
| `POST /api/rooms` (body: settings, optional) | host | Creates a room, returns `code` and `hostToken` |
| `GET /api/rooms/{code}` | anyone | Lobby: the seven seats and who sits where |
| `POST /api/rooms/{code}/join` `{"name":"Ania"}` | child | Takes the first free child seat, returns the player `token` |
| `POST /api/rooms/{code}/resume` `{"resumeCode":"K7MQ-4QX2"}` | child or host on another device | Returns the `token` (and `hostToken` for the host) that the return code belongs to |
| `GET /api/rooms/{code}/resume-codes` | child (`X-Player-Token`) or host | This screen's own return code |
| `POST /api/rooms/{code}/start` | host (`X-Host-Token`) | Starts the game; empty child seats go to the computer |
| `POST /api/rooms/{code}/end-turn` | host | Ends the turn now |
| `GET /api/rooms/{code}/state` | child (`X-Player-Token`) | The game as this child sees it |
| `POST /api/rooms/{code}/orders` | child | Replaces this turn's orders, returns problems (empty = OK) |
| `POST /api/rooms/{code}/ready` `{"ready":true}` | child | "I'm done"; the turn ends when every child is ready |
| `POST /api/rooms/{code}/pause` `{"paused":true}` | child or host | Freezes the game (`false` carries on) |

Errors come back as `400 {"error":"error.key"}`; the client translates the key.

### Playing on another device

Every child gets a return code when they join (8 letters and digits, e.g. `K7MQ-4QX2`, no 0/O or 1/I/L), and the
host gets one for the host's screen. The 📱 button in the game, the lobby and the frozen-game card shows it with a QR
code of the link `#room=123456&resume=K7MQ-4QX2`; scanning it, or typing the room code and the return code under
"Back to your game", brings the child back to their kingdom on any device. A host who joins as a player from the
host's screen needs only their own code: it also gives back the host's buttons. No accounts and no personal data.

Guessing is stopped in each room: one device (by its address, `CF-Connecting-IP` behind Cloudflare) gets 10 wrong
codes per 15 minutes, all devices together 100; past that every code, the right one too, waits until the 15 minutes
are over. Text that cannot be a code (empty, wrong length) is not counted. The link carries the code after `#`, so it
never reaches server or proxy logs. The codes are never in the lobby or the game state, only in the answer to that
screen's own token. Rooms saved before this change get codes when the server loads them.

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
npm run build        # dist/ is served by the Java server (KC_CLIENT_DIR)
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

## Deployment

The Java server also serves the built game page (`KC_CLIENT_DIR=client/dist`), so the whole game lives at one
address, https://kc.pointsapp.pl. Every change on `main` is tested and deployed to a small VPS (mikr.us), as a Docker container behind the Caddy that already serves pointsapp
and Cloudflare by `.github/workflows/deploy.yml`. Setup and the secrets it needs (in Polish) are in
[`deploy/README.md`](deploy/README.md).

To run the server version on your own computer:

```bash
mvn -B package
cd client && npm ci && npm run build && cd ..
KC_CLIENT_DIR=client/dist java -jar server/target/server-0.1.0-SNAPSHOT.jar   # http://localhost:8080
cd client && npm run e2e      # plays a two-child game in a real browser against that server
```
