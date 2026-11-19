import qrcode from 'qrcode-generator';
import { api, ApiError, isLocal, type Seat } from '../api';
import { h } from '../dom';
import { t } from '../i18n';

/** K7MQ4QX2 → K7MQ-4QX2: two groups are easier to read off a screen and type in. */
export function formatCode(code: string): string {
  const c = code.replace(/[\s-]/g, '').toUpperCase();
  return c.length === 8 ? `${c.slice(0, 4)}-${c.slice(4)}` : c;
}

/**
 * The address that opens this game on another device and comes straight back to the seat. The code sits after "#",
 * which the browser never sends to a server, so it does not end up in server or proxy logs.
 */
export function resumeLink(room: string, code: string, base = `${location.origin}${location.pathname}`): string {
  return `${base}#room=${encodeURIComponent(room)}&resume=${encodeURIComponent(formatCode(code))}`;
}

/** Reads #room=…&resume=… from the address, and removes it at once so the code does not stay in the history. */
export function takeResumeFromAddress(): { room: string; code: string } | null {
  const params = new URLSearchParams(location.hash.replace(/^#/, ''));
  const code = params.get('resume');
  const room = params.get('room');
  if (!code) return null;
  try {
    history.replaceState(null, '', `${location.pathname}${location.search}`);
  } catch {
    /* some embedded browsers refuse: the code then simply stays in the address */
  }
  return room ? { room: room.replace(/\D/g, ''), code } : null;
}

/** A QR code drawn as one SVG path, black on white, with the quiet border scanners need. */
export function qrSvg(text: string, size = 200): SVGSVGElement {
  const qr = qrcode(0, 'M');
  qr.addData(text);
  qr.make();
  const n = qr.getModuleCount();
  const margin = 4;
  let d = '';
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      if (qr.isDark(y, x)) d += `M${x + margin} ${y + margin}h1v1h-1z`;
    }
  }
  const all = n + margin * 2;
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('width', String(size));
  svg.setAttribute('height', String(size));
  svg.setAttribute('viewBox', `0 0 ${all} ${all}`);
  svg.setAttribute('shape-rendering', 'crispEdges');
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', t('device.qr'));
  svg.classList.add('qr');
  const bg = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
  bg.setAttribute('width', String(all));
  bg.setAttribute('height', String(all));
  bg.setAttribute('fill', '#fff');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', d);
  path.setAttribute('fill', '#000');
  svg.append(bg, path);
  return svg;
}

/** True when this screen has something to take along: a server game (not the offline one) and a seat in it. */
export function canMove(seat: Seat | null | undefined): seat is Seat {
  return !!seat && !isLocal() && !!(seat.token || seat.hostToken);
}

/** The card with the room code, this screen's return code and the QR code. Closes with the button or Escape. */
export async function showAnotherDevice(seat: Seat): Promise<void> {
  let body: (Node | null)[];
  try {
    const codes = await api.resumeCodes(seat);
    const code = codes.player ?? codes.host;
    if (!code) return;
    const forHost = !codes.player;
    body = [
      h('p', { class: 'small' }, t('device.how')),
      h('div', { class: 'qr-box' }, qrSvg(resumeLink(seat.code, code))),
      h('div', { class: 'row wrap device-codes' },
        h('div', { class: 'col' }, h('span', { class: 'small' }, t('device.room')), h('b', { class: 'resume-code' }, seat.code)),
        h('div', { class: 'col' }, h('span', { class: 'small' }, t(forHost ? 'device.host' : 'device.yours')),
          h('b', { class: 'resume-code', 'data-code': formatCode(code) }, formatCode(code)))),
      h('p', { class: 'small' }, t(forHost ? 'device.hostSecret' : 'device.secret')),
    ];
  } catch (e) {
    body = [h('p', { class: 'bad', role: 'alert' }, t(e instanceof ApiError ? e.key : 'error.badRequest'))];
  }
  const close = h('button', { class: 'btn-ready', onclick: () => done() }, t('device.close'));
  const layer = h('div', { class: 'tut dim device', role: 'dialog', 'aria-modal': 'true', 'aria-label': t('device.title') },
    h('div', { class: 'tut-card middle col gap' },
      h('div', { class: 'title' }, t('device.title')),
      ...body,
      h('div', { class: 'tut-buttons' }, h('span'), close)));
  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape') done();
  };
  function done(): void {
    document.removeEventListener('keydown', onKey);
    layer.remove();
  }
  document.addEventListener('keydown', onKey);
  document.body.append(layer);
  close.focus();
}

/** The 📱 button for the top bar or the lobby; null in the offline version, where the game lives on one device. */
export function deviceButton(seat: Seat | null | undefined, withText = false): HTMLButtonElement | null {
  if (!canMove(seat)) return null;
  return h('button', {
    class: 'btn small device-btn', title: t('device.button'), 'aria-label': t('device.button'),
    onclick: () => void showAnotherDevice(seat),
  }, withText ? `📱 ${t('device.button')}` : '📱');
}
