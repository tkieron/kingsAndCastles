import { t, tipFor, type Key } from './i18n';

type Child = Node | string | null | undefined | false;
type Attrs = Record<string, string | number | boolean | null | undefined | EventListener>;

/** Tiny element builder: h('button', {class: 'big', onclick: fn}, 'Ready!') */
export function h<K extends keyof HTMLElementTagNameMap>(tag: K, attrs: Attrs = {}, ...children: Child[]): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [name, value] of Object.entries(attrs)) {
    if (value === null || value === undefined || value === false) continue;
    if (name.startsWith('on') && typeof value === 'function') {
      el.addEventListener(name.slice(2), value as EventListener);
    } else if (value === true) {
      el.setAttribute(name, '');
    } else {
      el.setAttribute(name, String(value));
    }
  }
  append(el, ...children);
  return el;
}

export function append(el: Element, ...children: Child[]): void {
  for (const c of children) {
    if (c === null || c === undefined || c === false) continue;
    el.append(typeof c === 'string' ? document.createTextNode(c) : c);
  }
}

/** A glossary word with its translation tooltip (hover on a computer, long press on a phone). */
export function word(key: Key, params: Record<string, unknown> = {}): HTMLSpanElement {
  const tip = tipFor(key);
  return h('span', { class: tip ? 'word' : null, 'data-tip': tip ?? null, tabindex: tip ? 0 : null }, t(key, params));
}

// ------------------------------------------------------------------ tooltips

let bubble: HTMLDivElement | null = null;
let pressTimer: number | undefined;

function show(target: HTMLElement): void {
  const text = target.dataset.tip;
  if (!text) return;
  hide();
  bubble = h('div', { class: 'tip', role: 'tooltip' }, text);
  document.body.append(bubble);
  const r = target.getBoundingClientRect();
  const b = bubble.getBoundingClientRect();
  const left = Math.max(8, Math.min(window.innerWidth - b.width - 8, r.left + r.width / 2 - b.width / 2));
  const top = r.top - b.height - 8 >= 0 ? r.top - b.height - 8 : r.bottom + 8;
  bubble.style.left = `${left + window.scrollX}px`;
  bubble.style.top = `${top + window.scrollY}px`;
}

function hide(): void {
  bubble?.remove();
  bubble = null;
}

function tipTarget(e: Event): HTMLElement | null {
  const el = e.target instanceof Element ? e.target.closest('[data-tip]') : null;
  return el instanceof HTMLElement ? el : null;
}

/** Installs the tooltip behaviour once for the whole page. */
export function installTooltips(): void {
  document.addEventListener('mouseover', (e) => {
    const el = tipTarget(e);
    if (el) show(el);
  });
  document.addEventListener('mouseout', (e) => {
    if (tipTarget(e)) hide();
  });
  document.addEventListener('focusin', (e) => {
    const el = tipTarget(e);
    if (el) show(el);
  });
  document.addEventListener('focusout', hide);
  document.addEventListener('touchstart', (e) => {
    const el = tipTarget(e);
    window.clearTimeout(pressTimer);
    hide();
    if (el) pressTimer = window.setTimeout(() => show(el), 450);
  }, { passive: true });
  document.addEventListener('touchend', () => window.clearTimeout(pressTimer));
  document.addEventListener('touchmove', () => window.clearTimeout(pressTimer), { passive: true });
  document.addEventListener('scroll', hide, true);
  // a long press must not open the phone's own menu over a word
  document.addEventListener('contextmenu', (e) => {
    if (tipTarget(e)) e.preventDefault();
  });
}
