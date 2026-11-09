import { t } from '../i18n';
import type { BuildingType, GameState, KingdomView, Order, Pos, Rules, SettlementView, UnitView } from '../types';
import { buildOptions, buildingCost, distance, goldCost, improvementAt, reachable, samePos, terrainAt, territory, unitRule } from './rules';
import { placeName } from './text';

/**
 * Grosik the treasurer looks at the kingdom and says what is worth doing now, one small step at a time.
 * A 10-year-old should never wonder "what do I do now?": there is always one next step, and it shows
 * where to click. The steps only suggest orders; the child can do anything else, and the server checks everything.
 */

export type Target =
  | { kind: 'settlement'; id: number; building?: BuildingType }
  | { kind: 'tile'; pos: Pos; building: BuildingType }
  | { kind: 'unit'; id: number; dest: Pos }
  | { kind: 'tab'; tab: 'market' }
  | { kind: 'ready' }
  | { kind: 'none' };

export interface Step {
  id: string;
  text: string;
  /** Why it is worth it, in one short sentence. */
  why?: string;
  target: Target;
  done: boolean;
}

/** How many suggestions (besides "end the turn") Grosik shows at once. */
const MAX_TODO = 2;

export function advise(s: GameState, rules: Rules, draft: Order[], ready: boolean): Step[] {
  const me = s.kingdoms.find((k) => k.id === s.me);
  if (!me || s.finished) return [];
  const free = (me.gold ?? 0) - draft.reduce((sum, o) => sum + goldCost(o, rules, s), 0);
  const mine = s.settlements.filter((x) => x.owner === me.id);
  const castle = mine.find((x) => x.castle);
  const villages = mine.filter((x) => !x.castle);
  const out: Step[] = [];
  let todo = 0;

  const ordered = (match: (o: Order) => boolean) => draft.some(match);
  const offer = (step: Omit<Step, 'done'>, done: boolean, cost: number) => {
    if (out.some((x) => x.id === step.id)) return;
    if (done) out.push({ ...step, done: true });
    else if (todo < MAX_TODO && cost <= free) {
      out.push({ ...step, done: false });
      todo++;
    }
  };
  const buildIn = (st: SettlementView | undefined, b: BuildingType, why: string) => {
    if (!st) return;
    const done = ordered((o) => o.order === 'build' && o.settlementId === st.id && o.building === b);
    const busy = ordered((o) => o.order === 'build' && o.settlementId === st.id && o.building !== b);
    if (!done && (busy || !buildOptions(rules, s, st, me.palaceDone).includes(b))) return;
    offer({
      id: `build-${st.id}-${b}`,
      text: t('adv.build', { building: t(`b.${b}`), place: placeName(st.name), cost: buildingCost(rules, b) }),
      why,
      target: { kind: 'settlement', id: st.id, building: b },
    }, done, buildingCost(rules, b));
  };

  // 1. hunger and sadness first: they make the kingdom poorer every turn
  const families = mine.reduce((sum, x) => sum + x.families, 0);
  const soldiers = s.units.filter((u) => u.owner === me.id).length;
  if ((me.food ?? 0) < families + soldiers) {
    for (const v of villages) buildIn(v, 'FARM', t('adv.why.farmHungry'));
    const buying = ordered((o) => o.order === 'market' && o.buyFood > 0);
    offer({ id: 'buy-food', text: t('adv.buyFood'), why: t('adv.why.buyFood'), target: { kind: 'tab', tab: 'market' } }, buying, s.foodPrice * 3);
  }
  if (me.mood === 'SAD' && me.tax !== 'LOW') {
    const lowered = ordered((o) => o.order === 'setTax' && o.rate === 'LOW');
    offer({ id: 'tax-low', text: t('adv.lowTax'), why: t('adv.why.lowTax'), target: { kind: 'tab', tab: 'market' } }, lowered, 0);
  }

  // 2. investments that pay back, cheapest lesson first: a market in the castle, a farm, a gold mine
  buildIn(castle, 'MARKET', t('adv.why.market', { n: rules.balance.marketIncome ?? 5 }));
  for (const v of villages) buildIn(v, 'FARM', t('adv.why.farm'));
  const hills = hillsForMine(s, me);
  if (hills) {
    const done = ordered((o) => o.order === 'buildOnTile' && o.building === 'GOLD_MINE');
    offer({
      id: 'mine',
      text: t('adv.mine', { cost: buildingCost(rules, 'GOLD_MINE') }),
      why: t('adv.why.mine', { n: rules.balance.goldMineIncome ?? 10 }),
      target: { kind: 'tile', pos: hills, building: 'GOLD_MINE' },
    }, done, buildingCost(rules, 'GOLD_MINE'));
  }

  // 3. a free treasure: walk an idle army towards the nearest old ruins
  const trip = ruinsTrip(s, rules, draft, me);
  if (trip) {
    offer({
      id: `ruins-${trip.unit.id}`,
      text: t('adv.ruins', { unit: t(`u.${trip.unit.type}`) }),
      why: t('adv.why.ruins'),
      target: { kind: 'unit', id: trip.unit.id, dest: trip.dest },
    }, trip.done, 0);
  }

  // 4. later in the game: more markets, the Great Palace, the treasury
  for (const v of villages) buildIn(v, 'MARKET', t('adv.why.market', { n: rules.balance.marketIncome ?? 5 }));
  if (s.turn > (rules.balance.peaceTurns ?? 5)) buildIn(castle, 'GREAT_PALACE', t('adv.why.palace'));
  buildIn(castle, 'TREASURY', t('adv.why.treasury'));
  for (const v of villages) {
    if (v.families >= v.capacity) buildIn(v, 'INN', t('adv.why.inn'));
  }

  // nothing to do and nothing affordable: tell the child that saving is a good move too
  if (!out.length) {
    const next = cheapestWish(s, rules, me, castle, villages);
    if (next && next.cost > free) {
      out.push({
        id: 'save', done: true, target: { kind: 'none' },
        text: t('adv.save', { building: t(`b.${next.building}`), cost: next.cost, n: Math.max(0, free) }),
      });
    }
  }

  out.push({
    id: 'ready',
    text: t('adv.ready'),
    why: out.some((x) => !x.done) ? t('adv.why.readyLater') : t('adv.why.ready'),
    target: { kind: 'ready' },
    done: ready,
  });
  return out;
}

