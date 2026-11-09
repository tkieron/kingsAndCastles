import { h } from '../dom';
import { t } from '../i18n';

/**
 * A short guided tour of the game screen: each step lights up one part of the screen and says what the child
 * can do there. It opens by itself the first time, can be skipped at any moment, and comes back with the "?" button.
 */

interface Step {
  /** What to light up; no target = a card in the middle of the screen. */
  target?: string;
  key: string;
}

const STEPS: Step[] = [
  { key: 'tut.welcome' },
  { target: '.map-scroll', key: 'tut.map' },
  { target: '.map-scroll', key: 'tut.army' },
  { target: '.stats', key: 'tut.stats' },
  { target: '.tabs', key: 'tut.tabs' },
  { target: '.chips', key: 'tut.kingdoms' },
  { target: '.phases', key: 'tut.phases' },
  { target: '.ready-box', key: 'tut.ready' },
  { key: 'tut.end' },
];

const SEEN_KEY = 'kc.tutorialSeen';

export function tutorialSeen(): boolean {
  try {
    return localStorage.getItem(SEEN_KEY) === '1';
  } catch {
    return false;
  }
}

function markSeen(): void {
  try {
    localStorage.setItem(SEEN_KEY, '1');
  } catch {
    /* private mode: the tour simply shows again next time */
  }
}

export class Tutorial {
  private readonly root = h('div', { class: 'tut', role: 'dialog', 'aria-modal': 'true', 'aria-label': t('tut.title') });
  private readonly hole = h('div', { class: 'tut-hole' });
  private readonly card = h('div', { class: 'tut-card' });
  private step = 0;
  private readonly onResize = () => this.place();
  private readonly onKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape') this.close();
    if (e.key === 'ArrowRight') this.go(1);
    if (e.key === 'ArrowLeft') this.go(-1);
  };

  /** @param params filled into the texts: kingdom, peace (last turn of the opening peace), castles, gold */
  constructor(private readonly params: Record<string, unknown>, private readonly onClose: () => void = () => undefined) {
    this.root.append(this.hole, this.card);
    document.body.append(this.root);
    window.addEventListener('resize', this.onResize);
    window.addEventListener('keydown', this.onKey);
    this.render();
  }

  private go(delta: number): void {
    const next = this.step + delta;
    if (next < 0) return;
    if (next >= STEPS.length) {
      this.close();
      return;
    }
    this.step = next;
    this.render();
  }

  close(): void {
    markSeen();
    window.removeEventListener('resize', this.onResize);
    window.removeEventListener('keydown', this.onKey);
    this.root.remove();
    this.onClose();
  }

  private render(): void {
    const s = STEPS[this.step];
    const last = this.step === STEPS.length - 1;
    const next = h('button', { class: 'btn-ready', onclick: () => this.go(1) },
      t(last ? 'tut.play' : this.step === 0 ? 'tut.start' : 'tut.next'));
    this.card.replaceChildren(
      h('div', { class: 'tut-head' },
        h('span', { class: 'tut-count' }, t('tut.count', { n: this.step + 1, all: STEPS.length })),
        last ? null : h('button', { class: 'btn small', onclick: () => this.close() }, t('tut.skip'))),
      h('p', { class: 'tut-text' }, t(s.key, this.params)),
      h('div', { class: 'tut-buttons' },
        this.step > 0 ? h('button', { class: 'btn', onclick: () => this.go(-1) }, t('tut.back')) : h('span'),
        next));
    this.place();
    next.focus();
  }

  /** Puts the light on the target and the card next to it (above or below, whichever has more room). */
  private place(): void {
    const sel = STEPS[this.step].target;
    const el = sel ? document.querySelector<HTMLElement>(sel) : null;
    const r = el?.getBoundingClientRect();
    const visible = !!r && r.width > 0 && r.height > 0;
    this.root.classList.toggle('dim', !visible);
    if (!visible) {
      this.hole.hidden = true;
      this.card.className = 'tut-card middle';
      return;
    }
    if (el && (r.top < 0 || r.bottom > window.innerHeight)) el.scrollIntoView({ block: 'nearest' });
    const b = el!.getBoundingClientRect();
    const pad = 6;
    const top = Math.max(0, b.top - pad);
    const left = Math.max(0, b.left - pad);
    const bottom = Math.min(window.innerHeight, b.bottom + pad);
    const right = Math.min(window.innerWidth, b.right + pad);
    this.hole.hidden = false;
    Object.assign(this.hole.style, { top: `${top}px`, left: `${left}px`, width: `${right - left}px`, height: `${bottom - top}px` });
    const roomAbove = top;
    const roomBelow = window.innerHeight - bottom;
    // a big target (the map) leaves no room around it: the card then sits on top of it, at the bottom
    this.card.className = `tut-card ${roomBelow >= 200 ? 'below' : roomAbove >= 200 ? 'above' : 'inside'}`;
    this.card.style.top = roomBelow >= 200 ? `${bottom + 12}px` : '';
    this.card.style.bottom = roomBelow < 200 && roomAbove >= 200 ? `${window.innerHeight - top + 12}px` : '';
    const cardWidth = Math.min(420, window.innerWidth - 32);
    const centre = (left + right) / 2;
    this.card.style.left = `${Math.max(16, Math.min(window.innerWidth - cardWidth - 16, centre - cardWidth / 2))}px`;
  }
}
