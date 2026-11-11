import type { Link, ServerMessage } from '../api';
import { append, h, word } from '../dom';
import { t, type Key } from '../i18n';
import { MapCanvas, type Overlay } from '../game/MapCanvas';
import {
  buildOptions, buildingCost, distance, goldCost, improvementAt, key, nearestVillageSpot, neighbours, payback, reachable, samePos,
  terrainAt, territory, unitRule, villageSpot,
} from '../game/rules';
import { advise, armyStep, kindOf, KINDS, moveOf, saving, type Kind, type Step } from '../game/advisor';
import { inkOn } from '../game/sprites';
import { greeted, INTRO, markGreeted, TOUR, Tutorial, tutorialSeen } from './Tutorial';
import { grosikSvg } from './grosik';
import { avatarSvg, avatarWho } from '../game/avatars';
import { clock, eventText, kingdomName, ledgerText, orderText, placeName, playerLabel, season, setRealms } from '../game/text';
import type {
  BuildingType, ChronicleEvent, GameState, KingdomView, Order, Pos, ProposalView, Problem, Rules, SettlementView, TaxRate, TreatyType,
  UnitType, UnitView,
} from '../types';


type Tab = 'move' | 'ledger' | 'chronicle';
type Selection = { kind: 'unit'; id: number } | { kind: 'settlement'; id: number } | { kind: 'tile'; pos: Pos } | null;

const TABS: { id: Tab; word: Key }[] = [
  { id: 'move', word: 'w.yourMove' },
  { id: 'ledger', word: 'w.ledger' },
  { id: 'chronicle', word: 'w.chronicle' },
];
const KIND_ICON: Record<Kind, string> = { ARMY: '⚔️', BUILD: '🏰', ECONOMY: '💰', DIPLOMACY: '🤝' };
const TREATIES: TreatyType[] = ['PACT', 'TRADE', 'ALLIANCE'];
const TAXES: TaxRate[] = ['LOW', 'MEDIUM', 'HIGH'];

/** The main screen while a game runs: map on the left, the child's kingdom and orders on the right. */
export class GameScreen {
  readonly root = h('div', { class: 'game' });
  private readonly header = h('header', { class: 'topbar' });
  private readonly board = h('main', { class: 'board' });
  private readonly panel = h('aside', { class: 'panel' });
  private readonly mapBox = h('div', { class: 'map-scroll' });
  private readonly map: MapCanvas;
  private state?: GameState;
  private draft: Order[] = [];
  private problems: Problem[] = [];
  private saved = true;
  private online = true;
  private host = false;
  private tab: Tab = 'move';
  /** The kind of move the child picked for this turn (one turn, one move). */
  private kind?: Kind;
  /** The start of a turn: treaty offers and broken treaties are answered first. */
  private intro?: HTMLElement;
  private introTurn = 0;
  private tipNo = -1;
  private selection: Selection = null;
  private toast = '';
  private timeEl?: HTMLElement;
  private barEl?: HTMLElement;
  private ticker: number;
  private tutorial?: Tutorial;
  /** Grosik's ideas for this turn's move, most useful first. */
  private steps: Step[] = [];
  private readonly quest = h('section', { class: 'quest', 'aria-live': 'polite' });
  /** The choices for what the child clicked, right next to it on the map (a sheet from the bottom on a phone). */
  private readonly sheet = h('div', { class: 'sheet', role: 'dialog', 'aria-live': 'polite' });
  private readonly mapWrap = h('div', { class: 'map-wrap' });
  private summary?: HTMLElement;
  /** Tabs the child has opened, so a tab that appears later can say "New!" until then. */
  private readonly seenTabs = new Set<Tab>(['move', 'ledger', 'chronicle']);
  private blink = false;

  constructor(private readonly rules: Rules, private readonly conn: Link, private readonly onLeave: () => void) {
    this.map = new MapCanvas((p) => this.clickMap(p));
    this.mapBox.append(this.map.canvas);
    const zoom = h('div', { class: 'zoom' },
      h('button', { class: 'btn small', 'aria-label': t('game.zoomOut'), title: t('game.zoomOut'), onclick: () => this.zoom(-11) }, '−'),
      h('button', { class: 'btn small', 'aria-label': t('game.zoomIn'), title: t('game.zoomIn'), onclick: () => this.zoom(11) }, '+'));
    this.mapWrap.append(this.mapBox, zoom, this.sheet);
    this.board.append(this.quest, this.mapWrap, this.phases());
    this.mapBox.addEventListener('scroll', () => this.placeSheet(), { passive: true });
    window.addEventListener('keydown', this.onKey);
    this.root.append(this.header, h('div', { class: 'game-body' }, this.board, this.panel));
    this.map.setFieldSize(33);
    this.ticker = window.setInterval(() => this.tick(), 500);
  }

