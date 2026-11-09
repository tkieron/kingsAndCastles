import * as engine from 'kc-engine';
import type { ServerMessage } from '../api';
import type { Controller, GameState, Lobby, Order, Problem, RoomSettings, Rules, Seat, Status } from '../types';

/**
 * The game server, living inside the page: rooms, seats, Ready and the turn clock, like server/.../RoomService.java.
 * The rules and the computer kings come from the real Java engine compiled to JavaScript, so a game here plays
 * exactly like on the server. The room is saved on this device (seed + children's orders) and replayed on reload.
 */

export class LocalError extends Error {
  constructor(public readonly key: string) {
    super(key);
  }
}

interface Player { token: string; name: string; seat: number }
interface TurnRecord { orders: Record<string, Order[]>; present: string[] }
interface RoomRecord {
  code: string;
  hostToken: string;
  seed: number;
  status: Status;
  settings: RoomSettings;
  players: Player[];
  turns: TurnRecord[];
}

type Listener = { token?: string; hostToken?: string; send: (m: ServerMessage) => void };

const SAVE_KEY = 'kc.local.room';
const TURN_LENGTHS = [0, 30, 60, 90, 120, 180];

let rulesCache: Rules | null = null;
let room: RoomRecord | null = null;
let ready = new Set<string>();
let turnEndsAt: number | undefined;
let timer: number | undefined;
let loaded = false;
const listeners = new Set<Listener>();

function save(): void {
  try {
    localStorage.setItem(SAVE_KEY, JSON.stringify(room));
  } catch {
    /* private mode: the game still works, it just forgets on reload */
  }
}

function load(): void {
  if (loaded) return;
  loaded = true;
  try {
    const raw = localStorage.getItem(SAVE_KEY);
    if (!raw) return;
    room = JSON.parse(raw) as RoomRecord;
    if (room.status !== 'LOBBY') {
      replay(room);
      if (room.status === 'PLAYING') startClock();
    }
  } catch {
    room = null;
  }
}

function token(): string {
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}

function kingdomOfSeat(seat: number): string {
  return rules().kingdoms[seat].id;
}

function setupJson(r: RoomRecord): string {
  const seats = r.settings.seats.map((c, i) => {
    const p = r.players.find((x) => x.seat === i);
    if (c === 'HUMAN' && p) return { name: p.name, controller: 'HUMAN' };
    if (c === 'HUMAN') return { name: 'Computer', controller: r.settings.emptySeatLevel };
    return { name: 'Computer', controller: c };
  });
  return JSON.stringify({ seed: r.seed, maxTurns: r.settings.maxTurns, seats });
}

/** Rebuilds the game from its seed and the saved orders, as the server does after a restart. */
function replay(r: RoomRecord): void {
  engine.newGame(setupJson(r));
  for (const t of r.turns) {
    for (const [k, o] of Object.entries(t.orders)) engine.submit(k, JSON.stringify(o));
    t.present.forEach((k) => engine.markPresent(k));
    engine.endTurn();
  }
}

function requireRoom(code: string): RoomRecord {
  load();
  if (!room || room.code !== code) throw new LocalError('error.noSuchRoom');
  return room;
}

function requireHost(r: RoomRecord, hostToken?: string): void {
  if (!hostToken || r.hostToken !== hostToken) throw new LocalError('error.notHost');
}

function playingKingdom(r: RoomRecord, tok?: string): string {
  if (r.status !== 'PLAYING') throw new LocalError('error.notPlaying');
  const p = r.players.find((x) => x.token === tok);
  if (!p) throw new LocalError('error.notInRoom');
  return kingdomOfSeat(p.seat);
}

function lobbyView(r: RoomRecord): Lobby {
  const seats: Seat[] = r.settings.seats.map((controller, i) => {
    const k = rules().kingdoms[i];
    const player = r.players.find((p) => p.seat === i)?.name;
    return { seat: i, kingdom: k.id, name: k.name, color: k.color, controller, ...(player ? { player } : {}) };
  });
  return { code: r.code, status: r.status, settings: r.settings, seats };
}

function stateView(r: RoomRecord, me: string | null): GameState {
  const v = JSON.parse(engine.view(me)) as Omit<GameState, 'code' | 'status' | 'turnSeconds' | 'ready'>;
  return {
    ...v,
    code: r.code,
    status: r.status,
    turnEndsAt,
    turnSeconds: r.settings.turnSeconds,
    ready: [...ready],
  };
}

function viewFor(l: Listener): ServerMessage {
  const r = room!;
  const host = !!l.hostToken && l.hostToken === r.hostToken;
  if (r.status === 'LOBBY') return { type: 'lobby', lobby: lobbyView(r), ...(host ? { host } : {}) };
  const p = r.players.find((x) => x.token === l.token);
  return { type: 'state', state: stateView(r, p ? kingdomOfSeat(p.seat) : null), ...(host ? { host } : {}) };
}

function fire(change: 'view' | 'ready'): void {
  // a moment later, like a message arriving over the network, so the screen never updates mid-click
  window.setTimeout(() => {
    for (const l of listeners) {
      l.send(change === 'view' ? viewFor(l) : { type: 'ready', ready: [...ready] });
    }
  }, 0);
}

