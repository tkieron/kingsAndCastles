import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { en } from '../src/i18n/en';
import { pl } from '../src/i18n/pl';
import { setLang, t } from '../src/i18n';
import { eventText } from '../src/game/text';

function javaFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    return statSync(p).isDirectory() ? javaFiles(p) : p.endsWith('.java') ? [p] : [];
  });
}

describe('texts', () => {
  it('has an English and a Polish text for every message the server can send', () => {
    const sources = [
      ...javaFiles(join(__dirname, '../../engine/src/main/java')),
      ...javaFiles(join(__dirname, '../../server/src/main/java')),
    ].map((f) => readFileSync(f, 'utf8')).join('\n');
    const keys = new Set([...sources.matchAll(/"((?:chronicle|ledger|error|event)\.[A-Za-z]+)"/g)].map((m) => m[1]));
    expect(keys.size).toBeGreaterThan(80);
    const missing = [...keys].filter((k) => k !== 'error.key' && (!(k in en) || !(k in pl)));
    expect(missing).toEqual([]);
  });

  it('keeps the same placeholders in both languages', () => {
    const names = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort().join(',');
    const differ = (Object.keys(en) as (keyof typeof en)[]).filter((k) => names(en[k]) !== names(pl[k]));
    expect(differ).toEqual([]);
  });

  it('writes a battle with kingdom and unit names in the chosen language', () => {
    const battle = {
      phase: 5, key: 'chronicle.battle', visibleTo: ['fox', 'owl'],
      params: {
        attacker: 'fox', attackerUnit: 'KNIGHT', attackerBase: 5, attackerBonus: 0, attackerDice: 3, attackerTotal: 8,
        defender: 'owl', defenderUnit: 'ARCHER', defenderBase: 2, defenderBonus: 1, defenderDice: 1, defenderTotal: 4,
        attackerWins: true,
      },
    };
    setLang('en');
    expect(eventText(battle)).toContain('Fox Knight');
    expect(eventText(battle)).toContain('The attacker won!');
    setLang('pl');
    expect(eventText(battle)).toContain('Lis Rycerz');
    expect(t('w.gold')).toBe('Złoto');
    setLang('en');
  });
});
