import type { BuildingType, GameState, MapView, Order, Pos, Rules, SettlementView, UnitType, UnitView } from '../types';

// Small copies of engine rules the client needs to draw the map and help the child.
// The server always checks orders again; these only make the screen helpful.

export const TERRAIN = {
  M: { name: 'meadow', cost: 1, passable: true },
  F: { name: 'forest', cost: 2, passable: true },
  H: { name: 'hills', cost: 2, passable: true },
  A: { name: 'mountain', cost: 0, passable: false },
  R: { name: 'river', cost: 2, passable: true },
  L: { name: 'lake', cost: 0, passable: false },
} as const;
export type TerrainCode = keyof typeof TERRAIN;

export const samePos = (a: Pos, b: Pos) => a.x === b.x && a.y === b.y;
export const distance = (a: Pos, b: Pos) => Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y));
export const key = (p: Pos) => `${p.x},${p.y}`;

export function neighbours(p: Pos, map: MapView): Pos[] {
  const out: Pos[] = [];
  for (let dy = -1; dy <= 1; dy++) {
    for (let dx = -1; dx <= 1; dx++) {
      if (dx === 0 && dy === 0) continue;
      const q = { x: p.x + dx, y: p.y + dy };
      if (q.x >= 0 && q.y >= 0 && q.x < map.width && q.y < map.height) out.push(q);
    }
  }
  return out;
}

export function terrainAt(map: MapView, p: Pos): TerrainCode {
  return (map.rows[p.y]?.charAt(p.x) || 'M') as TerrainCode;
}

export function improvementAt(map: MapView, p: Pos): BuildingType | undefined {
  return map.improvements.find((i) => samePos(i.pos, p))?.building;
}

export function moveCost(map: MapView, p: Pos): number {
  const tc = terrainAt(map, p);
  if (tc === 'R' && improvementAt(map, p) === 'BRIDGE') return 1;
  return TERRAIN[tc].cost;
}

export function passable(map: MapView, p: Pos): boolean {
  return TERRAIN[terrainAt(map, p)].passable;
}

/** Owner of each field: nearest settlement within the radius, ties to the older (lower id) settlement. */
export function territory(state: GameState, radius = 2): (string | null)[][] {
  const { width, height } = state.map;
  const owner: (string | null)[][] = Array.from({ length: height }, () => Array<string | null>(width).fill(null));
  const best: number[][] = Array.from({ length: height }, () => Array<number>(width).fill(Infinity));
  const bestId: number[][] = Array.from({ length: height }, () => Array<number>(width).fill(Infinity));
  for (const st of state.settlements) {
    for (let y = st.pos.y - radius; y <= st.pos.y + radius; y++) {
      for (let x = st.pos.x - radius; x <= st.pos.x + radius; x++) {
        if (x < 0 || y < 0 || x >= width || y >= height) continue;
        const d = distance({ x, y }, st.pos);
        if (d < best[y][x] || (d === best[y][x] && st.id < bestId[y][x])) {
          best[y][x] = d;
          bestId[y][x] = st.id;
          owner[y][x] = st.owner;
        }
      }
    }
  }
  return owner;
}

/**
 * Where a unit can walk this turn, with the path to each field.
 * A unit may always take one step; further steps must fit into its movement points.
 */
export function reachable(state: GameState, unit: UnitView, move: number): Map<string, Pos[]> {
  const map = state.map;
  const out = new Map<string, Pos[]>();
  const left = new Map<string, number>([[key(unit.pos), move]]);
  const queue: { pos: Pos; points: number; path: Pos[] }[] = [{ pos: unit.pos, points: move, path: [] }];
  while (queue.length) {
    queue.sort((a, b) => b.points - a.points);
    const cur = queue.shift()!;
    for (const next of neighbours(cur.pos, map)) {
      if (!passable(map, next)) continue;
      const cost = moveCost(map, next);
      const first = cur.path.length === 0;
      if (!first && cost > cur.points) continue;
      const points = cur.points - cost;
      const k = key(next);
      const before = left.get(k);
      if (before !== undefined && before >= points) continue;
      left.set(k, points);
      const path = [...cur.path, next];
      out.set(k, path);
      // you cannot walk on through another unit or a settlement that is not yours
      const blocked = state.units.some((u) => samePos(u.pos, next))
        || state.settlements.some((s) => samePos(s.pos, next) && s.owner !== unit.owner);
      if (points > 0 && !blocked) queue.push({ pos: next, points, path });
    }
  }
  out.delete(key(unit.pos));
  return out;
}

