import { api, ApiError, saveSeat, type Seat } from '../api';
import { append, h } from '../dom';
import { t } from '../i18n';
import { inkOn } from '../game/sprites';
import { kingdomName, setRealms } from '../game/text';
import { avatarSvg } from '../game/avatars';
import { identityFields } from './HomeScreen';
import type { Lobby } from '../types';

/** Waiting room: shows the code, who sits where, and the host's Start button. */
export class LobbyScreen {
  readonly root = h('div', { class: 'lobby' });
  private lobby?: Lobby;
  private host = false;
  private error = '';

  constructor(private seat: Seat, private readonly reconnect: (seat: Seat) => void, private readonly onLeave: () => void) {}

  update(lobby: Lobby, host: boolean): void {
    this.lobby = lobby;
    this.host = host;
    setRealms(lobby.seats.map((s) => ({ id: s.kingdom, realm: s.realm })));
    this.render();
  }

  private render(): void {
    const l = this.lobby;
    if (!l) return;
    const link = `${location.origin}${location.pathname}?room=${l.code}`;
    const seats = h('ol', { class: 'lobby-seats' }, ...l.seats.map((s) => h('li', { class: 'row' },
      s.player
        ? h('div', { class: 'chip-avatar', style: `border-color:${s.color}` }, avatarSvg(s.avatar, 28))
        : h('div', { class: 'badge', style: `background:${s.color};color:${inkOn(s.color)}` }, kingdomName(s.kingdom).charAt(0)),
      h('b', { class: 'seat-name' }, kingdomName(s.kingdom)),
      s.controller === 'HUMAN'
        ? h('span', { class: s.player ? '' : 'muted' }, s.player ?? t('lobby.free'))
        : h('span', { class: 'muted' }, t(`ctl.${s.controller}`)))));
    const players = l.seats.filter((s) => s.player).length;
    const who = identityFields();
    this.root.replaceChildren();
    append(this.root,
      h('h1', {}, t('lobby.title', { code: l.code })),
      h('p', {}, t('lobby.tellCode')),
      h('div', { class: 'big-code', 'aria-label': l.code }, l.code),
      h('p', { class: 'small muted' }, link),
      seats,
      this.error ? h('p', { class: 'bad', role: 'alert' }, this.error) : null,
      this.host
        ? h('div', { class: 'col gap' },
          !this.seat.token ? h('form', {
            class: 'card-light col gap',
            onsubmit: async (e: Event) => {
              e.preventDefault();
              try {
                const r = await api.join(l.code, who.name.value, who.realm.value, who.avatar());
                this.seat = { ...this.seat, token: r.token };
                saveSeat(this.seat);
                this.reconnect(this.seat);
              } catch (err) {
                this.error = t(err instanceof ApiError ? err.key : 'error.badRequest');
                this.render();
              }
            },
          }, who.el, h('button', { class: 'btn', type: 'submit' }, t('lobby.hostJoin'))) : null,
          h('button', {
            class: 'btn-ready',
            onclick: async () => {
              try {
                await api.start(l.code, this.seat.hostToken!);
              } catch (err) {
                this.error = t(err instanceof ApiError ? err.key : 'error.badRequest');
                this.render();
              }
            },
          }, t('lobby.start')))
        : h('p', { class: 'muted' }, players ? t('lobby.waitHost') : t('lobby.waiting')),
      h('button', { class: 'btn small', onclick: () => this.onLeave() }, t('game.leave')));
  }
}