  private readonly onKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape' && this.selection && !this.summary && !this.tutorial && !this.intro) this.closeSheet();
  };

  destroy(): void {
    window.clearInterval(this.ticker);
    window.removeEventListener('keydown', this.onKey);
    this.tutorial?.close();
    this.summary?.remove();
    this.intro?.remove();
  }

  /**
   * The first time a child plays: three short cards (story, goal, Grosik), then straight into the game.
   * The "?" button opens the full tour of the screen.
   */
  private showTutorial(full: boolean): void {
    const me = this.me();
    if (!me || this.tutorial) return;
    const b = this.rules.balance;
    this.tutorial = new Tutorial({
      kingdom: kingdomName(me.id),
      peace: (b.peaceTurns ?? 5) + 1,
      castles: b.militaryWinKingdoms ?? 4,
      gold: b.economicWinGold ?? 300,
      turns: this.state?.maxTurns ?? 20,
      name: me.playerName ?? '',
      title: t(avatarWho(me.avatar) === 'queen' ? 'intro.queen' : 'intro.king'),
    }, () => {
      this.tutorial = undefined;
      this.showIntro();
    }, full ? TOUR : INTRO);
  }

  setOnline(on: boolean): void {
    this.online = on;
    this.renderHeader();
  }

  // ------------------------------------------------------------------ server messages

  message(m: ServerMessage): void {
    switch (m.type) {
      case 'state': {
        const before = this.state?.turn;
        const was = this.me();
        const old = was ? { gold: was.gold ?? 0, food: was.food ?? 0 } : undefined;
        this.host = !!m.host;
        this.state = m.state;
        setRealms(m.state.kingdoms);
        if (before === undefined || m.state.turn !== before) {
          this.draft = [...m.state.myOrders];
          const move = moveOf(this.draft);
          this.kind = move ? kindOf(move) as Kind : undefined;
          this.problems = [];
          this.saved = true;
          if (before !== undefined) {
            // a new turn: a short summary card, then the answers to other kings, then the choice of a move
            this.tab = 'move';
            this.selection = null;
            this.intro?.remove();
            this.intro = undefined;
            this.backToMap();
            if (old && this.me()) this.showSummary(old);
          }
        }
        if (before === undefined && this.me()) this.centreOnCastle();
        this.render();
        // Grosik greets the ruler at the start of every new game (and the first time ever, whenever they come in)
        const greet = m.state.turn === 1 ? !greeted(m.state.code) : !tutorialSeen();
        if (before === undefined && this.me() && !m.state.finished && greet) {
          markGreeted(m.state.code);
          window.setTimeout(() => this.showTutorial(false), 300);
        } else if (!this.summary && !this.tutorial) {
          this.showIntro();
        }
        return;
      }
      case 'ready':
        if (this.state) this.state.ready = m.ready;
        this.render();
        return;
      case 'ordersOk':
        this.saved = true;
        this.problems = [];
        this.renderPanel();
        return;
      case 'problems':
        this.saved = false;
        this.problems = m.problems;
        this.renderPanel();
        return;
      case 'error':
        this.toast = t(m.error);
        this.renderPanel();
        return;
    }
  }

  // ------------------------------------------------------------------ helpers

  private me(): KingdomView | undefined {
    return this.state?.kingdoms.find((k) => k.id === this.state?.me);
  }

  private amReady(): boolean {
    return !!this.state?.me && this.state.ready.includes(this.state.me);
  }

  private reserved(): number {
    return this.draft.reduce((sum, o) => sum + goldCost(o, this.rules, this.state!), 0);
  }

  private unitAt(p: Pos): UnitView | undefined {
    return this.state?.units.find((u) => samePos(u.pos, p));
  }

  private settlementAt(p: Pos): SettlementView | undefined {
    return this.state?.settlements.find((s) => samePos(s.pos, p));
  }

  private allies(): Set<string> {
    const me = this.state?.me;
    const out = new Set<string>();
    for (const tr of this.state?.treaties ?? []) {
      if (tr.type === 'ALLIANCE' && (tr.a === me || tr.b === me)) out.add(tr.a === me ? tr.b : tr.a);
    }
    return out;
  }

  /**
   * Replaces the orders that match `same` with `order` (or just removes them) and sends the list.
   * One turn is one move: a new move takes the place of the old one. Answers to offers stay.
   */
  private setOrder(same: (o: Order) => boolean, order?: Order): void {
    if (this.amReady()) return;
    this.draft = this.draft.filter((o) => !same(o));
    if (order && kindOf(order) !== 'ANSWER') {
      // a move of another kind replaces everything; within an Army move every army may walk or attack,
      // but training an army or founding a village is the one counted move
      const kind = kindOf(order);
      this.draft = this.draft.filter((o) => kindOf(o) === 'ANSWER'
        || (kindOf(o) === kind && (armyStep(o) || armyStep(order))));
      this.kind = kind as Kind;
    }
    if (order) this.draft.push(order);
    // an order was given for the clicked thing: close its choices, so Grosik's next step is what the child sees
    if (order && CLOSES.has(order.order)) this.selection = null;
    this.sync();
    if (order && CLOSES.has(order.order)) {
      this.quest.classList.remove('flash');
      void this.quest.offsetWidth;
      this.quest.classList.add('flash');
      if (phone()) this.quest.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }

  private sync(): void {
    this.saved = false;
    this.conn.orders(this.draft);
    this.render();
  }

  private zoom(delta: number): void {
    this.map.setFieldSize(this.map.fieldSize() + delta);
  }

  private centreOnCastle(): void {
    const castle = this.state?.settlements.find((s) => s.castle && s.owner === this.state?.me);
    if (castle) this.centreOn(castle.pos);
  }

  /** Scrolls the map so the field is in view: in the middle, or in the upper part on a phone (the sheet covers the bottom). */
  private centreOn(p: Pos): void {
    requestAnimationFrame(() => {
      const f = this.map.fieldSize();
      const up = phone() && this.selection ? 0.25 : 0.5;
      this.mapBox.scrollTo({
        left: p.x * f + f / 2 - this.mapBox.clientWidth / 2,
        top: p.y * f + f / 2 - this.mapBox.clientHeight * up,
        behavior: 'smooth',
      });
    });
  }

  private selectedPos(): Pos | undefined {
    const s = this.state;
    const sel = this.selection;
    if (!s || !sel) return undefined;
    if (sel.kind === 'tile') return sel.pos;
    if (sel.kind === 'settlement') return s.settlements.find((x) => x.id === sel.id)?.pos;
    return s.units.find((u) => u.id === sel.id)?.pos;
  }

  /** A new turn: back to the top of the page, the map on the child's castle, and Grosik's new step flashing. */
  private backToMap(): void {
    window.scrollTo({ top: 0, behavior: 'smooth' });
    this.centreOnCastle();
    this.quest.classList.remove('flash');
    void this.quest.offsetWidth;
    this.quest.classList.add('flash');
  }

  private closeSheet(): void {
    this.selection = null;
    this.render();
  }

  /** The choices for the selected thing, in a card next to it. */
  private renderSheet(): void {
    const s = this.state;
    this.sheet.replaceChildren();
    const show = !!s && !!this.me() && !!this.selection && !s.finished;
    this.sheet.hidden = !show;
    this.mapWrap.classList.toggle('sheet-open', show);
    if (!show) return;
    this.sheet.append(
      h('button', { class: 'sheet-close', 'aria-label': t('game.close'), title: t('game.close'), onclick: () => this.closeSheet() }, '✕'),
      this.selectionView());
    requestAnimationFrame(() => this.placeSheet());
  }

  /** On a computer the card stands beside the selected field (right of it, or left when there is no room). */
  private placeSheet(): void {
    const at = this.selectedPos();
    if (this.sheet.hidden || !at) return;
    if (phone()) {
      this.sheet.style.left = '';
      this.sheet.style.top = '';
      return;
    }
    const f = this.map.fieldSize();
    const border = 4;
    const x = at.x * f - this.mapBox.scrollLeft + border;
    const y = at.y * f - this.mapBox.scrollTop + border;
    const w = this.sheet.offsetWidth;
    const hgt = this.sheet.offsetHeight;
    const room = this.mapWrap.clientWidth;
    // beside an army, keep clear of the fields it can walk to
    const gap = this.selection?.kind === 'unit' ? f * 2 + 14 : 14;
    let left = x + f + gap;
    if (left + w > room - 8) left = x - w - gap;
    left = Math.max(8, Math.min(room - w - 8, left));
    const top = Math.max(8, Math.min(this.mapWrap.clientHeight - hgt - 8, y - 24));
    this.sheet.style.left = `${left}px`;
    this.sheet.style.top = `${top}px`;
  }

  /** Solo game: the child is the only human, so "Ready" simply ends the turn. */
  private solo(): boolean {
    return (this.state?.kingdoms.filter((k) => k.controller === 'HUMAN' && !k.autopilot).length ?? 0) <= 1;
  }

  /** Grosik's idea for the kind of move the child picked, while the move is not made yet. */
  private idea(): Step | undefined {
    if (!this.kind || this.amReady()) return undefined;
    const move = moveOf(this.draft);
    if (!move) return this.steps.find((x) => x.move === this.kind);
    if (this.kind !== 'ARMY') return undefined;
    // an Army move: the next army without orders
    const counted = this.draft.some((o) => kindOf(o) === 'ARMY' && !armyStep(o));
    return this.steps.find((x) => x.move === 'ARMY' && x.target.kind === 'unit'
      && !(counted && x.id.startsWith('found-'))
      && !this.draft.some((o) => 'unitId' in o && x.target.kind === 'unit' && o.unitId === x.target.id));
  }

  /**
   * Picks the kind of move for this turn (army, buildings, economy, diplomacy). A move of another kind
   * already chosen is taken back, because one turn is one move.
   */
  private chooseKind(k: Kind | undefined): void {
    if (this.amReady()) return;
    const move = moveOf(this.draft);
    this.kind = k;
    this.tab = 'move';
    if (move && kindOf(move) !== k) {
      this.draft = this.draft.filter((o) => o !== move);
      this.sync();
    } else {
      this.render();
    }
    if (k === 'ECONOMY' || k === 'DIPLOMACY') this.bringIntoView(this.panel.querySelector<HTMLElement>('.tab-body'));
  }

  /** Where Grosik's current step points on the map. */
  private hintPos(step?: Step): Pos | undefined {
    const s = this.state!;
    const tg = step?.target;
    if (!tg) return undefined;
    if (tg.kind === 'settlement') return s.settlements.find((x) => x.id === tg.id)?.pos;
    if (tg.kind === 'tile') return tg.pos;
    if (tg.kind === 'unit') {
      const sel = this.selection;
      if (sel?.kind === 'unit' && sel.id === tg.id) return tg.dest;
      return s.units.find((u) => u.id === tg.id)?.pos;
    }
    return undefined;
  }

  /** "Show me": selects the thing Grosik talks about and brings it into view. */
  private showStep(step: Step): void {
    const tg = step.target;
    if (tg.kind === 'settlement') this.selection = { kind: 'settlement', id: tg.id };
    if (tg.kind === 'tile') this.selection = { kind: 'tile', pos: tg.pos };
    if (tg.kind === 'unit') this.selection = { kind: 'unit', id: tg.id };
    this.kind = step.move;
    this.tab = 'move';
    this.render();
    const at = this.hintPos(step) ?? (tg.kind === 'unit' ? this.state!.units.find((u) => u.id === tg.id)?.pos : undefined);
    if (at) this.centreOn(at);
    this.bringIntoView(tg.kind === 'panel' ? this.panel.querySelector<HTMLElement>('.tab-body') : this.mapBox);
  }

  /** On a phone the page scrolls: brings the map (with its sheet) or a part of the panel into view. */
  private bringIntoView(el: HTMLElement | null): void {
    requestAnimationFrame(() => {
      if (!el) return;
      if (phone()) el.scrollIntoView({ behavior: 'smooth', block: el === this.mapBox ? 'start' : 'center' });
      else el.scrollIntoView({ block: 'nearest' });
    });
  }

  private unitEnd(u: UnitView): Pos {
    const mv = this.draft.find((o) => o.order === 'move' && o.unitId === u.id);
    return mv && mv.order === 'move' ? mv.path[mv.path.length - 1] : u.pos;
  }

  private attackTargets(u: UnitView): Set<string> {
    const s = this.state!;
    const friends = this.allies();
    const from = this.unitEnd(u);
    const out = new Set<string>();
    if (u.type === 'SETTLER') return out;
    for (const p of neighbours(from, s.map)) {
      const enemy = this.unitAt(p);
      const place = this.settlementAt(p);
      const foe = (owner?: string) => !!owner && owner !== s.me && !friends.has(owner);
      if ((enemy && foe(enemy.owner)) || (!enemy && place && foe(place.owner))) out.add(key(p));
    }
    return out;
  }

  // ------------------------------------------------------------------ map clicks

  private clickMap(p: Pos): void {
    const s = this.state;
    if (!s) return;
    const sel = this.selection;
    if (sel?.kind === 'unit' && !this.amReady() && this.kind === 'ARMY') {
      const u = s.units.find((x) => x.id === sel.id);
      if (u && u.owner === s.me) {
        const rule = unitRule(this.rules, u.type);
        const paths = reachable(s, u, rule.move);
        if (this.attackTargets(u).has(key(p))) {
          this.setOrder((o) => o.order === 'attack' && o.unitId === u.id, { order: 'attack', unitId: u.id, target: p });
          return;
        }
        const path = paths.get(key(p));
        if (path && !this.unitAt(p)) {
          this.setOrder((o) => (o.order === 'move' || o.order === 'attack') && o.unitId === u.id, { order: 'move', unitId: u.id, path });
          return;
        }
      }
    }
    const unit = this.unitAt(p);
    const place = this.settlementAt(p);
    if (place) {
      // first click on a castle shows the castle; a second click picks the army standing in it
      this.selection = unit && sel?.kind === 'settlement' && sel.id === place.id
        ? { kind: 'unit', id: unit.id }
        : { kind: 'settlement', id: place.id };
    } else if (unit) {
      this.selection = sel?.kind === 'unit' && sel.id === unit.id ? null : { kind: 'unit', id: unit.id };
    } else {
      this.selection = { kind: 'tile', pos: p };
    }
    this.render();
    if (this.selection && phone()) {
      this.centreOn(p);
      this.bringIntoView(this.mapBox);
    }
  }

  // ------------------------------------------------------------------ drawing

  private render(): void {
    this.steps = this.state && this.me() ? advise(this.state, this.rules) : [];
    this.renderHeader();
    this.renderMap();
    this.renderPanel();
  }

  private tick(): void {
    const s = this.state;
    this.blink = !this.blink;
    if (s && this.idea()) this.renderMap();
    if (!s || !this.timeEl) return;
    if (s.turnEndsAt) {
      const left = s.turnEndsAt - Date.now();
      this.timeEl.textContent = clock(left);
      this.timeEl.classList.toggle('hurry', left < 10_000);
      if (this.barEl) this.barEl.style.width = `${Math.max(0, Math.min(100, (left / (s.turnSeconds * 1000)) * 100))}%`;
    }
  }

  private renderHeader(): void {
    const s = this.state;
    this.header.replaceChildren();
    const logo = h('div', { class: 'logo' }, castleIcon(), h('span', { class: 'logo-text' }, t('app.name')));
    if (!s) {
      this.header.append(logo);
      return;
    }
    this.timeEl = h('span', { class: 'big-num gold-light' }, s.turnEndsAt ? '' : t('game.noLimit'));
    this.barEl = h('div', { class: 'bar-fill' });
    this.header.append(
      logo,
      h('div', { class: 'stat' }, h('span', { class: 'label' }, word('w.turn')),
        h('span', { class: 'big-num' }, t('game.turnOf', { season: season(s.turn), turn: s.turn, max: s.maxTurns }))),
      h('div', { class: 'stat time' },
        h('div', { class: 'row' }, h('span', { class: 'label' }, word('w.time')), this.timeEl),
        s.turnEndsAt ? h('div', { class: 'bar' }, this.barEl) : null),
      h('div', { class: 'chips' }, ...s.kingdoms.map((k) => {
        const ready = s.ready.includes(k.id);
        const state = k.controller !== 'HUMAN' ? 'PC' : k.autopilot ? 'zZ' : ready ? 'OK' : '…';
        return h('div', {
          class: `chip${k.id === s.me ? ' mine' : ''}`,
          title: `${kingdomName(k.id)} – ${playerLabel(k)}`,
        },
        k.controller === 'HUMAN' && k.avatar !== undefined
          ? h('div', { class: 'chip-avatar', style: `border-color:${k.color}` }, avatarSvg(k.avatar, 24))
          : h('div', { class: 'badge', style: `background:${k.color};color:${inkOn(k.color)}` }, kingdomName(k.id).charAt(0)),
        h('div', { class: 'chip-text' },
          h('b', {}, kingdomName(k.id)),
          h('span', {}, `${k.prestige} · `, h('i', { class: ready ? 'good' : '' }, state))));
      })),
      ...(this.online ? [] : [h('div', { class: 'offline' }, t('game.offline'))]),
      ...(this.me() ? [h('button', {
        class: 'btn small help', title: t('tut.help'), 'aria-label': t('tut.help'), onclick: () => this.showTutorial(true),
      }, '?')] : []),
    );
    this.tick();
  }

  private renderMap(): void {
    const s = this.state;
    if (!s) return;
    const overlay: Overlay = { moves: [], attacks: [], building: [], letters: {} };
    for (const u of ['SPEARMAN', 'ARCHER', 'KNIGHT', 'RAM', 'SETTLER'] as UnitType[]) overlay.letters[u] = t(`u.${u}`).charAt(0);
    for (const o of this.draft) {
      const u = 'unitId' in o ? s.units.find((x) => x.id === o.unitId) : undefined;
      if (o.order === 'move' && u) overlay.moves.push({ from: u.pos, path: o.path });
      if (o.order === 'attack' && u) overlay.attacks.push({ from: this.unitEnd(u), to: o.target });
      if (o.order === 'build') {
        const st = s.settlements.find((x) => x.id === o.settlementId);
        if (st) overlay.building.push(st.pos);
      }
      if (o.order === 'buildOnTile') overlay.building.push(o.pos);
    }
    const sel = this.selection;
    if (sel?.kind === 'unit') {
      const u = s.units.find((x) => x.id === sel.id);
      if (u) {
        overlay.selected = u.pos;
        if (u.owner === s.me && !this.amReady() && this.kind === 'ARMY') {
          const paths = reachable(s, u, unitRule(this.rules, u.type).move);
          overlay.reach = new Set([...paths.keys()].filter((k) => {
            const [x, y] = k.split(',').map(Number);
            return !this.unitAt({ x, y });
          }));
          overlay.targets = this.attackTargets(u);
        }
      }
    } else if (sel?.kind === 'settlement') {
      overlay.selected = s.settlements.find((x) => x.id === sel.id)?.pos;
    } else if (sel?.kind === 'tile') {
      overlay.selected = sel.pos;
    }
    overlay.hint = this.hintPos(this.idea());
    overlay.blink = this.blink;
    // the hinted field, also for automated play-throughs of the guided turns
    if (overlay.hint) this.map.canvas.dataset.hint = `${overlay.hint.x},${overlay.hint.y}`;
    else delete this.map.canvas.dataset.hint;
    this.map.update(s, overlay);
  }

  /** The nine phases of a turn: folded away, for children who want to know why things happened in that order. */
  private phases(): HTMLElement {
    return h('details', { class: 'phases' },
      h('summary', { class: 'muted small' }, t('game.phasesShow')),
      h('span', { class: 'muted small' }, t('game.phases')),
      h('div', { class: 'phase-row' }, ...[1, 2, 3, 4, 5, 6, 7, 8, 9].map((n) =>
        h('div', { class: 'phase' }, h('b', {}, String(n)), h('span', {}, t(`phase.${n}`))))));
  }

  private renderPanel(): void {
    const s = this.state;
    this.panel.replaceChildren();
    this.renderQuest();
    this.renderSheet();
    if (!s) return;
    if (this.toast) this.panel.append(h('div', { class: 'toast', role: 'status' }, this.toast));
    if (s.finished) this.panel.append(this.gameOver());
    const me = this.me();
    if (!me) {
      this.panel.append(this.spectator());
      return;
    }
    const settlements = s.settlements.filter((x) => x.owner === me.id);
    const villages = settlements.filter((x) => !x.castle).length;
    const lastTurn = (s.report?.ledger ?? []).reduce((sum, l) => sum + l.gold, 0);
    const free = (me.gold ?? 0) - this.reserved();
    this.panel.append(
      h('div', { class: 'me' },
        h('div', { class: 'me-avatar', style: `border-color:${me.color}` }, avatarSvg(me.avatar, 52)),
        h('div', { class: 'col' },
          h('span', { class: 'title' }, kingdomName(me.id)),
          h('span', { class: 'muted small' }, `${me.playerName ?? ''} · `, word('w.prestige'), ` ${me.prestige} · ${villages} `, word('w.village'),
            me.overlord ? ` · ${t('game.vassalOf', { name: kingdomName(me.overlord) })}` : ''))),
      h('div', { class: 'stats' },
        h('div', { class: 'card' }, h('span', { class: 'label' }, word('w.gold')), h('span', { class: 'big-num gold' }, String(me.gold ?? 0)),
          s.report ? h('span', { class: lastTurn >= 0 ? 'good small' : 'bad small' }, t('game.lastTurn', { sign: lastTurn >= 0 ? '+' : '', n: lastTurn })) : null),
        h('div', { class: 'card' }, h('span', { class: 'label' }, word('w.food')), h('span', { class: 'big-num' }, String(me.food ?? 0))),
        h('div', { class: 'card' }, h('span', { class: 'label' }, word('w.mood')),
          h('div', { class: 'row' }, moodFace(me.mood), word(`mood.${me.mood}`)),
          h('span', { class: 'muted small' }, word(`tax.${me.tax ?? 'LOW'}`)))),
      h('div', { class: 'small muted' }, h('b', { class: free < 0 ? 'bad' : 'gold' }, t('game.freeGold', { n: free }))),
      this.goal(),
      h('nav', { class: 'tabs', 'aria-label': 'Panel' }, ...TABS.map((tb) =>
        h('button', {
          class: `tab${this.tab === tb.id ? ' on' : ''}`,
          onclick: () => {
            this.tab = tb.id;
            this.seenTabs.add(tb.id);
            this.renderPanel();
          },
        }, word(tb.word), this.seenTabs.has(tb.id) ? null : h('span', { class: 'new' }, t('game.tabNew'))))),
    );
    const body = h('section', { class: 'tab-body' });
    switch (this.tab) {
      case 'move': body.append(this.moveView()); break;
      case 'ledger': body.append(this.ledgerView()); break;
      case 'chronicle': body.append(this.chronicleView()); break;
    }
    this.panel.append(body, this.ordersList(), this.readyBox());
  }

  // ------------------------------------------------------------------ tab: selection

  private selectionView(): HTMLElement {
    const s = this.state!;
    const sel = this.selection;
    if (!sel) return h('div');
    if (sel.kind === 'settlement') {
      const st = s.settlements.find((x) => x.id === sel.id);
      return st ? this.settlementView(st) : h('div');
    }
    if (sel.kind === 'unit') {
      const u = s.units.find((x) => x.id === sel.id);
      return u ? this.unitView(u) : h('div');
    }
    return this.tileView(sel.pos);
  }

  private settlementView(st: SettlementView): HTMLElement {
    const s = this.state!;
    const me = this.me()!;
    const parchment = h('div', { class: 'parchment' },
      h('div', { class: 'row spread' },
        h('span', { class: 'title' }, placeName(st.name)),
        h('span', { class: 'small' }, word(st.castle ? 'w.castle' : 'w.village'), ` · ${st.families}/${st.capacity} `, word('w.families'))));
    if (st.owner !== me.id) {
      parchment.append(h('p', {}, t('sel.notYours', { name: kingdomName(st.owner) })), this.nothingHere());
      return parchment;
    }
    const step = this.idea();
    const army = this.unitAt(st.pos);
    if (army && army.owner === me.id) {
      const wanted = step?.target.kind === 'unit' && step.target.id === army.id;
      parchment.append(h('button', {
        class: `option${wanted ? ' suggested' : ''}`,
        onclick: () => {
          this.selection = { kind: 'unit', id: army.id };
          this.render();
        },
      }, h('div', { class: 'opt-title' }, t('sel.armyHere', { unit: t(`u.${army.type}`) })),
      wanted ? h('div', { class: 'opt-sub star' }, `★ ${t('sel.suggested')}`) : null));
    }
    const free = (me.gold ?? 0) - this.reserved();
    parchment.append(h('div', { class: 'small' }, `${t('sel.buildings')}: `,
      ...(st.buildings.length ? st.buildings.flatMap((b, i) => [i ? ', ' : '', word(`b.${b}`)]) : [t('sel.none')])));
    if (st.castle && me.palacePaid > 0 && !me.palaceDone) {
      parchment.append(h('div', { class: 'small' }, t('sel.palace', { paid: me.palacePaid, all: this.rules.balance.palaceInstallments })));
    }
    if (this.kind !== 'BUILD' && this.kind !== 'ARMY') {
      parchment.append(this.needKind(['BUILD', 'ARMY']));
      return parchment;
    }
    const locked = this.amReady();
    if (this.kind === 'ARMY') {
      parchment.append(this.recruitOptions(st, free, locked), this.needKind(['BUILD']));
      return parchment;
    }
    const chosenBuild = this.draft.find((o) => o.order === 'build' && o.settlementId === st.id);
    parchment.append(h('div', { class: 'label dark' }, t('sel.build')));
    const builds = h('div', { class: 'grid2' });
    // the building Grosik suggests comes first and glows; ones the child cannot pay for say how much is missing
    const suggested = step?.target.kind === 'settlement' && step.target.id === st.id ? step.target.building : undefined;
    const options = buildOptions(this.rules, s, st, me.palaceDone).sort((a, b) => Number(b === suggested) - Number(a === suggested));
    for (const b of options) {
      const cost = buildingCost(this.rules, b);
      const pb = payback(this.rules, b);
      const chosen = chosenBuild?.order === 'build' && chosenBuild.building === b;
      const others = chosenBuild ? goldCost(chosenBuild, this.rules, s) : 0;
      const short = chosen ? 0 : cost - (free + others);
      const glow = b === suggested && !chosen;
      builds.append(h('button', {
        class: `option${chosen ? ' chosen' : ''}${glow ? ' suggested' : ''}`,
        disabled: locked || short > 0,
        'data-tip': tipText(`b.${b}`),
        onclick: () => this.setOrder((o) => o.order === 'build' && o.settlementId === st.id,
          chosen ? undefined : { order: 'build', settlementId: st.id, building: b }),
      },
      h('div', { class: 'opt-title' }, `${chosen ? '✔ ' : ''}${t(`b.${b}`)} · ${t('sel.cost', { n: cost })}`),
      h('div', { class: 'opt-sub' }, short > 0 ? t('sel.short', { n: short }) : pb ? t('sel.payback', { n: pb }) : t(`bd.${b}`)),
      glow ? h('div', { class: 'opt-sub star' }, `★ ${t('sel.suggested')}`) : null));
    }
    parchment.append(builds, this.needKind(['ARMY']));
    return parchment;
  }

  /** Training a new army in a castle or village: one of the Army moves. */
  private recruitOptions(st: SettlementView, free: number, locked: boolean): HTMLElement {
    const s = this.state!;
    const chosenUnit = this.draft.find((o) => o.order === 'recruit' && o.settlementId === st.id);
    const units = h('div', { class: 'grid2' });
    for (const u of this.rules.units) {
      const noBarracks = u.needsBarracks && !st.buildings.includes('BARRACKS');
      const chosen = chosenUnit?.order === 'recruit' && chosenUnit.unit === u.type;
      const others = chosenUnit ? goldCost(chosenUnit, this.rules, s) : 0;
      const short = chosen ? 0 : u.cost - (free + others);
      units.append(h('button', {
        class: `option${chosen ? ' chosen' : ''}`,
        disabled: locked || noBarracks || short > 0,
        'data-tip': tipText(`u.${u.type}`),
        onclick: () => this.setOrder((o) => o.order === 'recruit' && o.settlementId === st.id,
          chosen ? undefined : { order: 'recruit', settlementId: st.id, unit: u.type }),
      },
      h('div', { class: 'opt-title' }, `${t(`u.${u.type}`)} · ${t('sel.cost', { n: u.cost })}`),
      h('div', { class: 'opt-sub' }, noBarracks ? t('sel.needsBarracks') : short > 0 ? t('sel.short', { n: short })
        : `${t(`ud.${u.type}`)} · −${u.upkeep}/${t('w.turn').toLowerCase()}`)));
    }
    return h('div', { class: 'recruit' }, h('div', { class: 'label dark' }, t('sel.recruit')), units);
  }

  /** On a thing that needs another kind of move: say so, with buttons that switch to it. */
  private needKind(kinds: Kind[]): HTMLElement {
    return h('div', { class: 'need-kind' },
      h('p', { class: 'small' }, this.kind ? t('turn.otherKind', { kind: t(`move.${this.kind}`) }) : t('turn.pickFirst')),
      h('div', { class: 'row wrap' }, ...kinds.map((k) => h('button', {
        class: 'btn small', disabled: this.amReady(), onclick: () => this.chooseKind(k),
      }, `${KIND_ICON[k]} ${t(`move.${k}`)}`))));
  }

  private unitView(u: UnitView): HTMLElement {
    const s = this.state!;
    const rule = unitRule(this.rules, u.type);
    const box = h('div', { class: 'parchment' },
      h('div', { class: 'row spread' }, h('span', { class: 'title' }, word(`u.${u.type}`)), h('span', { class: 'small' }, kingdomName(u.owner))),
      h('div', { class: 'small' }, t('sel.unitStats', { a: rule.attack, d: rule.defense, m: rule.move })),
      h('div', { class: 'small' }, t(`ud.${u.type}`)));
    if (u.owner !== s.me) {
      box.append(this.nothingHere());
      return box;
    }
    if (u.type === 'SETTLER') box.append(this.settlerView(u));
    if (this.kind !== 'ARMY') {
      if (u.type !== 'SETTLER') box.append(this.needKind(['ARMY']));
      return box;
    }
    const step = this.idea();
    if (step?.target.kind === 'unit' && step.target.id === u.id && !samePos(step.target.dest, u.pos)) {
      box.append(h('p', { class: 'suggested-text' }, `★ ${t('adv.clickDest')}`));
    }
    box.append(h('p', { class: 'small' }, t('sel.moveHint')));
    if (u.type !== 'SETTLER') box.append(h('p', { class: 'small' }, t('sel.attackHint')));
    const planned = this.draft.some((o) => (o.order === 'move' || o.order === 'attack') && o.unitId === u.id);
    if (planned) {
      box.append(h('button', {
        class: 'btn', disabled: this.amReady(),
        onclick: () => this.setOrder((o) => (o.order === 'move' || o.order === 'attack') && o.unitId === u.id),
      }, t('sel.cancelMove')));
    }
    return box;
  }

  /**
   * A settler, made easy: one big "Build a village here" button when the field is good, otherwise why not
   * and a button that leads it to the nearest good field (Grosik marks it on the map). Works whatever kind
   * of move was picked: founding a village is an Army move, so it switches to Army.
   */
  private settlerView(u: UnitView): HTMLElement {
    const s = this.state!;
    const box = h('div', { class: 'settler' });
    const locked = this.amReady();
    const founding = this.draft.find((o) => o.order === 'foundVillage' && o.unitId === u.id);
    if (founding?.order === 'foundVillage') {
      box.append(h('p', { class: 'suggested-text' }, `✔ ${t('settler.planned', { name: founding.name })}`),
        h('button', { class: 'btn', disabled: locked, onclick: () => this.setOrder((o) => o === founding) }, t('settler.cancel')));
      return box;
    }
    const spot = villageSpot(s, this.rules, u.pos);
    if (spot === 'ok') {
      const own = s.settlements.filter((x) => x.owner === s.me).length;
      const name = `${kingdomName(s.me)} ${own + 1}`;
      box.append(h('button', {
        class: 'btn-ready found-here', disabled: locked,
        onclick: () => this.setOrder((o) => o.order === 'foundVillage' && o.unitId === u.id, { order: 'foundVillage', unitId: u.id, name }),
      }, `🏠 ${t('sel.foundVillage')}`),
      h('p', { class: 'small' }, t('settler.ok', { name })));
      return box;
    }
    box.append(h('p', { class: 'small bad' }, spot === 'tooClose'
      ? t('settler.tooClose', { n: this.rules.balance.settlerMinDistance ?? 3 })
      : t('settler.badGround')));
    const target = nearestVillageSpot(s, this.rules, u.pos);
    if (target) {
      box.append(h('p', { class: 'small' }, t('settler.nearest', { n: distance(u.pos, target) })));
      if (this.kind !== 'ARMY') {
        box.append(h('button', { class: 'btn-ready small-ready', disabled: locked, onclick: () => this.chooseKind('ARMY') }, `★ ${t('settler.lead')}`));
      }
    }
    return box;
  }

  private tileView(p: Pos): HTMLElement {
    const s = this.state!;
    const tc = terrainAt(s.map, p);
    const box = h('div', { class: 'parchment' },
      h('div', { class: 'title' }, t(`terrain.${tc}`)),
      h('p', { class: 'small' }, t(`terrainHint.${tc}`)));
    const owner = territory(s)[p.y][p.x];
    const step = this.idea();
    const free = (this.me()?.gold ?? 0) - this.reserved();
    let asked = false;
    if (s.map.ruins.some((r) => samePos(r, p))) box.append(h('p', {}, t('sel.ruins')));
    if (owner && owner !== s.me) box.append(h('p', {}, t('sel.notYours', { name: kingdomName(owner) })));
    if (owner === s.me && !improvementAt(s.map, p)) {
      const offer = (b: BuildingType, label: Key) => {
        const chosen = this.draft.some((o) => o.order === 'buildOnTile' && samePos(o.pos, p));
        const cost = buildingCost(this.rules, b);
        const short = chosen ? 0 : cost - free;
        const glow = !chosen && step?.target.kind === 'tile' && samePos(step.target.pos, p);
        box.append(h('button', {
          class: `option${chosen ? ' chosen' : ''}${glow ? ' suggested' : ''}`, disabled: this.amReady() || short > 0, 'data-tip': tipText(`b.${b}`),
          onclick: () => this.setOrder((o) => o.order === 'buildOnTile' && samePos(o.pos, p),
            chosen ? undefined : { order: 'buildOnTile', pos: p, building: b }),
        }, h('div', { class: 'opt-title' }, `${chosen ? '✔ ' : ''}${t(label)} · ${t('sel.cost', { n: cost })}`),
        h('div', { class: 'opt-sub' }, short > 0 ? t('sel.short', { n: short }) : t(`bd.${b}`)),
        glow ? h('div', { class: 'opt-sub star' }, `★ ${t('sel.suggested')}`) : null));
      };
      if ((tc === 'H' || tc === 'R') && this.kind !== 'BUILD') {
        box.append(this.needKind(['BUILD']));
        asked = true;
      } else {
        if (tc === 'H') offer('GOLD_MINE', 'sel.mine');
        if (tc === 'R') offer('BRIDGE', 'sel.bridge');
      }
    }
    if (!asked && !box.querySelector('.option')) box.append(this.nothingHere());
    return box;
  }

  // ------------------------------------------------------------------ other tabs

  private ledgerView(): HTMLElement {
    const r = this.state!.report;
    if (!r || !r.ledger.length) return h('p', { class: 'muted' }, t('ledger.empty'));
    const total = r.ledger.reduce((sum, l) => sum + l.gold, 0);
    return h('div', { class: 'parchment' },
      h('div', { class: 'title' }, t('ledger.title')),
      h('table', { class: 'ledger' },
        ...r.ledger.map((l) => h('tr', {}, h('td', {}, ledgerText(l)), h('td', { class: l.gold >= 0 ? 'num good-dark' : 'num bad' }, signed(l.gold)))),
        h('tr', { class: 'total' }, h('td', {}, t('ledger.total')), h('td', { class: 'num' }, signed(total)))));
  }

  private chronicleView(): HTMLElement {
    const s = this.state!;
    const r = s.report;
    if (!r) return h('p', { class: 'muted' }, t('chronicle.empty'));
    const ranking = [...s.kingdoms].sort((a, b) => b.prestige - a.prestige);
    return h('div', { class: 'parchment' },
      h('div', { class: 'title' }, t('chronicle.title', { n: r.turn })),
      h('ol', { class: 'chronicle' }, ...r.events.map((e) => h('li', {}, h('span', { class: 'phase-tag' }, String(e.phase)), eventText(e)))),
      h('div', { class: 'label dark' }, word('w.prestige')),
      h('ol', { class: 'ranking' }, ...ranking.map((k) => h('li', {},
        h('span', { class: 'dot', style: `background:${k.color}` }), `${kingdomName(k.id)} – ${k.prestige}`))));
  }

  private marketView(): HTMLElement {
    const s = this.state!;
    const me = this.me()!;
    const b = this.rules.balance;
    const locked = this.amReady();
    const current = this.draft.find((o) => o.order === 'market');
    const amount = h('input', { class: 'input num', type: 'number', min: 1, max: 99, value: 5, 'aria-label': t('market.amount') });
    const taxOrder = this.draft.find((o) => o.order === 'setTax');
    const tax = taxOrder?.order === 'setTax' ? taxOrder.rate : me.tax;
    const box = h('div', { class: 'parchment' },
      h('div', { class: 'title' }, word('w.market')),
      h('p', {}, word('w.price'), ': ', t('market.price', { n: s.foodPrice })),
      current ? h('p', { class: 'small' }, orderText(current, s)) : null,
      h('div', { class: 'row' }, h('label', { class: 'small' }, t('market.amount'), ' ', amount),
        h('button', { class: 'btn', disabled: locked, onclick: () => this.setOrder((o) => o.order === 'market', { order: 'market', buyFood: num(amount), sellFood: 0 }) }, t('market.buy')),
        h('button', { class: 'btn', disabled: locked, onclick: () => this.setOrder((o) => o.order === 'market', { order: 'market', buyFood: 0, sellFood: num(amount) }) }, t('market.sell'))),
      h('div', { class: 'label dark' }, word('w.tax')),
      h('p', { class: 'small' }, t('market.taxHelp')),
      h('div', { class: 'row' }, ...TAXES.map((r) => h('button', {
        class: `option slim${tax === r ? ' chosen' : ''}`, disabled: locked, 'data-tip': tipText(`tax.${r}`),
        onclick: () => this.setOrder((o) => o.order === 'setTax', r === me.tax ? undefined : { order: 'setTax', rate: r }),
      }, t(`tax.${r}`)))),
      h('div', { class: 'label dark' }, word('w.loan')),
      (me.loanLeft ?? 0) > 0
        ? h('p', { class: 'small' }, t('market.loanLeft', { n: me.loanLeft }))
        : h('button', {
          class: `option${this.draft.some((o) => o.order === 'takeLoan') ? ' chosen' : ''}`, disabled: locked,
          onclick: () => this.setOrder((o) => o.order === 'takeLoan', this.draft.some((o) => o.order === 'takeLoan') ? undefined : { order: 'takeLoan' }),
        }, t('market.loan', { gold: b.loanAmount, total: b.loanTotal })),
    );
    return box;
  }

  private diplomacyView(): HTMLElement {
    const s = this.state!;
    const me = this.me()!;
    const locked = this.amReady();
    const box = h('div', { class: 'parchment' });
    const offers = s.proposals.filter((p) => p.to === me.id);
    if (offers.length) {
      box.append(h('div', { class: 'label dark' }, t('dip.offers')));
      for (const p of offers) {
        const answer = (o: Order) => (o.order === 'acceptTreaty' || o.order === 'rejectTreaty') && o.from === p.from && o.type === p.type;
        const given = this.draft.find(answer)?.order;
        box.append(h('div', { class: 'row spread' }, `${kingdomName(p.from)}: `, word(`t.${p.type}`),
          h('span', { class: 'row' },
            h('button', {
              class: `btn small${given === 'acceptTreaty' ? ' chosen' : ''}`, disabled: locked,
              onclick: () => this.setOrder(answer, { order: 'acceptTreaty', from: p.from, type: p.type }),
            }, t('dip.accept')),
            h('button', {
              class: `btn small${given === 'rejectTreaty' ? ' chosen' : ''}`, disabled: locked,
              onclick: () => this.setOrder(answer, { order: 'rejectTreaty', from: p.from, type: p.type }),
            }, t('dip.reject')))));
      }
    }
    if (me.overlord) {
      const buying = this.draft.some((o) => o.order === 'buyFreedom');
      box.append(h('button', {
        class: `option${buying ? ' chosen' : ''}`, disabled: locked,
        onclick: () => this.setOrder((o) => o.order === 'buyFreedom', buying ? undefined : { order: 'buyFreedom' }),
      }, t('dip.freedom', { n: this.rules.balance.freedomPrice })));
    }
    box.append(h('div', { class: 'label dark' }, word('w.diplomacy')));
    for (const k of s.kingdoms) {
      if (k.id === me.id) continue;
      const mine = s.treaties.filter((tr) => (tr.a === me.id && tr.b === k.id) || (tr.b === me.id && tr.a === k.id));
      const row = h('div', { class: 'kingdom-row' },
        h('div', { class: 'row' },
          k.controller === 'HUMAN' && k.avatar !== undefined
            ? h('span', { class: 'chip-avatar', style: `border-color:${k.color}` }, avatarSvg(k.avatar, 22))
            : h('span', { class: 'dot', style: `background:${k.color}` }),
          h('b', {}, kingdomName(k.id)),
          h('span', { class: 'small' }, k.playerName ? ` ${k.playerName}` : ` ${t(`ctl.${k.controller}`)}`),
          k.overlord === me.id ? h('span', { class: 'small' }, ' · ', word('w.vassal')) : null,
          k.promiseBreaker ? h('span', { class: 'small bad' }, ` · ${t('dip.promiseBreaker')}`) : null));
      const actions = h('div', { class: 'row wrap' });
      for (const type of TREATIES) {
        const has = mine.find((tr) => tr.type === type);
        if (has) {
          const breaking = this.draft.some((o) => o.order === 'breakTreaty' && o.with === k.id && o.type === type);
          actions.append(h('span', { class: 'pill' }, word(`t.${type}`),
            has.endsAfterTurn ? ` (${t('dip.endsAfter', { n: has.endsAfterTurn })})` : '',
            h('button', {
              class: `btn tiny${breaking ? ' chosen' : ''}`, disabled: locked, title: t('dip.breakWarn'),
              onclick: () => this.setOrder((o) => o.order === 'breakTreaty' && o.with === k.id && o.type === type,
                breaking ? undefined : { order: 'breakTreaty', with: k.id, type }),
            }, t('dip.break'))));
        } else {
          const offered = this.draft.some((o) => o.order === 'proposeTreaty' && o.to === k.id && o.type === type);
          actions.append(h('button', {
            class: `btn tiny${offered ? ' chosen' : ''}`, disabled: locked, 'data-tip': tipText(`t.${type}`),
            onclick: () => this.setOrder((o) => o.order === 'proposeTreaty' && o.to === k.id && o.type === type,
              offered ? undefined : { order: 'proposeTreaty', to: k.id, type }),
          }, `${t('dip.propose')}: ${t(`t.${type}`)}`));
        }
      }
      row.append(actions);
      box.append(row);
    }
    const to = h('select', { class: 'input', 'aria-label': t('dip.gift') },
      ...s.kingdoms.filter((k) => k.id !== me.id).map((k) => h('option', { value: k.id }, kingdomName(k.id))));
    const gold = h('input', { class: 'input num', type: 'number', min: 0, value: 10, 'aria-label': t('dip.giftGold') });
    const food = h('input', { class: 'input num', type: 'number', min: 0, value: 0, 'aria-label': t('dip.giftFood') });
    box.append(h('div', { class: 'label dark' }, t('dip.gift')),
      h('div', { class: 'row wrap' }, to, h('label', { class: 'small' }, word('w.gold'), ' ', gold), h('label', { class: 'small' }, word('w.food'), ' ', food),
        h('button', {
          class: 'btn', disabled: locked,
          onclick: () => this.setOrder((o) => o.order === 'gift' && o.to === to.value, { order: 'gift', to: to.value, gold: num(gold), food: num(food) }),
        }, t('dip.send'))));
    return box;
  }

  // ------------------------------------------------------------------ orders, tips, ready

  private ordersList(): HTMLElement {
    const s = this.state!;
    const list = h('section', { class: 'orders' },
      h('div', { class: 'row spread' }, h('span', { class: 'label' }, t('game.yourMove')),
        h('span', { class: `small ${this.saved ? 'good' : 'muted'}` }, this.saved && this.draft.length ? `✔ ${t('game.saved')}` : '')));
    if (!moveOf(this.draft)) list.append(h('p', { class: 'muted small' }, t('game.noMove')));
    this.draft.forEach((o, i) => {
      const problem = this.problems.find((p) => p.orderIndex === i);
      const cost = goldCost(o, this.rules, s);
      list.append(h('div', { class: `order${problem ? ' problem' : ''}` },
        h('span', { class: 'n' }, String(i + 1)),
        h('span', { class: 'text' }, orderText(o, s), problem ? h('span', { class: 'why' }, t(problem.key)) : null),
        h('span', { class: 'cost' }, cost ? `${cost}` : ''),
        h('button', {
          class: 'x', 'aria-label': t('o.remove'), title: t('o.remove'), disabled: this.amReady(),
          onclick: () => {
            this.draft.splice(i, 1);
            this.sync();
          },
        }, '×')));
    });
    for (const p of this.problems.filter((x) => x.orderIndex < 0)) list.append(h('p', { class: 'bad small' }, t(p.key)));
    return list;
  }

  /**
   * Grosik's card above the map, which leads through the turn: pick a kind of move, make the move, end the turn.
   */
  private renderQuest(): void {
    const s = this.state;
    this.quest.replaceChildren();
    const me = this.me();
    this.quest.hidden = !s || !me || s.finished;
    if (this.quest.hidden || !s) return;
    const ready = this.amReady();
    const move = moveOf(this.draft);
    const body = h('div', { class: 'quest-body' });
    let go: HTMLElement | null = null;
    if (ready) {
      body.append(h('div', { class: 'quest-now' }, t('game.waiting')));
    } else if (move) {
      const moves = this.draft.filter((o) => kindOf(o) !== 'ANSWER');
      const next = this.idea();
      append(body,
        h('div', { class: 'quest-label' }, t('w.yourMove')),
        h('div', { class: 'quest-now' }, `✔ ${moves.length > 1
          ? t('turn.doneMany', { n: moves.length }) : t('turn.done', { move: orderText(move, s) })}`),
        h('div', { class: 'quest-why' }, this.kind === 'ARMY' ? t('turn.doneArmy') : t('turn.doneWhy')),
        h('div', { class: 'row wrap' },
          next ? h('button', { class: 'link', onclick: () => this.showStep(next) }, `★ ${next.text}`) : null,
          h('button', { class: 'link', onclick: () => this.setOrder((o) => kindOf(o) !== 'ANSWER') }, t('turn.changeMove'))));
      go = h('button', { class: 'btn-ready quest-go', onclick: () => this.endTurnNow() }, t('game.endTurn'));
    } else if (!this.kind) {
      const best = this.steps[0];
      append(body,
        h('div', { class: 'quest-label' }, t('turn.pickWhy')),
        h('div', { class: 'quest-now' }, t('turn.pick')),
        this.kindPicker(),
        h('div', { class: 'quest-why' }, best ? `★ ${t('turn.advice', { text: best.text })}` : saving(s, this.rules) ?? ''));
    } else {
      const idea = this.idea();
      append(body,
        h('div', { class: 'quest-label' }, `${KIND_ICON[this.kind]} ${t(`move.${this.kind}`)}`),
        h('div', { class: 'quest-now' }, idea ? `★ ${idea.text}` : t(`moveHow.${this.kind}`)),
        idea?.why ? h('div', { class: 'quest-why' }, idea.why) : null,
        h('button', { class: 'link', onclick: () => this.chooseKind(undefined) }, t('turn.change')));
      if (idea) go = h('button', { class: 'btn-ready quest-go', onclick: () => this.showStep(idea) }, t('adv.show'));
    }
    const face = h('button', {
      class: 'quest-face', title: t('tip.click'), 'aria-label': t('tip.click'),
      onclick: () => this.grosikTip(face),
    }, grosikSvg(ready ? 'happy' : move ? 'wink' : this.kind ? 'talk' : 'think', 48));
    append(this.quest, face, body, go);
  }

  /** Clicking Grosik: he pipes up with a piece of advice in a speech bubble, like an old desktop helper. */
  private grosikTip(face: HTMLElement): void {
    const s = this.state;
    const me = this.me();
    if (!s || !me) return;
    this.quest.querySelector('.grosik-pop')?.remove();
    const b = this.rules.balance;
    const upkeep = s.units.filter((u) => u.owner === me.id)
      .reduce((sum, u) => sum + (this.rules.units.find((x) => x.type === u.type)?.upkeep ?? 0), 0);
    const tips: string[] = [];
    if (s.turn <= (b.peaceTurns ?? 5)) tips.push(t('tip.peace', { n: (b.peaceTurns ?? 5) + 1 }));
    if ((me.food ?? 0) < 20) tips.push(t('tip.hungry'));
    if (me.mood === 'SAD') tips.push(t('tip.sad'));
    if ((me.gold ?? 0) >= 150) tips.push(t('tip.rich'));
    if (upkeep >= 6) tips.push(t('tip.upkeep', { n: upkeep }));
    tips.push(t('tip.default'), t('tip.joke1'), t('tip.joke2'));
    this.tipNo = (this.tipNo + 1) % tips.length;
    const close = () => pop.remove();
    const pop = h('div', { class: 'grosik-pop', role: 'status' },
      h('b', {}, `${t('tip.name')}: `), tips[this.tipNo],
      h('button', { class: 'x', 'aria-label': t('tip.close'), onclick: close }, '×'));
    face.after(pop);
    window.setTimeout(close, 9000);
  }

  /** The four kinds of move as big buttons; the one Grosik would pick has a star. */
  private kindPicker(): HTMLElement {
    const best = this.steps[0]?.move;
    return h('div', { class: 'kinds' }, ...KINDS.map((k) => h('button', {
      class: `kind-btn${k === best ? ' suggested' : ''}`, 'data-kind': k, disabled: this.amReady(),
      onclick: () => this.chooseKind(k),
    },
    h('span', { class: 'kind-icon', 'aria-hidden': 'true' }, KIND_ICON[k]),
    h('span', { class: 'kind-name' }, t(`move.${k}`)),
    h('span', { class: 'kind-sub' }, t(`moveSub.${k}`)),
    k === best ? h('span', { class: 'kind-star' }, t('turn.advised')) : null)));
  }

  /** The "Your move" tab: what to do for the chosen kind of move; economy and diplomacy are chosen right here. */
  private moveView(): HTMLElement {
    const k = this.kind;
    if (!k) return h('p', { class: 'muted small' }, t('turn.pick'));
    const box = h('div', { class: 'col gap' },
      h('div', { class: 'row spread' },
        h('span', { class: 'title' }, `${KIND_ICON[k]} ${t(`move.${k}`)}`),
        h('button', { class: 'btn small', disabled: this.amReady(), onclick: () => this.chooseKind(undefined) }, t('turn.change'))));
    if (k === 'ECONOMY') box.append(this.marketView());
    else if (k === 'DIPLOMACY') box.append(this.diplomacyView());
    else box.append(h('p', { class: 'muted small' }, t(`moveHow.${k}`)));
    return box;
  }

  /** For a field or a stranger's place: there is nothing to do here, and a way to what can be done. */
  private nothingHere(): HTMLElement {
    const idea = this.idea();
    const move = moveOf(this.draft);
    return h('div', { class: 'nothing' },
      h('p', { class: 'small' }, `${t('sel.nothingHere')} ${t('game.clickMap')}`),
      this.amReady() ? null : h('button', {
        class: 'btn-ready small-ready',
        onclick: () => {
          if (move) this.endTurnNow();
          else if (idea) this.showStep(idea);
          else this.closeSheet();
        },
      }, move ? t('game.endTurn') : t('sel.whatNow')));
  }

  /** Offers and broken treaties for this kingdom, answered before the move. */
  private introNews(): { offers: ProposalView[]; wars: ChronicleEvent[] } {
    const s = this.state!;
    const me = s.me;
    return {
      offers: s.proposals.filter((p) => p.to === me),
      wars: (s.report?.events ?? []).filter((e) => e.key === 'chronicle.treatyBroken' && e.params.with === me && e.params.type !== 'TRADE'),
    };
  }

  /**
   * The start of a turn, when another king asked us something: an offer of a treaty to accept or decline,
   * or news that someone broke a treaty with us (they may attack soon). Answering is free; then the child picks a move.
   */
  private showIntro(): void {
    const s = this.state;
    if (!s || !this.me() || s.finished || this.amReady() || this.intro || this.introTurn === s.turn) return;
    const { offers, wars } = this.introNews();
    if (!offers.length && !wars.length) return;
    this.introTurn = s.turn;
    const card = h('div', { class: 'tut-card middle summary intro' });
    this.intro = h('div', { class: 'tut dim', role: 'dialog', 'aria-modal': 'true', 'aria-label': t('intro.title') }, card);
    const close = () => {
      this.intro?.remove();
      this.intro = undefined;
      this.render();
      this.quest.querySelector<HTMLElement>('.kind-btn')?.focus();
    };
    const draw = () => {
      const answerOf = (p: ProposalView) => this.draft.find((o) =>
        (o.order === 'acceptTreaty' || o.order === 'rejectTreaty') && o.from === p.from && o.type === p.type)?.order;
      const answer = (p: ProposalView, yes: boolean) => {
        this.setOrder((o) => (o.order === 'acceptTreaty' || o.order === 'rejectTreaty') && o.from === p.from && o.type === p.type,
          { order: yes ? 'acceptTreaty' : 'rejectTreaty', from: p.from, type: p.type });
        draw();
      };
      const all = offers.every((p) => answerOf(p));
      card.replaceChildren();
      append(card,
        h('div', { class: 'title' }, t('intro.title')),
        offers.length ? h('p', { class: 'small' }, t('intro.text')) : null,
        ...wars.map((e) => h('div', { class: 'intro-item war' },
          h('p', {}, `⚔️ ${t('intro.war', {
            by: kingdomName(String(e.params.by)), type: t(`t.${String(e.params.type)}`), turn: Number(e.params.lastTurn) + 1,
          })}`))),
        ...offers.map((p) => {
          const given = answerOf(p);
          return h('div', { class: 'intro-item' },
            h('p', {}, `🤝 ${t('intro.offer', { from: kingdomName(p.from), type: t(`t.${p.type}`) })}`),
            h('div', { class: 'row wrap' },
              h('button', { class: `option slim${given === 'acceptTreaty' ? ' chosen' : ''}`, onclick: () => answer(p, true) },
                `${given === 'acceptTreaty' ? '✔ ' : ''}${t('intro.yes')}`),
              h('button', { class: `option slim${given === 'rejectTreaty' ? ' chosen' : ''}`, onclick: () => answer(p, false) },
                `${given === 'rejectTreaty' ? '✔ ' : ''}${t('intro.no')}`)));
        }),
        all ? null : h('p', { class: 'small muted-dark' }, t('intro.answerAll')),
        h('button', { class: 'btn-ready', disabled: !all, onclick: close }, offers.length ? t('intro.go') : t('intro.ok')));
    };
    draw();
    document.body.append(this.intro);
    this.intro.querySelector<HTMLElement>('button')?.focus();
  }

  private endTurnNow(): void {
    if (!this.amReady()) this.conn.ready(true);
  }

  /** The goal in one line, the child's place among the seven kings, and how to win (folded). */
  private goal(): HTMLElement {
    const s = this.state!;
    const me = this.me()!;
    const b = this.rules.balance;
    const ranking = [...s.kingdoms].sort((a, c) => c.prestige - a.prestige);
    const rank = ranking.findIndex((k) => k.id === me.id) + 1;
    const left = Math.max(0, s.maxTurns - s.turn + 1);
    return h('details', { class: 'goal' },
      h('summary', {},
        h('span', { class: 'goal-title' }, `🏆 ${t('goal.title')}`),
        h('span', { class: 'goal-text' }, t('goal.text', { left }), ' ', h('b', {}, t('goal.rank', { rank, all: s.kingdoms.length }))),
        h('span', { class: 'goal-how' }, t('goal.how'))),
      h('ul', {},
        h('li', {}, t('goal.way1')),
        h('li', {}, t('goal.way2', { gold: b.economicWinGold ?? 300 })),
        h('li', {}, t('goal.way3', { castles: b.militaryWinKingdoms ?? 4 }))),
      h('ol', { class: 'ranking light' }, ...ranking.map((k) => h('li', { class: k.id === me.id ? 'me-row' : '' },
        h('span', { class: 'dot', style: `background:${k.color}` }), `${kingdomName(k.id)} – ${k.prestige}`))));
  }

  /** After a turn: gold and food before and after, where the gold went, and what happened to the child's kingdom. */
  private showSummary(old: { gold: number; food: number }): void {
    const s = this.state!;
    const me = this.me()!;
    const r = s.report;
    this.summary?.remove();
    const close = () => {
      this.summary?.remove();
      this.summary = undefined;
      this.backToMap();
      window.removeEventListener('keydown', onKey);
      this.showIntro();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' || e.key === 'Enter') close();
    };
    const change = (label: Key, before: number, after: number) => {
      const d = after - before;
      return h('div', { class: 'sum-row' }, h('span', { class: 'label dark' }, word(label)),
        h('span', { class: 'big-num' }, `${before} → ${after}`),
        h('span', { class: d >= 0 ? 'good-dark' : 'bad' }, signed(d)));
    };
    const mineIds = new Set([me.id]);
    const aboutMe = (r?.events ?? []).filter((e) => e.visibleTo.includes(me.id)
      || Object.values(e.params).some((v) => typeof v === 'string' && mineIds.has(v))
      || e.key.startsWith('event.'));
    const next = h('button', { class: 'btn-ready', onclick: close }, t('sum.next', { n: s.turn }));
    this.summary = h('div', { class: 'tut dim', role: 'dialog', 'aria-modal': 'true', 'aria-label': t('sum.title', { n: r?.turn ?? s.turn - 1 }) },
      h('div', { class: 'tut-card middle summary' },
        h('div', { class: 'title' }, t('sum.title', { n: r?.turn ?? s.turn - 1 })),
        change('w.gold', old.gold, me.gold ?? 0),
        change('w.food', old.food, me.food ?? 0),
        r && r.ledger.length ? h('details', { class: 'sum-money' }, h('summary', {}, t('sum.money')),
          h('table', { class: 'ledger' }, ...r.ledger.map((l) => h('tr', {}, h('td', {}, ledgerText(l)),
            h('td', { class: l.gold >= 0 ? 'num good-dark' : 'num bad' }, signed(l.gold)))))) : null,
        h('div', { class: 'label dark' }, t('sum.happened')),
        aboutMe.length
          ? h('ul', { class: 'sum-events' }, ...aboutMe.slice(0, 6).map((e) => h('li', {}, eventText(e))))
          : h('p', { class: 'small' }, t('sum.nothing')),
        h('p', { class: 'small muted-dark' }, t('sum.more')),
        next));
    document.body.append(this.summary);
    window.addEventListener('keydown', onKey);
    next.focus();
  }

  private readyBox(): HTMLElement {
    const s = this.state!;
    const humans = s.kingdoms.filter((k) => k.controller === 'HUMAN' && !k.autopilot);
    const readyCount = humans.filter((k) => s.ready.includes(k.id)).length;
    const ready = this.amReady();
    const box = h('div', { class: 'ready-box' });
    if (s.finished) return box;
    const solo = this.solo();
    const moved = !!moveOf(this.draft);
    box.append(h('button', {
      class: `btn-ready${ready ? ' waiting' : ''}${!ready && moved ? ' suggested' : ''}`,
      onclick: () => this.conn.ready(!ready),
    }, ready ? t('game.notReady') : solo ? t('game.endTurn') : word('w.ready'),
    solo ? null : h('span', { class: 'small' }, ` (${t('game.readyCount', { n: readyCount, all: humans.length })})`)));
    // alone against the computer, "End turn" already ends it: a second button would only confuse
    if (this.host && !solo) box.append(h('button', { class: 'btn', onclick: () => this.conn.endTurn() }, t('game.hostEnd')));
    return box;
  }

  private spectator(): HTMLElement {
    const s = this.state!;
    const ranking = [...s.kingdoms].sort((a, b) => b.prestige - a.prestige);
    const humans = s.kingdoms.filter((k) => k.controller === 'HUMAN' && !k.autopilot);
    return h('div', { class: 'col gap' },
      h('div', { class: 'title' }, t('game.code', { code: s.code })),
      h('p', { class: 'muted' }, t('game.spectator')),
      h('ol', { class: 'ranking light' }, ...ranking.map((k) => h('li', {},
        h('span', { class: 'dot', style: `background:${k.color}` }),
        `${kingdomName(k.id)} (${playerLabel(k)}) – ${k.prestige}`,
        s.ready.includes(k.id) ? ' ✔' : ''))),
      h('p', {}, t('game.readyCount', { n: humans.filter((k) => s.ready.includes(k.id)).length, all: humans.length })),
      this.host && !s.finished ? h('button', { class: 'btn-ready', onclick: () => this.conn.endTurn() }, t('game.hostEnd')) : null,
      this.chronicleView(),
      h('button', { class: 'btn', onclick: () => this.onLeave() }, t('game.leave')));
  }

  private gameOver(): HTMLElement {
    const s = this.state!;
    return h('div', { class: 'game-over' },
      h('div', { class: 'title' }, t('game.over')),
      h('p', {}, t('game.winner', { names: s.winners.map(kingdomName).join(', ') })),
      s.winReason ? h('p', { class: 'small' }, t(`game.win.${s.winReason}`)) : null,
      h('button', { class: 'btn', onclick: () => this.onLeave() }, t('game.leave')));
  }
}

