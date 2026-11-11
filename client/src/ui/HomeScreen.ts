import { api, ApiError, isLocal, saveSeat, savedSeat, type Seat } from '../api';
import { h } from '../dom';
import { getLang, setLang, t, type Lang } from '../i18n';
import { inkOn } from '../game/sprites';
import { defaultKingdomName, kingdomName } from '../game/text';
import { AVATAR_COUNT, avatarSvg } from '../game/avatars';
import type { Controller, Rules } from '../types';
import { castleIcon } from './GameScreen';

const LEVELS: Controller[] = ['HUMAN', 'AI_BEGINNER', 'AI_ADVANCED', 'AI_PRO'];
const DEFAULT_SEATS: Controller[] = ['HUMAN', 'HUMAN', 'HUMAN', 'AI_PRO', 'AI_BEGINNER', 'AI_ADVANCED', 'AI_ADVANCED'];
const TURN_LENGTHS = [0, 30, 60, 90, 120, 180];

/**
 * Who the child is: their name (needed), their kingdom's name (optional: empty keeps the kingdom's own name)
 * and one of ten pictures. Used on the start screen and in the lobby.
 */
export function identityFields(realmPlaceholder = ''): { el: HTMLElement; name: HTMLInputElement; realm: HTMLInputElement; avatar: () => number } {
  const name = h('input', {
    class: 'input', maxlength: 20, autocomplete: 'nickname', required: true, 'aria-label': t('home.name'),
  }) as HTMLInputElement;
  name.addEventListener('invalid', () => name.setCustomValidity(t('error.badName')));
  name.addEventListener('input', () => name.setCustomValidity(''));
  const realm = h('input', {
    class: 'input', maxlength: 24, autocomplete: 'off', 'aria-label': t('home.realm'), placeholder: realmPlaceholder,
  }) as HTMLInputElement;
  let chosen = Math.floor(Math.random() * AVATAR_COUNT);
  const buttons = Array.from({ length: AVATAR_COUNT }, (_, i) => h('button', {
    type: 'button', class: `avatar-pick${i === chosen ? ' chosen' : ''}`, role: 'radio',
    'aria-checked': String(i === chosen), 'aria-label': t('home.avatarN', { n: i + 1 }),
    onclick: () => {
      chosen = i;
      buttons.forEach((b, j) => {
        b.classList.toggle('chosen', j === i);
        b.setAttribute('aria-checked', String(j === i));
      });
    },
  }, avatarSvg(i, 48)));
  const el = h('div', { class: 'col gap' },
    h('label', { class: 'col' }, h('span', {}, t('home.name'), h('span', { class: 'need' }, ' *')), name),
    h('div', { class: 'col' }, h('span', { class: 'field-title' }, t('home.avatar')),
      h('div', { class: 'avatar-grid', role: 'radiogroup', 'aria-label': t('home.avatar') }, ...buttons)),
    h('label', { class: 'col' }, t('home.realm'), realm, h('span', { class: 'small muted-dark' }, t('home.realmHint'))));
  return { el, name, realm, avatar: () => chosen };
}

