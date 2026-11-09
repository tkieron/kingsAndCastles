import { describe, expect, it } from 'vitest';
import { advise, current } from '../src/game/advisor';
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
  it('starts the first turn with a market in the castle, then a farm, then "end the turn"', () => {
    const steps = advise(start(), rules, [], false);
    expect(steps.map((x) => x.id)).toEqual(['build-1-MARKET', 'build-2-FARM', 'ready']);
    expect(current(steps)?.target).toEqual({ kind: 'settlement', id: 1, building: 'MARKET' });
  });

  it('ticks a step off when the child gives that order, and moves on to the next one', () => {
    const draft: Order[] = [{ order: 'build', settlementId: 1, building: 'MARKET' }];
    const steps = advise(start(), rules, draft, false);
    expect(steps.find((x) => x.id === 'build-1-MARKET')?.done).toBe(true);
    expect(current(steps)?.id).toBe('build-2-FARM');
  });

  it('only suggests what the child can pay for', () => {
    const steps = advise(start(40), rules, [], false);
    expect(steps.map((x) => x.id)).toEqual(['build-2-FARM', 'ready']);
  });

  it('says that saving is a good move when nothing is affordable', () => {
    const steps = advise(start(10), rules, [], false);
    expect(steps[0].id).toBe('save');
    expect(steps[0].text).toContain('30');
    expect(current(steps)?.id).toBe('ready');
  });

  it('suggests a gold mine on the hills once the market and the farm are there', () => {
    const s = start(100);
    s.settlements[0].buildings = ['MARKET'];
    s.settlements[1].buildings = ['FARM'];
    const now = current(advise(s, rules, [], false));
    expect(now?.target).toEqual({ kind: 'tile', pos: { x: 3, y: 3 }, building: 'GOLD_MINE' });
  });

  it('sends an idle army one step towards ruins that are close', () => {
    const s = start(0);
    s.map.ruins = [{ x: 6, y: 4 }];
    const step = advise(s, rules, [], false).find((x) => x.id === 'ruins-7');
    expect(step?.target).toMatchObject({ kind: 'unit', id: 7, dest: { x: 3, y: 3 } });
  });

  it('feeds hungry people first', () => {
    const s = start(100);
    s.kingdoms[0].food = 2;
    expect(current(advise(s, rules, [], false))?.id).toBe('build-2-FARM');
  });

  it('is finished once the child pressed Ready', () => {
    const steps = advise(start(), rules, [], true);
    expect(steps.find((x) => x.id === 'ready')?.done).toBe(true);
  });
});