function startClock(): void {
  window.clearTimeout(timer);
  const seconds = room!.settings.turnSeconds;
  if (seconds <= 0) {
    turnEndsAt = undefined;
    return;
  }
  turnEndsAt = Date.now() + seconds * 1000;
  const turn = engine.turn();
  timer = window.setTimeout(() => endTurn(turn), seconds * 1000);
}

function endTurn(turn: number): void {
  const r = room;
  if (!r || r.status !== 'PLAYING' || engine.turn() !== turn) return;
  window.clearTimeout(timer);
  r.turns.push({ orders: JSON.parse(engine.humanOrders()), present: JSON.parse(engine.present()) });
  engine.endTurn();
  ready = new Set();
  if (engine.finished()) {
    r.status = 'FINISHED';
    turnEndsAt = undefined;
  } else {
    startClock();
  }
  save();
  fire('view');
}

export function rules(): Rules {
  rulesCache ??= JSON.parse(engine.rules()) as Rules;
  return rulesCache;
}

export const localApi = {
  async rules(): Promise<Rules> {
    return rules();
  },

  /** One room per device: a new room replaces the old one. */
  async create(settings: RoomSettings): Promise<{ code: string; hostToken: string }> {
    load();
    if (!TURN_LENGTHS.includes(settings.turnSeconds) || settings.maxTurns < 10 || settings.maxTurns > 30
      || settings.seats?.length !== 7) {
      throw new LocalError('error.badRequest');
    }
    const emptySeatLevel: Controller = settings.emptySeatLevel === 'HUMAN' ? 'AI_BEGINNER' : settings.emptySeatLevel;
    window.clearTimeout(timer);
    turnEndsAt = undefined;
    ready = new Set();
    room = {
      code: String(Math.floor(Math.random() * 1_000_000)).padStart(6, '0'),
      hostToken: token(),
      seed: Math.floor(Math.random() * 2 ** 52),
      status: 'LOBBY',
      settings: { ...settings, emptySeatLevel },
      players: [],
      turns: [],
    };
    save();
    return { code: room.code, hostToken: room.hostToken };
  },

  async lobby(code: string): Promise<Lobby> {
    return lobbyView(requireRoom(code));
  },

  async join(code: string, name: string): Promise<{ token: string; seat: number; kingdom: string }> {
    const r = requireRoom(code);
    const clean = (name ?? '').trim();
    if (!clean || clean.length > 20) throw new LocalError('error.badName');
    if (r.status !== 'LOBBY') throw new LocalError('error.gameStarted');
    const seat = r.settings.seats.findIndex((c, i) => c === 'HUMAN' && !r.players.some((p) => p.seat === i));
    if (seat < 0) throw new LocalError('error.roomFull');
    const p = { token: token(), name: clean, seat };
    r.players.push(p);
    save();
    fire('view');
    return { token: p.token, seat, kingdom: kingdomOfSeat(seat) };
  },

  async start(code: string, hostToken: string): Promise<Lobby> {
    const r = requireRoom(code);
    requireHost(r, hostToken);
    if (r.status !== 'LOBBY') throw new LocalError('error.gameStarted');
    r.status = 'PLAYING';
    engine.newGame(setupJson(r));
    ready = new Set();
    startClock();
    save();
    fire('view');
    return lobbyView(r);
  },
};

/** What the WebSocket does on the server, for one screen on this device. */
export class LocalConnection {
  private readonly listener: Listener;

  constructor(
    seat: { code: string; token?: string; hostToken?: string },
    onMessage: (m: ServerMessage) => void,
    onOnline: (online: boolean) => void,
  ) {
    this.listener = { token: seat.token, hostToken: seat.hostToken, send: onMessage };
    window.setTimeout(() => {
      try {
        const r = requireRoom(seat.code);
        const isPlayer = r.players.some((p) => p.token === seat.token);
        if (!isPlayer && r.hostToken !== seat.hostToken) throw new LocalError('error.notInRoom');
        listeners.add(this.listener);
        onOnline(true);
        onMessage(viewFor(this.listener));
      } catch (e) {
        onMessage({ type: 'error', error: e instanceof LocalError ? e.key : 'error.badRequest' });
      }
    }, 0);
  }

  private guard(fn: (r: RoomRecord) => void): void {
    try {
      fn(room!);
    } catch (e) {
      const error = e instanceof LocalError ? e.key : 'error.badRequest';
      window.setTimeout(() => this.listener.send({ type: 'error', error }), 0);
    }
  }

  orders(orders: Order[]): void {
    this.guard((r) => {
      const k = playingKingdom(r, this.listener.token);
      const problems = JSON.parse(engine.submit(k, JSON.stringify(orders))) as Problem[];
      window.setTimeout(() => this.listener.send(problems.length ? { type: 'problems', problems } : { type: 'ordersOk' }), 0);
    });
  }

  ready(isReady: boolean): void {
    this.guard((r) => {
      const k = playingKingdom(r, this.listener.token);
      const turn = engine.turn();
      engine.markPresent(k);
      if (isReady) ready.add(k);
      else ready.delete(k);
      const waiting = (JSON.parse(engine.waitingFor()) as string[]).filter((x) => !ready.has(x));
      fire('ready');
      if (waiting.length === 0) endTurn(turn);
    });
  }

  endTurn(): void {
    this.guard((r) => {
      requireHost(r, this.listener.hostToken);
      if (r.status !== 'PLAYING') throw new LocalError('error.notPlaying');
      endTurn(engine.turn());
    });
  }

  close(): void {
    listeners.delete(this.listener);
  }
}
