import { t } from '../i18n';
import type { BuildingType, GameState, KingdomView, Order, Pos, Rules, SettlementView, UnitView } from '../types';
import {
  buildOptions, buildingCost, distance, improvementAt, nearestVillageSpot, reachable, samePos, terrainAt, territory, unitRule, villageSpot,
} from './rules';
import { placeName } from './text';

/**
 * One turn is one move. The child first picks what kind of move to make (army, buildings, economy or
 * diplomacy), then gives one order of that kind. Answers to treaty offers are free and come before the move.
 *
 * Grosik the treasurer looks at the kingdom and says which move is worth making now, so a 10-year-old never
 * wonders "what do I do now?". The ideas only suggest; the child can do anything else, and the server checks everything.
 */

export type Kind = 'ARMY' | 'BUILD' | 'ECONOMY' | 'DIPLOMACY';
export const KINDS: Kind[] = ['ARMY', 'BUILD', 'ECONOMY', 'DIPLOMACY'];

/** The kind of move an order is (the same split as the engine's Order.Category). */
export function kindOf(o: Order): Kind | 'ANSWER' {
  switch (o.order) {
    case 'move': case 'attack': case 'recruit': case 'foundVillage': return 'ARMY';
    case 'build': case 'buildOnTile': return 'BUILD';
    case 'setTax': case 'market': case 'takeLoan': return 'ECONOMY';
    case 'gift': case 'buyFreedom': case 'proposeTreaty': case 'breakTreaty': return 'DIPLOMACY';
    case 'acceptTreaty': case 'rejectTreaty': return 'ANSWER';
  }
}

/** Walking or attacking: an Army move may give one to every army (the engine's Order.armyStep). */
export function armyStep(o: Order): boolean {
  return o.order === 'move' || o.order === 'attack';
}

/** This turn's move, if the child has chosen it already. */
export function moveOf(draft: Order[]): Order | undefined {
  return draft.find((o) => kindOf(o) !== 'ANSWER');
}

export type Target =
  | { kind: 'settlement'; id: number; building?: BuildingType }
  | { kind: 'tile'; pos: Pos; building: BuildingType }
  | { kind: 'unit'; id: number; dest: Pos }
  /** The choices are in the side panel (market, tax, treaties). */
  | { kind: 'panel' };

/** One move Grosik suggests. */
export interface Step {
  id: string;
  /** The kind of move the child picks to do it. */
  move: Kind;
  text: string;
  /** Why it is worth it, in one short sentence. */
  why?: string;
  target: Target;
}

