import { describe, expect, it } from 'vitest';
import { goldCost, reachable, territory } from '../src/game/rules';
import type { GameState, Rules } from '../src/types';

const rules: Rules = {
  buildings: [
    { type: 'MARKET', cost: 50, place: 'SETTLEMENT' },
    { type: 'GREAT_PALACE', cost: 500, place: 'CASTLE' },
  ],
  units: [
    { type: 'SPEARMAN', cost: 20, upkeep: 2, attack: 2, defense: 3, move: 1, needsBarracks: false },
    { type: 'KNIGHT', cost: 50, upkeep: 5, attack: 5, defense: 3, move: 2, needsBarracks: true },
  ],
  kingdoms: [],
  balance: { palaceInstallment: 100, freedomPrice: 150 },
};

function world(rows: string[]): GameState {
  return {
    code: '123456', status: 'PLAYING', turn: 1, maxTurns: 20, turnSeconds: 60, foodPrice: 3, me: 'fox',
    map: { width: rows[0].length, height: rows.length, rows, improvements: [], ruins: [] },
    kingdoms: [], settlements: [], units: [], treaties: [], proposals: [], myOrders: [], ready: [],
    finished: false, winners: [],
  };
}

describe('territory', () => {
  it('gives each field to the nearest settlement within 2, ties to the older one', () => {
    const s = world(['MMMMMMM', 'MMMMMMM', 'MMMMMMM']);
    s.settlements = [
      { id: 1, name: 'A', pos: { x: 1, y: 1 }, castle: true, owner: 'fox', families: 3, capacity: 10, buildings: [] },
      { id: 2, name: 'B', pos: { x: 5, y: 1 }, castle: true, owner: 'owl', families: 3, capacity: 10, buildings: [] },
    ];
    const t = territory(s);
    expect(t[1][0]).toBe('fox');
    expect(t[1][3]).toBe('fox'); // same distance from both: the older settlement wins
    expect(t[1][4]).toBe('owl');
    expect(t[0][6]).toBe('owl');
  });
});

describe('reachable', () => {
  it('always allows one step, even into the forest', () => {
    const s = world(['MFM', 'MMM']);
    const unit = { id: 1, owner: 'fox', type: 'SPEARMAN' as const, pos: { x: 0, y: 0 } };
    const r = reachable(s, unit, 1);
    expect(r.has('1,0')).toBe(true);
    expect(r.has('2,0')).toBe(false);
  });

  it('lets a knight walk two meadows but not over a mountain', () => {
    const s = world(['MMMA', 'MMMM']);
    const unit = { id: 1, owner: 'fox', type: 'KNIGHT' as const, pos: { x: 0, y: 0 } };
    const r = reachable(s, unit, 2);
    expect(r.get('2,0')).toEqual([{ x: 1, y: 0 }, { x: 2, y: 0 }]);
    expect(r.has('3,0')).toBe(false);
  });

  it('does not walk on through another army', () => {
    const s = world(['MMMM']);
    s.units = [{ id: 2, owner: 'owl', type: 'SPEARMAN', pos: { x: 1, y: 0 } }];
    const unit = { id: 1, owner: 'fox', type: 'KNIGHT' as const, pos: { x: 0, y: 0 } };
    const r = reachable(s, unit, 2);
    expect(r.has('2,0')).toBe(false);
  });
});

describe('goldCost', () => {
  it('counts the palace as one installment and food at the market price', () => {
    const s = world(['M']);
    expect(goldCost({ order: 'build', settlementId: 1, building: 'GREAT_PALACE' }, rules, s)).toBe(100);
    expect(goldCost({ order: 'market', buyFood: 4, sellFood: 0 }, rules, s)).toBe(12);
    expect(goldCost({ order: 'recruit', settlementId: 1, unit: 'KNIGHT' }, rules, s)).toBe(50);
    expect(goldCost({ order: 'setTax', rate: 'HIGH' }, rules, s)).toBe(0);
  });
});