export function unitRule(rules: Rules, type: UnitType) {
  return rules.units.find((u) => u.type === type)!;
}

export function buildingCost(rules: Rules, type: BuildingType): number {
  if (type === 'GREAT_PALACE') return rules.balance.palaceInstallment ?? 100;
  return rules.buildings.find((b) => b.type === type)?.cost ?? 0;
}

/** Gold an order needs this turn; it is put aside when the turn ends. */
export function goldCost(order: Order, rules: Rules, state: GameState): number {
  switch (order.order) {
    case 'build':
    case 'buildOnTile':
      return buildingCost(rules, order.building);
    case 'recruit':
      return unitRule(rules, order.unit).cost;
    case 'gift':
      return order.gold;
    case 'market':
      return order.buyFood * state.foodPrice;
    case 'buyFreedom':
      return rules.balance.freedomPrice ?? 150;
    default:
      return 0;
  }
}

/** Buildings a child may still add in this settlement (the server checks again). */
export function buildOptions(rules: Rules, state: GameState, st: SettlementView, palaceDone: boolean): BuildingType[] {
  const meadowNear = neighbours(st.pos, state.map).some((p) => terrainAt(state.map, p) === 'M');
  return rules.buildings
    .filter((b) => b.place !== 'HILLS_TILE' && b.place !== 'RIVER_TILE')
    .filter((b) => (b.place === 'VILLAGE' ? !st.castle : b.place === 'CASTLE' ? st.castle : true))
    .filter((b) => (b.type === 'GREAT_PALACE' ? !palaceDone : !st.buildings.includes(b.type)))
    .filter((b) => b.type !== 'FARM' || meadowNear)
    .map((b) => b.type);
}

/** Turns until a building has paid for itself, for the ones that make gold. */
export function payback(rules: Rules, type: BuildingType): number | undefined {
  const b = rules.balance;
  const income: Partial<Record<BuildingType, number>> = { MARKET: b.marketIncome, GOLD_MINE: b.goldMineIncome };
  const per = income[type];
  return per ? Math.ceil(buildingCost(rules, type) / per) : undefined;
}

/** Can a settler found a village on this field? The same rule as the engine's foundVillage. */
export function villageSpot(state: GameState, rules: Rules, p: Pos): 'ok' | 'tooClose' | 'badGround' {
  const tc = terrainAt(state.map, p);
  if (tc === 'R' || !passable(state.map, p)) return 'badGround';
  const min = rules.balance.settlerMinDistance ?? 3;
  return state.settlements.some((s) => distance(s.pos, p) < min) ? 'tooClose' : 'ok';
}

/** The nearest field (in steps) where a village can be founded, walking from `from`. */
export function nearestVillageSpot(state: GameState, rules: Rules, from: Pos): Pos | undefined {
  const seen = new Set([key(from)]);
  let ring = [from];
  for (let steps = 0; steps < 30 && ring.length; steps++) {
    const ok = ring.filter((p) => villageSpot(state, rules, p) === 'ok'
      && !state.units.some((u) => samePos(u.pos, p) && !samePos(p, from)));
    if (ok.length) return ok.sort((a, b) => (a.y - b.y) || (a.x - b.x))[0];
    const next: Pos[] = [];
    for (const p of ring) {
      for (const n of neighbours(p, state.map)) {
        if (seen.has(key(n)) || !passable(state.map, n)) continue;
        seen.add(key(n));
        next.push(n);
      }
    }
    ring = next;
  }
  return undefined;
}
