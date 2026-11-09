import type { Link, ServerMessage } from '../api';
import { h, word } from '../dom';
import { t, type Key } from '../i18n';
import { MapCanvas, type Overlay } from '../game/MapCanvas';
import {
  buildOptions, buildingCost, goldCost, improvementAt, key, neighbours, payback, reachable, samePos,
  terrainAt, territory, unitRule,
} from '../game/rules';
import { inkOn } from '../game/sprites';
import { Tutorial, tutorialSeen } from './Tutorial';
import { clock, eventText, kingdomName, ledgerText, orderText, placeName, playerLabel, season } from '../game/text';
import type {
  BuildingType, GameState, KingdomView, Order, Pos, Problem, Rules, SettlementView, TaxRate, TreatyType, UnitType, UnitView,
} from '../types';

type Tab = 'orders' | 'ledger' | 'diplomacy' | 'market' | 'chronicle';
type Selection = { kind: 'unit'; id: number } | { kind: 'settlement'; id: number } | { kind: 'tile'; pos: Pos } | null;

const TABS: { id: Tab; word: Key }[] = [
  { id: 'orders', word: 'w.orders' },
  { id: 'ledger', word: 'w.ledger' },
  { id: 'diplomacy', word: 'w.diplomacy' },
  { id: 'market', word: 'w.market' },
  { id: 'chronicle', word: 'w.chronicle' },
];
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
  private tab: Tab = 'orders';
  private selection: Selection = null;
  private toast = '';
  private timeEl?: HTMLElement;
  private barEl?: HTMLElement;
  private ticker: number;
  private tutorial?: Tutorial;

  constructor(private readonly rules: Rules, private readonly conn: Link, private readonly onLeave: () => void) {
    this.map = new MapCanvas((p) => this.clickMap(p));
    this.mapBox.append(this.map.canvas);
    const zoom = h('div', { class: 'zoom' },
      h('button', { class: 'btn small', 'aria-label': t('game.zoomOut'), title: t('game.zoomOut'), onclick: () => this.zoom(-11) }, '−'),
      h('button', { class: 'btn small', 'aria-label': t('game.zoomIn'), title: t('game.zoomIn'), onclick: () => this.zoom(11) }, '+'));
    this.board.append(this.mapBox, zoom, this.phases());
    this.root.append(this.header, h('div', { class: 'game-body' }, this.board, this.panel));
    this.map.setFieldSize(33);
    this.ticker = window.setInterval(() => this.tick(), 500);
  }

  destroy(): void {
    window.clearInterval(this.ticker);
    this.tutorial?.close();
  }

  /** The guided tour of this screen; it opens by itself the first time a child plays. */
  private showTutorial(): void {
    const me = this.me();
    if (!me || this.tutorial) return;
    const b = this.rules.balance;
    this.tutorial = new Tutorial({
      kingdom: kingdomName(me.id),
      peace: (b.peaceTurns ?? 5) + 1,
      castles: b.militaryWinKingdoms ?? 4,
      gold: b.economicWinGold ?? 300,
    }, () => {
      this.tutorial = undefined;
    });
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
        this.host = !!m.host;
        this.state = m.state;
        if (before === undefined || m.state.turn !== before) {
          this.draft = [...m.state.myOrders];
          this.problems = [];
          this.saved = true;
          if (before !== undefined) {
            this.tab = 'chronicle';
            this.selection = null;
            this.toast = t('game.newTurn');
            window.setTimeout(() => {
              this.toast = '';
              this.renderPanel();
            }, 5000);
          }
        }
        if (before === undefined && this.me()) this.centreOnCastle();
        this.render();
        if (before === undefined && this.me() && !m.state.finished && !tutorialSeen()) {
          window.setTimeout(() => this.showTutorial(), 300);
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

  /** Replaces the orders that match `same` with `order` (or just removes them) and sends the list. */
  private setOrder(same: (o: Order) => boolean, order?: Order): void {
    if (this.amReady()) return;
    this.draft = this.draft.filter((o) => !same(o));
    if (order) this.draft.push(order);
    this.sync();
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
    if (!castle) return;
    requestAnimationFrame(() => {
      const f = this.map.fieldSize();
      this.mapBox.scrollLeft = castle.pos.x * f - this.mapBox.clientWidth / 2;
      this.mapBox.scrollTop = castle.pos.y * f - this.mapBox.clientHeight / 2;
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
    if (sel?.kind === 'unit' && !this.amReady()) {
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
    this.tab = 'orders';
    this.render();
  }

  // ------------------------------------------------------------------ drawing

  private render(): void {
    this.renderHeader();
    this.renderMap();
    this.renderPanel();
  }

  private tick(): void {
    const s = this.state;
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
        h('div', { class: 'badge', style: `background:${k.color};color:${inkOn(k.color)}` }, kingdomName(k.id).charAt(0)),
        h('div', { class: 'chip-text' },
          h('b', {}, kingdomName(k.id)),
          h('span', {}, `${k.prestige} · `, h('i', { class: ready ? 'good' : '' }, state))));
      })),
      ...(this.online ? [] : [h('div', { class: 'offline' }, t('game.offline'))]),
      ...(this.me() ? [h('button', {
        class: 'btn small help', title: t('tut.help'), 'aria-label': t('tut.help'), onclick: () => this.showTutorial(),
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
        if (u.owner === s.me && !this.amReady()) {
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
    this.map.update(s, overlay);
  }

  private phases(): HTMLElement {
    return h('div', { class: 'phases' },
      h('span', { class: 'muted small' }, t('game.phases')),
      h('div', { class: 'phase-row' }, ...[1, 2, 3, 4, 5, 6, 7, 8, 9].map((n) =>
        h('div', { class: 'phase' }, h('b', {}, String(n)), h('span', {}, t(`phase.${n}`))))));
  }

  private renderPanel(): void {
    const s = this.state;
    this.panel.replaceChildren();
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
        h('div', { class: 'badge big', style: `background:${me.color};color:${inkOn(me.color)}` }, kingdomName(me.id).charAt(0)),
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
      h('nav', { class: 'tabs', 'aria-label': 'Panel' }, ...TABS.map((tb) =>
        h('button', { class: `tab${this.tab === tb.id ? ' on' : ''}`, onclick: () => { this.tab = tb.id; this.renderPanel(); } }, word(tb.word)))),
    );
    const body = h('section', { class: 'tab-body' });
    switch (this.tab) {
      case 'orders': body.append(this.selectionView()); break;
      case 'ledger': body.append(this.ledgerView()); break;
      case 'diplomacy': body.append(this.diplomacyView()); break;
      case 'market': body.append(this.marketView()); break;
      case 'chronicle': body.append(this.chronicleView()); break;
    }
    this.panel.append(body, this.ordersList(), this.grosik(), this.readyBox());
  }

  // ------------------------------------------------------------------ tab: selection

  private selectionView(): HTMLElement {
    const s = this.state!;
    const sel = this.selection;
    if (!sel) return h('p', { class: 'muted' }, t('game.clickMap'));
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
      parchment.append(h('p', {}, t('sel.notYours', { name: kingdomName(st.owner) })));
      return parchment;
    }
    const army = this.unitAt(st.pos);
    if (army && army.owner === me.id) {
      parchment.append(h('button', { class: 'btn small', onclick: () => this.clickMap(st.pos) },
        t('sel.armyHere', { unit: t(`u.${army.type}`) })));
    }
    parchment.append(h('div', { class: 'small' }, `${t('sel.buildings')}: `,
      ...(st.buildings.length ? st.buildings.flatMap((b, i) => [i ? ', ' : '', word(`b.${b}`)]) : [t('sel.none')])));
    if (st.castle && me.palacePaid > 0 && !me.palaceDone) {
      parchment.append(h('div', { class: 'small' }, t('sel.palace', { paid: me.palacePaid, all: this.rules.balance.palaceInstallments })));
    }
    const locked = this.amReady();
    const chosenBuild = this.draft.find((o) => o.order === 'build' && o.settlementId === st.id);
    parchment.append(h('div', { class: 'label dark' }, t('sel.build')));
    const builds = h('div', { class: 'grid2' });
    for (const b of buildOptions(this.rules, s, st, me.palaceDone)) {
      const cost = buildingCost(this.rules, b);
      const pb = payback(this.rules, b);
      const chosen = chosenBuild?.order === 'build' && chosenBuild.building === b;
      builds.append(h('button', {
        class: `option${chosen ? ' chosen' : ''}`,
        disabled: locked,
        'data-tip': tipText(`b.${b}`),
        onclick: () => this.setOrder((o) => o.order === 'build' && o.settlementId === st.id,
          chosen ? undefined : { order: 'build', settlementId: st.id, building: b }),
      },
      h('div', { class: 'opt-title' }, `${t(`b.${b}`)} · ${t('sel.cost', { n: cost })}`),
      h('div', { class: 'opt-sub' }, pb ? t('sel.payback', { n: pb }) : t(`bd.${b}`))));
    }
    parchment.append(builds);

    const chosenUnit = this.draft.find((o) => o.order === 'recruit' && o.settlementId === st.id);
    parchment.append(h('div', { class: 'label dark' }, t('sel.recruit')));
    const units = h('div', { class: 'grid2' });
    for (const u of this.rules.units) {
      const noBarracks = u.needsBarracks && !st.buildings.includes('BARRACKS');
      const chosen = chosenUnit?.order === 'recruit' && chosenUnit.unit === u.type;
      units.append(h('button', {
        class: `option${chosen ? ' chosen' : ''}`,
        disabled: locked || noBarracks,
        'data-tip': tipText(`u.${u.type}`),
        onclick: () => this.setOrder((o) => o.order === 'recruit' && o.settlementId === st.id,
          chosen ? undefined : { order: 'recruit', settlementId: st.id, unit: u.type }),
      },
      h('div', { class: 'opt-title' }, `${t(`u.${u.type}`)} · ${t('sel.cost', { n: u.cost })}`),
      h('div', { class: 'opt-sub' }, noBarracks ? t('sel.needsBarracks') : `${t(`ud.${u.type}`)} · −${u.upkeep}/${t('w.turn').toLowerCase()}`)));
    }
    parchment.append(units);
    return parchment;
  }

  private unitView(u: UnitView): HTMLElement {
    const s = this.state!;
    const rule = unitRule(this.rules, u.type);
    const box = h('div', { class: 'parchment' },
      h('div', { class: 'row spread' }, h('span', { class: 'title' }, word(`u.${u.type}`)), h('span', { class: 'small' }, kingdomName(u.owner))),
      h('div', { class: 'small' }, t('sel.unitStats', { a: rule.attack, d: rule.defense, m: rule.move })),
      h('div', { class: 'small' }, t(`ud.${u.type}`)));
    if (u.owner !== s.me) return box;
    box.append(h('p', { class: 'small' }, t('sel.moveHint')));
    if (u.type !== 'SETTLER') box.append(h('p', { class: 'small' }, t('sel.attackHint')));
    const planned = this.draft.some((o) => (o.order === 'move' || o.order === 'attack') && o.unitId === u.id);
    if (planned) {
      box.append(h('button', {
        class: 'btn', disabled: this.amReady(),
        onclick: () => this.setOrder((o) => (o.order === 'move' || o.order === 'attack' || o.order === 'foundVillage') && o.unitId === u.id),
      }, t('sel.cancelMove')));
    }
    if (u.type === 'SETTLER') {
      const own = s.settlements.filter((x) => x.owner === s.me).length;
      const input = h('input', { class: 'input', maxlength: 20, value: `${kingdomName(s.me)} ${own + 1}`, 'aria-label': t('sel.villageName') });
      box.append(h('div', { class: 'row' }, input,
        h('button', {
          class: 'btn', disabled: this.amReady(),
          onclick: () => this.setOrder((o) => o.order === 'foundVillage' && o.unitId === u.id,
            { order: 'foundVillage', unitId: u.id, name: input.value.trim() || input.placeholder }),
        }, t('sel.foundVillage'))));
    }
    return box;
  }

  private tileView(p: Pos): HTMLElement {
    const s = this.state!;
    const box = h('div', { class: 'parchment' }, h('div', { class: 'title' }, t('game.tile', { x: p.x, y: p.y })));
    const owner = territory(s)[p.y][p.x];
    const tc = terrainAt(s.map, p);
    if (s.map.ruins.some((r) => samePos(r, p))) box.append(h('p', {}, t('sel.ruins')));
    if (owner && owner !== s.me) box.append(h('p', {}, t('sel.notYours', { name: kingdomName(owner) })));
    if (owner === s.me && !improvementAt(s.map, p)) {
      const offer = (b: BuildingType, label: Key) => {
        const chosen = this.draft.some((o) => o.order === 'buildOnTile' && samePos(o.pos, p));
        box.append(h('button', {
          class: `option${chosen ? ' chosen' : ''}`, disabled: this.amReady(), 'data-tip': tipText(`b.${b}`),
          onclick: () => this.setOrder((o) => o.order === 'buildOnTile' && samePos(o.pos, p),
            chosen ? undefined : { order: 'buildOnTile', pos: p, building: b }),
        }, h('div', { class: 'opt-title' }, `${t(label)} · ${t('sel.cost', { n: buildingCost(this.rules, b) })}`),
        h('div', { class: 'opt-sub' }, t(`bd.${b}`))));
      };
      if (tc === 'H') offer('GOLD_MINE', 'sel.mine');
      if (tc === 'R') offer('BRIDGE', 'sel.bridge');
    }
    if (box.childElementCount === 1) box.append(h('p', { class: 'small' }, t('sel.empty')));
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
        const accepted = this.draft.some((o) => o.order === 'acceptTreaty' && o.from === p.from && o.type === p.type);
        box.append(h('div', { class: 'row spread' }, `${kingdomName(p.from)}: `, word(`t.${p.type}`),
          h('button', {
            class: `btn small${accepted ? ' chosen' : ''}`, disabled: locked,
            onclick: () => this.setOrder((o) => o.order === 'acceptTreaty' && o.from === p.from && o.type === p.type,
              accepted ? undefined : { order: 'acceptTreaty', from: p.from, type: p.type }),
          }, t('dip.accept'))));
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
          h('span', { class: 'dot', style: `background:${k.color}` }),
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
      h('div', { class: 'row spread' }, h('span', { class: 'label' }, t('game.yourOrders')),
        h('span', { class: `small ${this.saved ? 'good' : 'muted'}` }, this.saved && this.draft.length ? `✔ ${t('game.saved')}` : '')));
    if (!this.draft.length) list.append(h('p', { class: 'muted small' }, t('game.noOrders')));
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

  private grosik(): HTMLElement {
    const s = this.state!;
    const me = this.me()!;
    const b = this.rules.balance;
    const upkeep = s.units.filter((u) => u.owner === me.id).reduce((sum, u) => sum + unitRule(this.rules, u.type).upkeep, 0);
    let tip: string;
    if ((me.food ?? 0) < 5) tip = t('tip.hungry');
    else if (me.mood === 'SAD') tip = t('tip.sad');
    else if (s.turn <= b.peaceTurns) tip = t('tip.peace', { n: b.peaceTurns + 1 });
    else if (upkeep > 0 && s.turn % 2 === 0) tip = t('tip.upkeep', { n: upkeep });
    else if ((me.gold ?? 0) > 150) tip = t('tip.rich');
    else tip = t('tip.default');
    return h('div', { class: 'grosik' }, coinFace(), h('div', { class: 'bubble' }, h('b', {}, `${t('tip.name')}: `), tip));
  }

  private readyBox(): HTMLElement {
    const s = this.state!;
    const humans = s.kingdoms.filter((k) => k.controller === 'HUMAN' && !k.autopilot);
    const readyCount = humans.filter((k) => s.ready.includes(k.id)).length;
    const ready = this.amReady();
    const box = h('div', { class: 'ready-box' });
    if (s.finished) return box;
    box.append(h('button', {
      class: `btn-ready${ready ? ' waiting' : ''}`,
      onclick: () => this.conn.ready(!ready),
    }, ready ? t('game.notReady') : word('w.ready'), ' ',
    h('span', { class: 'small' }, `(${t('game.readyCount', { n: readyCount, all: humans.length })})`)));
    if (this.host) box.append(h('button', { class: 'btn', onclick: () => this.conn.endTurn() }, t('game.hostEnd')));
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

function coinFace(): SVGSVGElement {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('width', '40');
  svg.setAttribute('height', '40');
  svg.setAttribute('viewBox', '0 0 10 10');
  svg.setAttribute('shape-rendering', 'crispEdges');
  svg.setAttribute('aria-hidden', 'true');
  svg.innerHTML = '<rect x="2" y="1" width="6" height="8" fill="#f2b134"/><rect x="1" y="2" width="8" height="6" fill="#f2b134"/>'
    + '<rect x="3" y="3" width="1" height="1" fill="#2b1d0e"/><rect x="6" y="3" width="1" height="1" fill="#2b1d0e"/>'
    + '<rect x="3" y="6" width="4" height="1" fill="#2b1d0e"/><rect x="2" y="2" width="1" height="1" fill="#ffe7a6"/>';
  return svg;
}