/** The moves worth making this turn, most useful first. Empty when there is nothing the child can pay for. */
export function advise(s: GameState, rules: Rules): Step[] {
  const me = s.kingdoms.find((k) => k.id === s.me);
  if (!me || s.finished) return [];
  const free = me.gold ?? 0;
  const mine = s.settlements.filter((x) => x.owner === me.id);
  const castle = mine.find((x) => x.castle);
  const villages = mine.filter((x) => !x.castle);
  const out: Step[] = [];

  const offer = (step: Step, cost: number) => {
    if (cost <= free && !out.some((x) => x.id === step.id)) out.push(step);
  };
  const buildIn = (st: SettlementView | undefined, b: BuildingType, why: string) => {
    if (!st || !buildOptions(rules, s, st, me.palaceDone).includes(b)) return;
    offer({
      id: `build-${st.id}-${b}`,
      move: 'BUILD',
      text: t('adv.build', { building: t(`b.${b}`), place: placeName(st.name), cost: buildingCost(rules, b) }),
      why,
      target: { kind: 'settlement', id: st.id, building: b },
    }, buildingCost(rules, b));
  };

  // 1. hunger and sadness first: they make the kingdom poorer every turn
  const families = mine.reduce((sum, x) => sum + x.families, 0);
  const soldiers = s.units.filter((u) => u.owner === me.id).length;
  if ((me.food ?? 0) < families + soldiers) {
    for (const v of villages) buildIn(v, 'FARM', t('adv.why.farmHungry'));
    offer({ id: 'buy-food', move: 'ECONOMY', text: t('adv.buyFood'), why: t('adv.why.buyFood'), target: { kind: 'panel' } }, s.foodPrice * 3);
  }
  if (me.mood === 'SAD' && me.tax !== 'LOW') {
    offer({ id: 'tax-low', move: 'ECONOMY', text: t('adv.lowTax'), why: t('adv.why.lowTax'), target: { kind: 'panel' } }, 0);
  }

  // 2. a settler waits for its new village: found it here, or walk to the nearest good field
  for (const u of s.units.filter((x) => x.owner === me.id && x.type === 'SETTLER')) {
    if (villageSpot(s, rules, u.pos) === 'ok') {
      offer({ id: `found-${u.id}`, move: 'ARMY', text: t('adv.found'), why: t('adv.why.found'), target: { kind: 'unit', id: u.id, dest: u.pos } }, 0);
      continue;
    }
    const spot = nearestVillageSpot(s, rules, u.pos);
    if (!spot) continue;
    let dest: Pos | undefined;
    for (const k of reachable(s, u, unitRule(rules, u.type).move).keys()) {
      const [x, y] = k.split(',').map(Number);
      const p = { x, y };
      if (s.units.some((o) => samePos(o.pos, p)) || s.settlements.some((o) => samePos(o.pos, p))) continue;
      if (!dest || closer(p, dest, spot)) dest = p;
    }
    if (dest && distance(dest, spot) < distance(u.pos, spot)) {
      offer({
        id: `settler-${u.id}`, move: 'ARMY', text: t('adv.settlerWalk', { n: distance(u.pos, spot) }), why: t('adv.why.found'),
        target: { kind: 'unit', id: u.id, dest },
      }, 0);
    }
  }

  // 3. investments that pay back, cheapest lesson first: a market in the castle, a farm, a gold mine
  buildIn(castle, 'MARKET', t('adv.why.market', { n: rules.balance.marketIncome ?? 5 }));
  for (const v of villages) buildIn(v, 'FARM', t('adv.why.farm'));
  const hills = hillsForMine(s, me);
  if (hills) {
    offer({
      id: 'mine',
      move: 'BUILD',
      text: t('adv.mine', { cost: buildingCost(rules, 'GOLD_MINE') }),
      why: t('adv.why.mine', { n: rules.balance.goldMineIncome ?? 10 }),
      target: { kind: 'tile', pos: hills, building: 'GOLD_MINE' },
    }, buildingCost(rules, 'GOLD_MINE'));
  }

  // 4. a free treasure: walk an idle army towards the nearest old ruins
  const trip = ruinsTrip(s, rules, me);
  if (trip) {
    offer({
      id: `ruins-${trip.unit.id}`,
      move: 'ARMY',
      text: t('adv.ruins', { unit: t(`u.${trip.unit.type}`) }),
      why: t('adv.why.ruins'),
      target: { kind: 'unit', id: trip.unit.id, dest: trip.dest },
    }, 0);
  }

  // 5. later in the game: more markets, the Great Palace, the treasury
  for (const v of villages) buildIn(v, 'MARKET', t('adv.why.market', { n: rules.balance.marketIncome ?? 5 }));
  if (s.turn > (rules.balance.peaceTurns ?? 5)) buildIn(castle, 'GREAT_PALACE', t('adv.why.palace'));
  buildIn(castle, 'TREASURY', t('adv.why.treasury'));
  for (const v of villages) {
    if (v.families >= v.capacity) buildIn(v, 'INN', t('adv.why.inn'));
  }

  // 6. a friend to trade with: both kingdoms earn every turn
  const partner = s.turn >= 3 ? s.kingdoms.find((k) => k.id !== me.id
    && !s.treaties.some((tr) => tr.type === 'TRADE' && (tr.a === k.id || tr.b === k.id) && (tr.a === me.id || tr.b === me.id))) : undefined;
  if (partner) {
    offer({
      id: 'trade',
      move: 'DIPLOMACY',
      text: t('adv.trade'),
      why: t('adv.why.trade', { n: rules.balance.tradeDealIncome ?? 5 }),
      target: { kind: 'panel' },
    }, 0);
  }
  return out;
}

/** When nothing is worth buying yet: Grosik says that saving up is a good move too. */
export function saving(s: GameState, rules: Rules): string | undefined {
  const me = s.kingdoms.find((k) => k.id === s.me);
  if (!me) return undefined;
  const mine = s.settlements.filter((x) => x.owner === me.id);
  const next = cheapestWish(s, rules, me, mine.find((x) => x.castle), mine.filter((x) => !x.castle));
  const free = me.gold ?? 0;
  return next && next.cost > free
    ? t('adv.save', { building: t(`b.${next.building}`), cost: next.cost, n: Math.max(0, free) })
    : undefined;
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

/** An army with old ruins not far away: where to step this turn to get closer. */
function ruinsTrip(s: GameState, rules: Rules, me: KingdomView): { unit: UnitView; dest: Pos } | undefined {
  if (!s.map.ruins.length) return undefined;
  for (const u of s.units.filter((x) => x.owner === me.id && x.type !== 'SETTLER')) {
    const ruin = [...s.map.ruins].sort((a, b) => distance(a, u.pos) - distance(b, u.pos))[0];
    if (distance(ruin, u.pos) > 6) continue;
    const paths = reachable(s, u, unitRule(rules, u.type).move);
    let dest: Pos | undefined;
    for (const k of paths.keys()) {
      const [x, y] = k.split(',').map(Number);
      const p = { x, y };
      if (s.units.some((o) => samePos(o.pos, p)) || s.settlements.some((o) => samePos(o.pos, p))) continue;
      if (!dest || closer(p, dest, ruin)) dest = p;
    }
    if (dest && distance(dest, ruin) < distance(u.pos, ruin)) return { unit: u, dest };
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