// ------------------------------------------------------------------ small pieces

/** Orders that finish what the child was doing with the clicked thing. */
const CLOSES = new Set<Order['order']>(['build', 'buildOnTile', 'recruit', 'move', 'attack', 'foundVillage']);

function phone(): boolean {
  return window.matchMedia('(max-width: 900px)').matches;
}

function num(input: HTMLInputElement): number {
  const n = Math.floor(Number(input.value));
  return Number.isFinite(n) && n > 0 ? n : 0;
}

function signed(n: number): string {
  return n > 0 ? `+${n}` : String(n);
}

function tipText(k: Key): string | null {
  return word(k).dataset.tip ?? null;
}

export function castleIcon(): SVGSVGElement {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('width', '34');
  svg.setAttribute('height', '34');
  svg.setAttribute('viewBox', '0 0 11 11');
  svg.setAttribute('shape-rendering', 'crispEdges');
  svg.setAttribute('aria-hidden', 'true');
  svg.innerHTML = '<rect x="1" y="4" width="9" height="5" fill="#f2b134"/><rect x="1" y="2" width="1" height="2" fill="#f2b134"/>'
    + '<rect x="5" y="1" width="1" height="3" fill="#f2b134"/><rect x="9" y="2" width="1" height="2" fill="#f2b134"/>'
    + '<rect x="3" y="3" width="1" height="1" fill="#f2b134"/><rect x="7" y="3" width="1" height="1" fill="#f2b134"/>'
    + '<rect x="1" y="9" width="9" height="1" fill="#b57d14"/><rect x="5" y="5" width="1" height="2" fill="#e0414b"/>';
  return svg;
}

