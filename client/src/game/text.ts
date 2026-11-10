import { hasKey, t } from '../i18n';
import type { ChronicleEvent, GameState, LedgerLine, Order } from '../types';

const KINGDOM_PARAMS = new Set(['kingdom', 'by', 'from', 'to', 'with', 'a', 'b', 'vassal', 'attacker', 'defender', 'target']);

export function kingdomName(id: string | undefined | null): string {
  if (!id) return '';
  return hasKey(`k.${id}`) ? t(`k.${id}`) : id;
}

const PLACE_PARAMS = new Set(['village', 'castle', 'settlement']);

/** The name of a castle or village in the chosen language (the server names them in English; new villages keep the child's name). */
export function placeName(name: string | undefined | null): string {
  if (!name) return '';
  return hasKey(`place.${name}`) ? t(`place.${name}`) : name;
}

/** Who plays a kingdom: the child's name, or the computer level. */
export function playerLabel(k: { playerName?: string; controller: string }): string {
  return k.controller === 'HUMAN' ? k.playerName ?? '' : t(`ctl.${k.controller}`);
}

/** Turns raw values from the server (kingdom ids, BUILDING names…) into words in the chosen language. */
export function niceParams(params: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [name, value] of Object.entries(params)) {
    if (typeof value !== 'string') {
      out[name] = value;
    } else if (KINGDOM_PARAMS.has(name)) {
      out[name] = kingdomName(value);
    } else if (PLACE_PARAMS.has(name)) {
      out[name] = placeName(value);
    } else if (name === 'winners') {
      out[name] = value.split(',').filter(Boolean).map(kingdomName).join(', ');
    } else if (hasKey(`b.${value}`)) {
      out[name] = t(`b.${value}`);
    } else if (hasKey(`u.${value}`)) {
      out[name] = t(`u.${value}`);
    } else if (hasKey(`t.${value}`)) {
      out[name] = t(`t.${value}`);
    } else if (hasKey(`tax.${value}`)) {
      out[name] = t(`tax.${value}`);
    } else {
      out[name] = value;
    }
  }
  return out;
}

export function eventText(e: ChronicleEvent): string {
  const p = niceParams(e.params);
  if (e.key === 'chronicle.battle' || e.key === 'chronicle.meeting') {
    p.result = t(e.params.attackerWins ? 'chronicle.attackerWon' : 'chronicle.defenderWon');
  }
  if (e.key === 'chronicle.built') {
    p.where = e.params.settlement ? t('where.at', { settlement: p.settlement }) : '';
  }
  return t(e.key, p);
}

export function ledgerText(l: LedgerLine): string {
  return t(l.key, niceParams(l.params));
}

export function orderText(o: Order, s: GameState): string {
  const settlement = (id: number) => placeName(s.settlements.find((x) => x.id === id)?.name) || '?';
  const unit = (id: number) => {
    const u = s.units.find((x) => x.id === id);
    return u ? t(`u.${u.type}`) : '?';
  };
  switch (o.order) {
    case 'setTax':
      return t('o.setTax', { rate: t(`tax.${o.rate}`) });
    case 'build':
      return t('o.build', { building: t(`b.${o.building}`), settlement: settlement(o.settlementId) });
    case 'buildOnTile':
      return t('o.buildOnTile', { building: t(`b.${o.building}`), x: o.pos.x, y: o.pos.y });
    case 'recruit':
      return t('o.recruit', { unit: t(`u.${o.unit}`), settlement: settlement(o.settlementId) });
    case 'move': {
      const end = o.path[o.path.length - 1];
      return t('o.move', { unit: unit(o.unitId), x: end?.x, y: end?.y });
    }
    case 'attack':
      return t('o.attack', { unit: unit(o.unitId), x: o.target.x, y: o.target.y });
    case 'foundVillage':
      return t('o.foundVillage', { unit: unit(o.unitId), name: o.name });
    case 'gift':
      return t('o.gift', { to: kingdomName(o.to), gold: o.gold, food: o.food });
    case 'market':
      return t('o.market', { buy: o.buyFood, sell: o.sellFood });
    case 'takeLoan':
      return t('o.takeLoan');
    case 'buyFreedom':
      return t('o.buyFreedom');
    case 'proposeTreaty':
      return t('o.proposeTreaty', { type: t(`t.${o.type}`), to: kingdomName(o.to) });
    case 'acceptTreaty':
      return t('o.acceptTreaty', { type: t(`t.${o.type}`), from: kingdomName(o.from) });
    case 'rejectTreaty':
      return t('o.rejectTreaty', { type: t(`t.${o.type}`), from: kingdomName(o.from) });
    case 'breakTreaty':
      return t('o.breakTreaty', { type: t(`t.${o.type}`), with: kingdomName(o.with) });
  }
}

export function season(turn: number): string {
  return t(`season.${(turn - 1) % 4}`);
}

export function clock(ms: number): string {
  const total = Math.max(0, Math.ceil(ms / 1000));
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
}
