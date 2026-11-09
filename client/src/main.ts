import './style.css';
import { api, Connection, saveSeat, type Seat, type ServerMessage } from './api';
import { h, installTooltips } from './dom';
import { getLang, setLang, t } from './i18n';
import type { Rules } from './types';
import { GameScreen } from './ui/GameScreen';
import { homeScreen } from './ui/HomeScreen';
import { LobbyScreen } from './ui/LobbyScreen';

const app = document.getElementById('app')!;
let rules: Rules | null = null;
let conn: Connection | null = null;
let game: GameScreen | null = null;
let lobby: LobbyScreen | null = null;
let seat: Seat | null = null;

function clear(): void {
  game?.destroy();
  game = null;
  lobby = null;
  app.replaceChildren();
}

function home(): void {
  conn?.close();
  conn = null;
  seat = null;
  clear();
  app.append(homeScreen(rules, enter, home));
}

function leave(): void {
  saveSeat(null);
  home();
}

/** Goes into a room: one live connection decides whether we see the lobby or the game. */
function enter(s: Seat): void {
  conn?.close();
  seat = s;
  clear();
  app.append(h('p', { class: 'loading' }, t('error.sayHelloFirst')));
  conn = new Connection(s, onMessage, (on) => game?.setOnline(on));
}

async function onMessage(m: ServerMessage): Promise<void> {
  if (m.type === 'lobby') {
    if (!lobby) {
      clear();
      lobby = new LobbyScreen(seat!, enter, leave);
      app.append(lobby.root);
    }
    lobby.update(m.lobby, !!m.host);
    return;
  }
  if (m.type === 'state' && !game) {
    clear();
    rules ??= await api.rules();
    game = new GameScreen(rules, conn!, leave);
    app.append(game.root);
  }
  if (m.type === 'error' && !game && !lobby) {
    // the saved seat is no longer valid (old room, server reset): back to the start
    saveSeat(null);
    home();
    app.prepend(h('p', { class: 'bad center' }, t(m.error)));
    return;
  }
  game?.message(m);
}

async function boot(): Promise<void> {
  setLang(getLang());
  installTooltips();
  try {
    rules = await api.rules();
  } catch {
    rules = null;
  }
  home();
  if (rules === null) app.prepend(h('p', { class: 'bad center' }, t('error.network')));
}

if ('serviceWorker' in navigator && import.meta.env.PROD) {
  navigator.serviceWorker.register('./sw.js').catch(() => undefined);
}

void boot();