/** First screen: join with a code, or (for the teacher or parent) create a room. */
export function homeScreen(rules: Rules | null, go: (seat: Seat) => void, relang: () => void): HTMLElement {
  const error = h('p', { class: 'bad', role: 'alert' });
  const fail = (e: unknown) => {
    error.textContent = t(e instanceof ApiError ? e.key : 'error.badRequest');
  };

  const langs = h('div', { class: 'row lang' }, h('span', { class: 'small muted' }, t('home.language')),
    ...(['en', 'pl'] as Lang[]).map((l) => h('button', {
      class: `btn small${getLang() === l ? ' chosen' : ''}`,
      onclick: () => {
        setLang(l);
        relang();
      },
    }, l === 'en' ? 'English' : 'Polski')));

  const params = new URLSearchParams(location.search);
  const code = h('input', {
    class: 'input code', inputmode: 'numeric', maxlength: 6, autocomplete: 'off', placeholder: '123456',
    value: params.get('room') ?? '', 'aria-label': t('home.code'),
  });
  const who = identityFields();
  const join = h('form', {
    class: 'card-light col gap',
    onsubmit: async (e: Event) => {
      e.preventDefault();
      error.textContent = '';
      try {
        const c = code.value.replace(/\D/g, '');
        const r = await api.join(c, who.name.value, who.realm.value, who.avatar());
        const seat = { code: c, token: r.token };
        saveSeat(seat);
        go(seat);
      } catch (err) {
        fail(err);
      }
    },
  },
  h('h2', {}, t('home.join')),
  h('label', { class: 'col' }, t('home.code'), code),
  who.el,
  h('button', { class: 'btn-ready', type: 'submit' }, t('home.joinButton')));

  const turnSel = h('select', { class: 'input', 'aria-label': t('home.turnLength') },
    ...TURN_LENGTHS.map((s) => h('option', { value: s, selected: s === 60 }, s ? t('home.seconds', { n: s }) : t('home.noLimit'))));
  const turnsSel = h('select', { class: 'input', 'aria-label': t('home.turns') },
    ...[10, 15, 20, 25, 30].map((n) => h('option', { value: n, selected: n === 20 }, String(n))));
  const seatSels = DEFAULT_SEATS.map((c, i) => h('select', { class: 'input', 'aria-label': `${t('lobby.seatKind')} ${i + 1}` },
    ...LEVELS.map((l) => h('option', { value: l, selected: l === c }, t(`ctl.${l}`)))));
  const emptySel = h('select', { class: 'input', 'aria-label': t('lobby.emptyLevel') },
    ...LEVELS.slice(1).map((l) => h('option', { value: l }, t(`ctl.${l}`))));
  const kingdoms = rules?.kingdoms ?? [];
  const create = h('form', {
    class: 'card-light col gap',
    onsubmit: async (e: Event) => {
      e.preventDefault();
      error.textContent = '';
      try {
        const r = await api.create({
          turnSeconds: Number(turnSel.value),
          maxTurns: Number(turnsSel.value),
          seats: seatSels.map((s) => s.value as Controller),
          emptySeatLevel: emptySel.value as Controller,
        });
        const seat = { code: r.code, hostToken: r.hostToken };
        saveSeat(seat);
        go(seat);
      } catch (err) {
        fail(err);
      }
    },
  },
  h('h2', {}, t('home.create')),
  h('div', { class: 'row wrap' }, h('label', { class: 'col' }, t('home.turnLength'), turnSel), h('label', { class: 'col' }, t('home.turns'), turnsSel)),
  h('div', { class: 'seats' }, ...seatSels.map((sel, i) => {
    const k = kingdoms[i];
    return h('div', { class: 'row' },
      h('div', { class: 'badge', style: k ? `background:${k.color};color:${inkOn(k.color)}` : '' }, k ? kingdomName(k.id).charAt(0) : String(i + 1)),
      h('span', { class: 'seat-name' }, k ? kingdomName(k.id) : String(i + 1)), sel);
  })),
  h('label', { class: 'col' }, t('lobby.emptyLevel'), emptySel),
  h('button', { class: 'btn', type: 'submit' }, t('home.createButton')));

  // offline version: one child against six computer kings, straight into the game
  // the child always gets the first kingdom against the computer: its own name is the one "z urzędu"
  const soloWho = identityFields(kingdoms[0] ? defaultKingdomName(kingdoms[0].id) : '');
  const soloTurn = h('select', { class: 'input', 'aria-label': t('home.turnLength') },
    ...TURN_LENGTHS.map((s) => h('option', { value: s, selected: s === 0 }, s ? t('home.seconds', { n: s }) : t('home.noLimit'))));
  const soloTurns = h('select', { class: 'input', 'aria-label': t('home.turns') },
    ...[10, 15, 20, 25, 30].map((n) => h('option', { value: n, selected: n === 20 }, String(n))));
  const soloLevel = h('select', { class: 'input', 'aria-label': t('home.soloLevel') },
    ...LEVELS.slice(1).map((l) => h('option', { value: l, selected: l === 'AI_BEGINNER' }, t(`ctl.${l}`))));
  const solo = h('form', {
    class: 'card-light col gap',
    onsubmit: async (e: Event) => {
      e.preventDefault();
      error.textContent = '';
      try {
        const level = soloLevel.value as Controller;
        const r = await api.create({
          turnSeconds: Number(soloTurn.value),
          maxTurns: Number(soloTurns.value),
          seats: ['HUMAN', level, level, level, level, level, level],
          emptySeatLevel: level,
        });
        const joined = await api.join(r.code, soloWho.name.value, soloWho.realm.value, soloWho.avatar());
        await api.start(r.code, r.hostToken);
        const seat = { code: r.code, hostToken: r.hostToken, token: joined.token };
        saveSeat(seat);
        go(seat);
      } catch (err) {
        fail(err);
      }
    },
  },
  h('h2', {}, t('home.solo')),
  soloWho.el,
  h('div', { class: 'row wrap' }, h('label', { class: 'col' }, t('home.turnLength'), soloTurn), h('label', { class: 'col' }, t('home.turns'), soloTurns)),
  h('label', { class: 'col' }, t('home.soloLevel'), soloLevel),
  h('button', { class: 'btn-ready', type: 'submit' }, t('home.soloButton')));

  const offline = isLocal();
  const saved = savedSeat();
  return h('div', { class: 'home' },
    h('header', { class: 'home-head' }, castleIcon(), h('h1', {}, t('app.name'))),
    h('p', { class: 'tagline' }, t('home.tagline')),
    langs,
    offline ? h('p', { class: 'small muted center' }, t('home.offline')) : null,
    saved ? h('button', { class: 'btn-ready', onclick: () => go(saved) }, t('home.continue', { code: saved.code })) : null,
    error,
    // offline there is one child on one device: only "play against the computer", nothing to set up for a host
    h('div', { class: 'home-cards' }, offline ? solo : join, offline ? null : create));
}
