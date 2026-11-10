import { describe, expect, it } from 'vitest';
import { advise, kindOf, moveOf, saving } from '../src/game/advisor';
import { setLang } from '../src/i18n';
import type { GameState, KingdomView, Order, Rules } from '../src/types';

const rules: Rules = {
  buildings: [
    { type: 'FARM', cost: 30, place: 'VILLAGE' },
    { type: 'MARKET', cost: 50, place: 'SETTLEMENT' },
    { type: 'GOLD_MINE', cost: 60, place: 'HILLS_TILE' },
    { type: 'TREASURY', cost: 80, place: 'CASTLE' },
    { type: 'GREAT_PALACE', cost: 500, place: 'CASTLE' },
  ],
  units: [{ type: 'SPEARMAN', cost: 20, upkeep: 2, attack: 2, defense: 3, move: 1, needsBarracks: false }],
  kingdoms: [],
  balance: { palaceInstallment: 100, marketIncome: 5, goldMineIncome: 10, peaceTurns: 5 },
};

function fox(gold: number, food = 20): KingdomView {
  return {
    id: 'fox', name: 'Fox', color: '#e0414b', controller: 'HUMAN', autopilot: false, mood: 'NORMAL', prestige: 40,
    palaceDone: false, palacePaid: 0, promiseBreaker: false, gold, food, tax: 'LOW',
  };
}

/** A small kingdom: castle at 2,2, village at 5,2, hills at 3,3, ruins far away at 12,2. */
function start(gold = 100): GameState {
  return {
    code: '123456', status: 'PLAYING', turn: 1, maxTurns: 20, turnSeconds: 0, foodPrice: 3, me: 'fox',
    map: {
      width: 14, height: 5,
      rows: ['MMMMMMMMMMMMMM', 'MMMMMMMMMMMMMM', 'MMMMMMMMMMMMMM', 'MMMHMMMMMMMMMM', 'MMMMMMMMMMMMMM'],
      improvements: [], ruins: [{ x: 12, y: 2 }],
    },
    kingdoms: [fox(gold)],
    settlements: [
      { id: 1, name: 'Foxhold', pos: { x: 2, y: 2 }, castle: true, owner: 'fox', families: 3, capacity: 10, buildings: [] },
      { id: 2, name: 'Oakvale', pos: { x: 5, y: 2 }, castle: false, owner: 'fox', families: 3, capacity: 5, buildings: [] },
    ],
    units: [{ id: 7, owner: 'fox', type: 'SPEARMAN', pos: { x: 2, y: 2 } }],
    treaties: [], proposals: [], myOrders: [], ready: [], finished: false, winners: [],
  };
}

setLang('pl');

describe('Grosik, the advisor', () => {
  it('starts the first turn with a market in the castle, then a farm', () => {
    const steps = advise(start(), rules);
    expect(steps.map((x) => x.id).slice(0, 2)).toEqual(['build-1-MARKET', 'build-2-FARM']);
    expect(steps[0].move).toBe('BUILD');
    expect(steps[0].target).toEqual({ kind: 'settlement', id: 1, building: 'MARKET' });
  });

  it('only suggests what the child can pay for', () => {
    const steps = advise(start(40), rules);
    expect(steps.map((x) => x.id)).toEqual(['build-2-FARM']);
  });

  it('says that saving is a good move when nothing is affordable', () => {
    expect(advise(start(10), rules)).toEqual([]);
    expect(saving(start(10), rules)).toContain('30');
  });

  it('suggests a gold mine on the hills once the market and the farm are there', () => {
    const s = start(100);
    s.settlements[0].buildings = ['MARKET'];
    s.settlements[1].buildings = ['FARM'];
    expect(advise(s, rules)[0].target).toEqual({ kind: 'tile', pos: { x: 3, y: 3 }, building: 'GOLD_MINE' });
  });

  it('sends an army one step towards ruins that are close: an Army move', () => {
    const s = start(0);
    s.map.ruins = [{ x: 6, y: 4 }];
    const step = advise(s, rules).find((x) => x.id === 'ruins-7');
    expect(step?.move).toBe('ARMY');
    expect(step?.target).toMatchObject({ kind: 'unit', id: 7, dest: { x: 3, y: 3 } });
  });

  it('feeds hungry people first', () => {
    const s = start(100);
    s.kingdoms[0].food = 2;
    expect(advise(s, rules)[0].id).toBe('build-2-FARM');
  });

  it('suggests a trade deal, a Diplomacy move, from the third turn', () => {
    const s = start(0);
    s.kingdoms.push({ ...fox(0), id: 'owl', name: 'Owl' });
    expect(advise(s, rules).some((x) => x.id === 'trade')).toBe(false);
    s.turn = 3;
    expect(advise(s, rules).find((x) => x.id === 'trade')?.move).toBe('DIPLOMACY');
  });
});

describe('one turn, one move', () => {
  it('sorts every order into a kind of move; answers to offers are free', () => {
    const kinds = ([
      { order: 'move', unitId: 1, path: [] }, { order: 'recruit', settlementId: 1, unit: 'SPEARMAN' },
      { order: 'build', settlementId: 1, building: 'FARM' }, { order: 'setTax', rate: 'LOW' }, { order: 'takeLoan' },
      { order: 'proposeTreaty', to: 'owl', type: 'PACT' }, { order: 'acceptTreaty', from: 'owl', type: 'PACT' },
      { order: 'rejectTreaty', from: 'owl', type: 'TRADE' },
    ] as Order[]).map(kindOf);
    expect(kinds).toEqual(['ARMY', 'ARMY', 'BUILD', 'ECONOMY', 'ECONOMY', 'DIPLOMACY', 'ANSWER', 'ANSWER']);
  });

  it('finds the move among the answers', () => {
    const draft: Order[] = [{ order: 'acceptTreaty', from: 'owl', type: 'PACT' }, { order: 'setTax', rate: 'LOW' }];
    expect(moveOf(draft)).toEqual({ order: 'setTax', rate: 'LOW' });
    expect(moveOf(draft.slice(0, 1))).toBeUndefined();
  });
});