/** The step to light up now: the first one not done yet. */
export function current(steps: Step[]): Step | undefined {
  return steps.find((x) => !x.done && x.target.kind !== 'none');
}

/** A field of hills in the child's land with nothing on it yet, nearest to the castle. */
function hillsForMine(s: GameState, me: KingdomView): Pos | undefined {
  const owner = territory(s);
  const castle = s.settlements.find((x) => x.castle && x.owner === me.id);
  let best: Pos | undefined;
  for (let y = 0; y < s.map.height; y++) {
    for (let x = 0; x < s.map.width; x++) {
      const p = { x, y };
      if (owner[y][x] !== me.id || terrainAt(s.map, p) !== 'H' || improvementAt(s.map, p)) continue;
      if (!best || (castle && distance(p, castle.pos) < distance(best, castle.pos))) best = p;
    }
  }
  return best;
}

/** An army with no orders and old ruins not far away: where to step this turn to get closer. */
function ruinsTrip(s: GameState, rules: Rules, draft: Order[], me: KingdomView):
  { unit: UnitView; dest: Pos; done: boolean } | undefined {
  if (!s.map.ruins.length) return undefined;
  for (const u of s.units.filter((x) => x.owner === me.id && x.type !== 'SETTLER')) {
    const ruin = [...s.map.ruins].sort((a, b) => distance(a, u.pos) - distance(b, u.pos))[0];
    if (distance(ruin, u.pos) > 6) continue;
    const planned = draft.find((o) => (o.order === 'move' || o.order === 'attack') && o.unitId === u.id);
    if (planned) {
      if (planned.order !== 'move') continue;
      const end = planned.path[planned.path.length - 1];
      if (distance(end, ruin) < distance(u.pos, ruin)) return { unit: u, dest: end, done: true };
      continue;
    }
    const paths = reachable(s, u, unitRule(rules, u.type).move);
    let dest: Pos | undefined;
    for (const k of paths.keys()) {
      const [x, y] = k.split(',').map(Number);
      const p = { x, y };
      if (s.units.some((o) => samePos(o.pos, p)) || s.settlements.some((o) => samePos(o.pos, p))) continue;
      if (!dest || closer(p, dest, ruin)) dest = p;
    }
    if (dest && distance(dest, ruin) < distance(u.pos, ruin)) return { unit: u, dest, done: false };
  }
  return undefined;
}

/** Is `a` nearer to `goal` than `b`? Counted in steps, then as the crow flies, so the army walks straight at it. */
function closer(a: Pos, b: Pos, goal: Pos): boolean {
  const da = distance(a, goal);
  const db = distance(b, goal);
  if (da !== db) return da < db;
  const sq = (p: Pos) => (p.x - goal.x) ** 2 + (p.y - goal.y) ** 2;
  return sq(a) < sq(b);
}

function cheapestWish(s: GameState, rules: Rules, me: KingdomView, castle: SettlementView | undefined, villages: SettlementView[]):
  { building: BuildingType; cost: number } | undefined {
  const wishes: BuildingType[] = [];
  if (castle && !castle.buildings.includes('MARKET')) wishes.push('MARKET');
  if (villages.some((v) => buildOptions(rules, s, v, me.palaceDone).includes('FARM'))) wishes.push('FARM');
  if (hillsForMine(s, me)) wishes.push('GOLD_MINE');
  if (villages.some((v) => !v.buildings.includes('MARKET'))) wishes.push('MARKET');
  if (castle && !me.palaceDone && s.turn > (rules.balance.peaceTurns ?? 5)) wishes.push('GREAT_PALACE');
  const priced = wishes.map((b) => ({ building: b, cost: buildingCost(rules, b) })).sort((a, b) => a.cost - b.cost);
  return priced[0];
}
