import type { GameState, Lobby, Order, Problem, RoomSettings, Rules } from './types';

/**
 * Where the game server lives. ?server=… in the address wins, then the build setting, then this computer.
 * "local" means no server at all: the game runs inside this page (see src/local).
 */
export function serverUrl(): string {
  const fromQuery = new URLSearchParams(location.search).get('server');
  if (fromQuery) {
    remember('kc.server', fromQuery);
    return fromQuery.replace(/\/$/, '');
  }
  const saved = recall('kc.server');
  if (saved) return saved.replace(/\/$/, '');
  const built = import.meta.env.VITE_SERVER_URL as string | undefined;
  if (built) return built.replace(/\/$/, '');
  return location.hostname === 'localhost' || location.hostname === '127.0.0.1' ? 'http://localhost:8080' : location.origin;
}

export class ApiError extends Error {
  constructor(public readonly key: string) {
    super(key);
  }
}

/** True for the offline version: rooms, rules and computer kings all live in this page. */
export function isLocal(): boolean {
  return serverUrl() === 'local';
}

type LocalModule = typeof import('./local/LocalServer');
let localModule: Promise<LocalModule> | null = null;

/** Loads the in-page server (and the game engine with it) only when it is needed. */
function local(): Promise<LocalModule> {
  localModule ??= import('./local/LocalServer');
  return localModule;
}

async function callLocal<T>(fn: (m: LocalModule) => Promise<T>): Promise<T> {
  const m = await local();
  try {
    return await fn(m);
  } catch (e) {
    throw new ApiError(e instanceof m.LocalError ? e.key : 'error.badRequest');
  }
}

async function call<T>(method: 'GET' | 'POST', path: string, body?: unknown, headers: Record<string, string> = {}): Promise<T> {
  let res: Response;
  try {
    res = await fetch(serverUrl() + path, {
      method,
      headers: { 'Content-Type': 'application/json', ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError('error.network');
  }
  const text = await res.text();
  const data = text ? JSON.parse(text) : undefined;
  if (!res.ok) throw new ApiError(data?.error ?? 'error.badRequest');
  return data as T;
}

export const api = {
  rules: () => isLocal()
    ? callLocal((m) => m.localApi.rules())
    : call<Rules>('GET', '/api/rules'),
  create: (settings: RoomSettings) => isLocal()
    ? callLocal((m) => m.localApi.create(settings))
    : call<{ code: string; hostToken: string }>('POST', '/api/rooms', settings),
  lobby: (code: string) => isLocal()
    ? callLocal((m) => m.localApi.lobby(code))
    : call<Lobby>('GET', `/api/rooms/${code}`),
  /** realm: the kingdom's name (empty: the kingdom keeps its own name); avatar: the chosen picture, 0-9 */
  join: (code: string, name: string, realm = '', avatar = 0) => isLocal()
    ? callLocal((m) => m.localApi.join(code, name, realm, avatar))
    : call<{ token: string; seat: number; kingdom: string }>('POST', `/api/rooms/${code}/join`, { name, realm, avatar }),
  start: (code: string, hostToken: string) => isLocal()
    ? callLocal((m) => m.localApi.start(code, hostToken))
    : call<Lobby>('POST', `/api/rooms/${code}/start`, undefined, { 'X-Host-Token': hostToken }),
};

// ------------------------------------------------------------------ remembered seats

export interface Seat {
  code: string;
  token?: string;
  hostToken?: string;
}

function remember(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* private mode: the game still works, it just forgets on reload */
  }
}

function recall(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

/** The last room this device played in, so a reload or a lost connection brings the child back. */
export function savedSeat(): Seat | null {
  const raw = recall('kc.seat');
  if (!raw) return null;
  try {
    return JSON.parse(raw) as Seat;
  } catch {
    return null;
  }
}

export function saveSeat(seat: Seat | null): void {
  if (seat) remember('kc.seat', JSON.stringify(seat));
  else {
    try {
      localStorage.removeItem('kc.seat');
    } catch {
      /* ignore */
    }
  }
}

// ------------------------------------------------------------------ live connection

export type ServerMessage =
  | { type: 'lobby'; lobby: Lobby; host?: boolean }
  | { type: 'state'; state: GameState; host?: boolean }
  | { type: 'ready'; ready: string[] }
  | { type: 'problems'; problems: Problem[] }
  | { type: 'ordersOk' }
  | { type: 'error'; error: string }
  | { type: 'pong' };

/** What a game screen talks to: the WebSocket to the server, or the in-page server. */
export interface Link {
  orders(orders: Order[]): void;
  ready(ready: boolean): void;
  endTurn(): void;
  close(): void;
}

/** Opens the live connection for a seat: to the server, or to the in-page server in the offline version. */
export function connect(seat: Seat, onMessage: (m: ServerMessage) => void, onOnline: (online: boolean) => void): Link {
  if (!isLocal()) return new Connection(seat, onMessage, onOnline);
  let link: Link | null = null;
  let closed = false;
  const waiting: ((l: Link) => void)[] = [];
  void local().then((m) => {
    if (closed) return;
    link = new m.LocalConnection(seat, onMessage, onOnline);
    waiting.splice(0).forEach((f) => f(link!));
  });
  const use = (f: (l: Link) => void) => (link ? f(link) : waiting.push(f));
  return {
    orders: (o) => use((l) => l.orders(o)),
    ready: (r) => use((l) => l.ready(r)),
    endTurn: () => use((l) => l.endTurn()),
    close: () => {
      closed = true;
      link?.close();
    },
  };
}

/** Keeps a WebSocket open to the room, reconnecting after a lost connection (phones sleep a lot). */
export class Connection implements Link {
  private ws: WebSocket | null = null;
  private closed = false;
  private retry = 0;
  private pingTimer: number | undefined;

  constructor(
    private readonly seat: Seat,
    private readonly onMessage: (m: ServerMessage) => void,
    private readonly onOnline: (online: boolean) => void,
  ) {
    this.open();
  }

  private open(): void {
    const url = serverUrl().replace(/^http/, 'ws') + '/ws';
    const ws = new WebSocket(url);
    this.ws = ws;
    ws.onopen = () => {
      this.retry = 0;
      this.onOnline(true);
      ws.send(JSON.stringify({ type: 'hello', code: this.seat.code, token: this.seat.token, hostToken: this.seat.hostToken }));
      window.clearInterval(this.pingTimer);
      this.pingTimer = window.setInterval(() => this.send({ type: 'ping' }), 25_000);
    };
    ws.onmessage = (e) => this.onMessage(JSON.parse(e.data as string) as ServerMessage);
    ws.onclose = () => {
      window.clearInterval(this.pingTimer);
      if (this.closed) return;
      this.onOnline(false);
      const wait = Math.min(10_000, 500 * 2 ** this.retry++);
      window.setTimeout(() => this.open(), wait);
    };
  }

  private send(msg: object): void {
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(msg));
  }

  orders(orders: Order[]): void {
    this.send({ type: 'orders', orders });
  }

  ready(ready: boolean): void {
    this.send({ type: 'ready', ready });
  }

  endTurn(): void {
    this.send({ type: 'endTurn' });
  }

  close(): void {
    this.closed = true;
    this.ws?.close();
  }
}