function moodFace(mood: string): SVGSVGElement {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('width', '24');
  svg.setAttribute('height', '24');
  svg.setAttribute('viewBox', '0 0 9 9');
  svg.setAttribute('shape-rendering', 'crispEdges');
  svg.setAttribute('aria-hidden', 'true');
  const mouth = mood === 'HAPPY'
    ? '<rect x="2.5" y="5" width="1" height="1" fill="#2b1d0e"/><rect x="5.5" y="5" width="1" height="1" fill="#2b1d0e"/><rect x="3.5" y="6" width="2" height="1" fill="#2b1d0e"/>'
    : mood === 'SAD'
      ? '<rect x="3.5" y="5" width="2" height="1" fill="#2b1d0e"/><rect x="2.5" y="6" width="1" height="1" fill="#2b1d0e"/><rect x="5.5" y="6" width="1" height="1" fill="#2b1d0e"/>'
      : '<rect x="2.5" y="6" width="4" height="1" fill="#2b1d0e"/>';
  svg.innerHTML = `<rect x="1" y="1" width="7" height="7" fill="${mood === 'SAD' ? '#9fb4d6' : '#f2c230'}"/>`
    + '<rect x="2.5" y="3" width="1" height="1" fill="#2b1d0e"/><rect x="5.5" y="3" width="1" height="1" fill="#2b1d0e"/>' + mouth;
  return svg;
}

