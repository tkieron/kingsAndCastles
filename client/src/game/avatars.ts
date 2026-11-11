// Pixel portraits a child can pick on the start screen: ten young kings and ten young queens
// (about 20 to 40 years old). Drawn in code on a 16×16 grid, like the rest of the game's pixel art.

/** 10 kings (0-9) and 10 queens (10-19). */
export const AVATAR_COUNT = 20;
export const AVATARS_PER_KIND = 10;

export type Who = 'king' | 'queen';

/** The pictures for a boy (kings) or a girl (queens). */
export function avatarsOf(who: Who): number[] {
  const first = who === 'king' ? 0 : AVATARS_PER_KIND;
  return Array.from({ length: AVATARS_PER_KIND }, (_, i) => first + i);
}

type Hair = 'short' | 'long' | 'bun' | 'braids' | 'curly' | 'bob' | 'shoulder';
type Beard = 'none' | 'moustache' | 'short' | 'full';
type Hat = 'crown' | 'tiara' | 'circlet';

interface Look {
  /** 'king' or 'queen': used for the greeting ("królu" / "królowo") */
  who: 'king' | 'queen';
  skin: string;
  hair: string;
  style: Hair;
  beard: Beard;
  hat: Hat;
  cloth: string;
  bg: string;
}

const SKIN = { fair: '#f6d3b3', light: '#eabf96', tan: '#c98e5f', brown: '#9a6440', dark: '#6e4429' };

const LOOKS: Look[] = [
  // kings and princes (0-9)
  { who: 'king', skin: SKIN.fair, hair: '#6b3e1f', style: 'short', beard: 'none', hat: 'crown', cloth: '#c0392b', bg: '#f3c9a0' },
  { who: 'king', skin: SKIN.brown, hair: '#1d1716', style: 'short', beard: 'short', hat: 'crown', cloth: '#2f8a4a', bg: '#c6eac0' },
  { who: 'king', skin: SKIN.light, hair: '#e7c35a', style: 'short', beard: 'moustache', hat: 'crown', cloth: '#1f5fa8', bg: '#c4e2f7' },
  { who: 'king', skin: SKIN.fair, hair: '#b5481c', style: 'short', beard: 'full', hat: 'crown', cloth: '#6a2d8f', bg: '#d9c6ec' },
  { who: 'king', skin: SKIN.tan, hair: '#3b2414', style: 'shoulder', beard: 'none', hat: 'circlet', cloth: '#a3203a', bg: '#f6c3cc' },
  { who: 'king', skin: SKIN.dark, hair: '#1d1716', style: 'curly', beard: 'none', hat: 'circlet', cloth: '#d97a12', bg: '#ffe0a8' },
  { who: 'king', skin: SKIN.light, hair: '#1d1716', style: 'short', beard: 'short', hat: 'circlet', cloth: '#17867a', bg: '#bdebe4' },
  { who: 'king', skin: SKIN.fair, hair: '#f2cf5b', style: 'shoulder', beard: 'none', hat: 'crown', cloth: '#23397a', bg: '#c9d2f5' },
  { who: 'king', skin: SKIN.tan, hair: '#6b3e1f', style: 'short', beard: 'full', hat: 'circlet', cloth: '#3f6b1e', bg: '#d4efc0' },
  { who: 'king', skin: SKIN.brown, hair: '#2a1a12', style: 'short', beard: 'moustache', hat: 'crown', cloth: '#7a1f2b', bg: '#f3d0c0' },
  // queens and princesses (10-19)
  { who: 'queen', skin: SKIN.fair, hair: '#f2cf5b', style: 'long', beard: 'none', hat: 'tiara', cloth: '#2f6fd0', bg: '#bfd8ff' },
  { who: 'queen', skin: SKIN.light, hair: '#c4521f', style: 'braids', beard: 'none', hat: 'circlet', cloth: '#7d3fb0', bg: '#e2c9f5' },
  { who: 'queen', skin: SKIN.tan, hair: '#1d1716', style: 'bun', beard: 'none', hat: 'tiara', cloth: '#d97a12', bg: '#ffe0a8' },
  { who: 'queen', skin: SKIN.dark, hair: '#2a1a12', style: 'curly', beard: 'none', hat: 'crown', cloth: '#17867a', bg: '#bdebe4' },
  { who: 'queen', skin: SKIN.fair, hair: '#ece6d8', style: 'bob', beard: 'none', hat: 'crown', cloth: '#3f8d2e', bg: '#d4efc0' },
  { who: 'queen', skin: SKIN.brown, hair: '#1d1716', style: 'long', beard: 'none', hat: 'crown', cloth: '#c0392b', bg: '#f3c9a0' },
  { who: 'queen', skin: SKIN.light, hair: '#6b3e1f', style: 'braids', beard: 'none', hat: 'tiara', cloth: '#d4547e', bg: '#f8cfe0' },
  { who: 'queen', skin: SKIN.fair, hair: '#b5481c', style: 'long', beard: 'none', hat: 'circlet', cloth: '#2f8a4a', bg: '#c6eac0' },
  { who: 'queen', skin: SKIN.tan, hair: '#3b2414', style: 'bob', beard: 'none', hat: 'tiara', cloth: '#6a2d8f', bg: '#d9c6ec' },
  { who: 'queen', skin: SKIN.dark, hair: '#1d1716', style: 'bun', beard: 'none', hat: 'crown', cloth: '#b57d14', bg: '#fff0b8' },
];

