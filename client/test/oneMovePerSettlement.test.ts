import { describe, expect, it } from 'vitest';
import { addToMove, countedOrders } from '../src/game/advisor';
import { ordersAllowed } from '../src/game/rules';
import type { GameState, Order, Rules, SettlementView } from '../src/types';

const rules = (balance: Record<string, number>): Rules => ({ buildings: [], units: [], kingdoms: [], balance });

function state(owners: string[]): GameState {
  const settlements: SettlementView[] = owners.map((owner, i) => ({
    id: i + 1, name: `S${i}`, pos: { x: i * 4, y: 0 }, castle: i === 0, owner, families: 3, capacity: 10, buildings: [],
  }));
  return {
    code: '123456', status: 'PLAYING', turn: 1, maxTurns: 60, turnSeconds: 0, foodPrice: 3, me: 'fox',
    map: { width: 12, height: 1, rows: ['MMMMMMMMMMMM'], improvements: [], ruins: [] },
    kingdoms: [], settlements, units: [], treaties: [], proposals: [], myOrders: [], ready: [],
    finished: false, winners: [],
  };
}

describe('ordersAllowed', () => {
  it('gives a Buildings or Army move one order per own settlement', () => {
    const r = rules({ actionsPerTurn: 1, ordersPerSettlement: 1 });
    const s = state(['fox', 'fox', 'owl']);
    expect(ordersAllowed(r, s, 'BUILD')).toBe(2);
    expect(ordersAllowed(r, s, 'ARMY')).toBe(2);
  });

  it('keeps economy and diplomacy at one order', () => {
    const r = rules({ actionsPerTurn: 1, ordersPerSettlement: 1 });
    const s = state(['fox', 'fox', 'fox']);
    expect(ordersAllowed(r, s, 'ECONOMY')).toBe(1);
    expect(ordersAllowed(r, s, 'DIPLOMACY')).toBe(1);
  });

  it('falls back to one order when the server does not send ordersPerSettlement', () => {
    expect(ordersAllowed(rules({ actionsPerTurn: 1 }), state(['fox', 'fox']), 'BUILD')).toBe(1);
  });
});

describe('addToMove', () => {
  const build = (settlementId: number): Order => ({ order: 'build', settlementId, building: 'MARKET' });
  const accept: Order = { order: 'acceptTreaty', from: 'owl', type: 'TRADE' };

  it('keeps an order in every settlement while the move has room', () => {
    let d: Order[] = [];
    d = addToMove(d, build(1), 2);
    d = addToMove(d, build(2), 2);
    expect(d).toEqual([build(1), build(2)]);
  });

  it('lets the newest order take the oldest one\'s place when the move is full', () => {
    let d: Order[] = [];
    d = addToMove(d, build(1), 1);
    d = addToMove(d, build(2), 1);
    expect(d).toEqual([build(2)]);
  });

  it('takes back a move of another kind but keeps answers to offers', () => {
    let d: Order[] = [accept, build(1), build(2)];
    d = addToMove(d, { order: 'setTax', rate: 'LOW' }, 1);
    expect(d).toEqual([accept, { order: 'setTax', rate: 'LOW' }]);
  });

  it('walking armies do not use up places, training does', () => {
    let d: Order[] = [];
    d = addToMove(d, { order: 'move', unitId: 1, path: [{ x: 1, y: 0 }] }, 1);
    d = addToMove(d, { order: 'move', unitId: 2, path: [{ x: 2, y: 0 }] }, 1);
    d = addToMove(d, { order: 'recruit', settlementId: 1, unit: 'SPEARMAN' }, 1);
    expect(d).toHaveLength(3);
    expect(countedOrders(d)).toEqual([{ order: 'recruit', settlementId: 1, unit: 'SPEARMAN' }]);
  });
});
