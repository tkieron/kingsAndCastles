// Grosik, the royal treasurer: a living gold coin with a face, little arms and boots. He greets the new ruler,
// leads them through the game and pipes up with advice when clicked. Drawn in code on a 16×18 pixel grid.

export type GrosikMood = 'happy' | 'talk' | 'wink' | 'think' | 'wow';

const GOLD = '#f2b134';
const LIGHT = '#ffd96b';
const SHINE = '#fff3c4';
const RIM = '#b57d14';
const INK = '#2b1d0e';
const WHITE = '#ffffff';
const CHEEK = '#f08a5d';
const BOOT = '#6b3e1f';

type Cell = [number, number, number, number, string, string?];

function body(mood: GrosikMood): Cell[] {
  const c: Cell[] = [];
  const px = (x: number, y: number, w: number, h: number, col: string, cls?: string) => c.push([x, y, w, h, col, cls]);
  // a round coin: dark rim, gold face, a lighter inner ring and a shine
  px(5, 0, 6, 1, RIM);
  px(3, 1, 10, 1, RIM);
  px(2, 2, 12, 1, RIM);
  px(1, 3, 14, 8, RIM);
  px(2, 11, 12, 1, RIM);
  px(3, 12, 10, 1, RIM);
  px(5, 13, 6, 1, RIM);
  px(5, 1, 6, 1, GOLD);
  px(3, 2, 10, 1, GOLD);
  px(2, 3, 12, 8, GOLD);
  px(3, 11, 10, 1, GOLD);
  px(5, 12, 6, 1, GOLD);
  px(4, 2, 2, 1, LIGHT);
  px(3, 3, 1, 2, SHINE);
  px(4, 3, 1, 1, SHINE);
  // arms: one waves (or scratches the head when thinking), one rests on the side
  if (mood === 'think') {
    px(0, 8, 1, 2, INK);
    px(14, 4, 1, 1, INK);
    px(15, 3, 1, 3, INK);
  } else if (mood === 'happy' || mood === 'wow') {
    px(15, 5, 1, 4, INK);
    px(14, 4, 1, 1, INK);
    px(0, 5, 1, 4, INK);
    px(1, 4, 1, 1, INK);
  } else {
    px(15, 3, 1, 5, INK, 'grosik-wave');
    px(0, 8, 1, 3, INK);
  }
  // legs and boots
  px(6, 14, 1, 2, INK);
  px(9, 14, 1, 2, INK);
  px(4, 16, 3, 2, BOOT);
  px(9, 16, 3, 2, BOOT);

  // eyebrows
  if (mood === 'think') {
    px(4, 4, 2, 1, INK);
    px(10, 3, 2, 1, INK);
  } else if (mood === 'wow') {
    px(4, 3, 2, 1, INK);
    px(10, 3, 2, 1, INK);
  } else {
    px(4, 4, 2, 1, RIM);
    px(10, 4, 2, 1, RIM);
  }
  // eyes: white with a dark pupil; a wink closes the right one
  px(4, 5, 2, 2, WHITE);
  px(5, 5, 1, 2, INK);
  if (mood === 'wink') {
    px(10, 6, 2, 1, INK);
  } else {
    px(10, 5, 2, 2, WHITE);
    px(10, 5, 1, 2, INK);
  }
  // eyelids that blink now and then (animated in CSS)
  px(4, 5, 2, 2, GOLD, 'grosik-lid');
  if (mood !== 'wink') px(10, 5, 2, 2, GOLD, 'grosik-lid');
  // cheeks
  px(3, 8, 1, 1, CHEEK);
  px(12, 8, 1, 1, CHEEK);
  // mouth
  if (mood === 'happy' || mood === 'wink') {
    px(5, 8, 1, 1, INK);
    px(10, 8, 1, 1, INK);
    px(6, 9, 4, 1, INK);
    px(7, 9, 2, 1, '#c0392b');
  } else if (mood === 'talk') {
    px(7, 8, 2, 1, INK);
    px(6, 9, 4, 1, INK, 'grosik-jaw');
    px(7, 9, 2, 1, '#c0392b', 'grosik-jaw');
  } else if (mood === 'wow') {
    px(7, 8, 2, 2, INK);
  } else {
    px(7, 9, 3, 1, INK);
  }
  // a tiny "G" stamped on the coin, like a mint mark
  px(7, 11, 2, 1, RIM);
  return c;
}

/** Grosik as a crisp pixel SVG. `size` is the width; he is a little taller than wide. */
export function grosikSvg(mood: GrosikMood = 'happy', size = 48): SVGSVGElement {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('width', String(size));
  svg.setAttribute('height', String(Math.round(size * 18 / 16)));
  svg.setAttribute('viewBox', '0 0 16 18');
  svg.setAttribute('shape-rendering', 'crispEdges');
  svg.setAttribute('aria-hidden', 'true');
  svg.classList.add('grosik-svg', `grosik-${mood}`);
  svg.innerHTML = body(mood).map(([x, y, w, h, col, cls]) =>
    `<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="${col}"${cls ? ` class="${cls}"` : ''}/>`).join('');
  return svg;
}