const INK = '#2b1d0e';
const GOLD = '#f2b134';
const GOLD_DARK = '#b57d14';
const GEM = '#e8364a';

export function avatarWho(i: number | undefined): Who {
  return LOOKS[clamp(i)].who;
}

function clamp(i: number | undefined): number {
  return i === undefined || !Number.isInteger(i) || i < 0 || i >= AVATAR_COUNT ? 0 : i;
}

/** The rectangles of one portrait, as [x, y, w, h, colour]. */
function cells(look: Look): [number, number, number, number, string][] {
  const r: [number, number, number, number, string][] = [];
  const px = (x: number, y: number, w: number, h: number, c: string) => r.push([x, y, w, h, c]);
  const { skin, hair, style, beard, hat, cloth } = look;

  px(0, 0, 16, 16, look.bg);
  // long hair falls behind the shoulders
  if (style === 'long') px(3, 5, 10, 9, hair);
  if (style === 'curly') {
    px(3, 4, 10, 9, hair);
    px(2, 6, 1, 6, hair);
    px(13, 6, 1, 6, hair);
  }
  if (style === 'shoulder') px(4, 5, 8, 7, hair);
  if (style === 'braids') {
    px(3, 7, 2, 7, hair);
    px(11, 7, 2, 7, hair);
    px(3, 10, 2, 1, GOLD);
    px(11, 10, 2, 1, GOLD);
  }
  if (style === 'bob') px(4, 5, 8, 6, hair);

  // clothes, cape and collar
  px(2, 13, 12, 3, cloth);
  px(1, 14, 1, 2, cloth);
  px(14, 14, 1, 2, cloth);
  px(6, 13, 4, 1, GOLD);
  px(7, 14, 2, 1, GOLD_DARK);
  // neck and face
  px(7, 11, 2, 2, skin);
  px(5, 5, 6, 7, skin);
  px(4, 7, 1, 2, skin); // ears
  px(11, 7, 1, 2, skin);

  // hair on top
  if (style === 'bun') {
    px(6, 1, 4, 2, hair);
    px(5, 3, 6, 2, hair);
    px(4, 4, 1, 3, hair);
    px(11, 4, 1, 3, hair);
  } else {
    px(5, 3, 6, 2, hair);
    px(4, 4, 1, style === 'short' ? 3 : 4, hair);
    px(11, 4, 1, style === 'short' ? 3 : 4, hair);
  }
  // fringe: a side parting for kings, a soft fringe for queens
  if (look.who === 'king') px(5, 5, 3, 1, hair);
  else px(5, 5, 2, 1, hair), px(9, 5, 2, 1, hair);

  // eyes, brows, mouth, cheeks
  px(6, 8, 1, 1, INK);
  px(9, 8, 1, 1, INK);
  px(6, 7, 1, 1, look.who === 'king' ? hair : skin);
  px(9, 7, 1, 1, look.who === 'king' ? hair : skin);
  if (look.who === 'queen') {
    px(5, 9, 1, 1, '#f08a8a');
    px(10, 9, 1, 1, '#f08a8a');
    px(7, 10, 2, 1, '#c0392b');
  } else {
    px(7, 10, 2, 1, '#8a3a2a');
  }

  // beards for the older kings
  if (beard === 'moustache') px(6, 9, 4, 1, hair), px(6, 10, 1, 1, hair), px(9, 10, 1, 1, hair);
  if (beard === 'short') px(5, 10, 1, 2, hair), px(10, 10, 1, 2, hair), px(6, 11, 4, 1, hair);
  if (beard === 'full') {
    px(5, 9, 1, 3, hair);
    px(10, 9, 1, 3, hair);
    px(6, 9, 4, 1, hair);
    px(6, 11, 4, 2, hair);
    px(7, 10, 2, 1, '#8a3a2a');
  }

  // what they wear on the head
  if (hat === 'crown') {
    px(4, 2, 8, 2, GOLD);
    px(4, 1, 1, 1, GOLD);
    px(7, 0, 2, 2, GOLD);
    px(11, 1, 1, 1, GOLD);
    px(4, 3, 8, 1, GOLD_DARK);
    px(7, 2, 2, 1, GEM);
  } else if (hat === 'tiara') {
    px(5, 3, 6, 1, GOLD);
    px(7, 2, 2, 1, GOLD);
    px(7, 1, 2, 1, '#7fd6ff');
  } else {
    px(4, 4, 8, 1, GOLD);
    px(7, 4, 2, 1, '#2fae6a');
  }
  return r;
}

/** One portrait as a crisp pixel SVG. */
export function avatarSvg(i: number | undefined, size = 48): SVGSVGElement {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('width', String(size));
  svg.setAttribute('height', String(size));
  svg.setAttribute('viewBox', '0 0 16 16');
  svg.setAttribute('shape-rendering', 'crispEdges');
  svg.setAttribute('aria-hidden', 'true');
  svg.classList.add('avatar');
  svg.innerHTML = cells(LOOKS[clamp(i)])
    .map(([x, y, w, h, c]) => `<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="${c}"/>`).join('');
  return svg;
}
