// Pixel art for the map, drawn on an 11×11 grid per field (the same grid as the approved mockups).
// Everything is drawn in code, so the game has no image files to load.

export const PALETTE = {
  ink: '#0a0916',
  meadow: '#6abe30',
  forest: '#4f9a3a',
  hills: '#8fae45',
  mountain: '#8a8f7a',
  lake: '#3a6fc4',
  river: '#4f8fe0',
  tree: '#1f6b3f',
  trunk: '#5a3a22',
  hill1: '#c9a24a',
  hill2: '#b38b38',
  rock: '#5f5a6b',
  snow: '#f4f4f4',
  wave: '#a9d1ff',
  stone: '#e6dfcf',
  door: '#3a2a1a',
  pole: '#2b2b2b',
  wall: '#f1dfae',
  gold: '#f2b134',
  goldDark: '#b57d14',
  plank: '#8a5a2b',
};

export const TERRAIN_BG: Record<string, string> = {
  M: PALETTE.meadow,
  F: PALETTE.forest,
  H: PALETTE.hills,
  A: PALETTE.mountain,
  L: PALETTE.lake,
  R: PALETTE.river,
};

type Ctx = CanvasRenderingContext2D;

/** Draws in grid units: px(ctx, x, y, w, h) fills cells of the 11×11 field. */
function grid(ctx: Ctx, ox: number, oy: number, size: number) {
  const u = size / 11;
  return (x: number, y: number, w: number, h: number, color: string) => {
    ctx.fillStyle = color;
    ctx.fillRect(Math.round(ox + x * u), Math.round(oy + y * u), Math.ceil(w * u), Math.ceil(h * u));
  };
}

function poly(ctx: Ctx, ox: number, oy: number, size: number, color: string, pts: number[]) {
  const u = size / 11;
  ctx.fillStyle = color;
  ctx.beginPath();
  for (let i = 0; i < pts.length; i += 2) {
    const x = ox + pts[i] * u;
    const y = oy + pts[i + 1] * u;
    if (i === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  }
  ctx.closePath();
  ctx.fill();
}

export function drawTerrain(ctx: Ctx, code: string, x: number, y: number, s: number, variant: number): void {
  ctx.fillStyle = TERRAIN_BG[code] ?? PALETTE.meadow;
  ctx.fillRect(x, y, s, s);
  const px = grid(ctx, x, y, s);
  switch (code) {
    case 'M':
      // a few grass tufts so the meadow does not look flat
      if (variant % 3 === 0) {
        px(2, 3, 1, 1, '#5aa828');
        px(7, 7, 1, 1, '#5aa828');
      } else if (variant % 3 === 1) {
        px(5, 2, 1, 1, '#7fd040');
        px(3, 8, 1, 1, '#5aa828');
      }
      break;
    case 'F':
      poly(ctx, x, y, s, PALETTE.tree, [3, 1, 5.5, 6, 0.5, 6]);
      px(2.5, 6, 1, 2, PALETTE.trunk);
      poly(ctx, x, y, s, PALETTE.tree, [8, 3, 10.5, 8, 5.5, 8]);
      px(7.5, 8, 1, 2, PALETTE.trunk);
      break;
    case 'H': {
      const u = s / 11;
      ctx.fillStyle = PALETTE.hill1;
      ctx.beginPath();
      ctx.moveTo(x + 0.5 * u, y + 8 * u);
      ctx.quadraticCurveTo(x + 3 * u, y + 3.5 * u, x + 5.5 * u, y + 8 * u);
      ctx.fill();
      ctx.fillStyle = PALETTE.hill2;
      ctx.beginPath();
      ctx.moveTo(x + 5 * u, y + 9.5 * u);
      ctx.quadraticCurveTo(x + 7.5 * u, y + 5 * u, x + 10 * u, y + 9.5 * u);
      ctx.fill();
      break;
    }
    case 'A':
      poly(ctx, x, y, s, PALETTE.rock, [5.5, 1, 10.5, 10, 0.5, 10]);
      poly(ctx, x, y, s, PALETTE.snow, [5.5, 1, 7.2, 4, 3.8, 4]);
      break;
    case 'R':
    case 'L': {
      const shift = variant % 2;
      px(1 + shift, 3.5, 2, 0.6, PALETTE.wave);
      px(3 + shift, 4.1, 2, 0.6, PALETTE.wave);
      px(5 + shift, 3.5, 2, 0.6, PALETTE.wave);
      px(2 - shift, 7.5, 2, 0.6, PALETTE.wave);
      px(4 - shift, 8.1, 2, 0.6, PALETTE.wave);
      px(6 - shift, 7.5, 2, 0.6, PALETTE.wave);
      break;
    }
  }
}

export function drawCastle(ctx: Ctx, x: number, y: number, s: number, flag: string): void {
  const px = grid(ctx, x, y, s);
  px(1, 4, 9, 6, PALETTE.stone);
  px(1, 2, 2, 2, PALETTE.stone);
  px(8, 2, 2, 2, PALETTE.stone);
  px(4, 3, 1, 1, PALETTE.stone);
  px(6, 3, 1, 1, PALETTE.stone);
  px(4.5, 7, 2, 3, PALETTE.door);
  px(5.3, 0, 0.4, 4, PALETTE.pole);
  px(5.7, 0, 2.5, 1.6, flag);
  px(1, 10, 9, 0.6, PALETTE.ink);
}

export function drawVillage(ctx: Ctx, x: number, y: number, s: number, roof: string): void {
  const px = grid(ctx, x, y, s);
  px(1, 5.5, 4, 3.5, PALETTE.wall);
  poly(ctx, x, y, s, roof, [0.5, 5.5, 3, 3, 5.5, 5.5]);
  px(2.4, 7, 1.2, 2, PALETTE.trunk);
  px(6, 6.5, 4, 3, PALETTE.wall);
  poly(ctx, x, y, s, roof, [5.5, 6.5, 8, 4, 10.5, 6.5]);
}

export function drawWalls(ctx: Ctx, x: number, y: number, s: number): void {
  const px = grid(ctx, x, y, s);
  px(0, 9.6, 11, 1.4, '#9a917e');
  for (let i = 0; i < 11; i += 2) px(i, 9, 1, 0.6, '#9a917e');
}

export function drawMine(ctx: Ctx, x: number, y: number, s: number): void {
  const px = grid(ctx, x, y, s);
  px(3, 4, 5, 4, PALETTE.trunk);
  px(4, 5, 3, 3, PALETTE.ink);
  px(4.5, 6.5, 2, 1, PALETTE.gold);
  px(2.5, 3.5, 6, 0.8, PALETTE.plank);
}

export function drawBridge(ctx: Ctx, x: number, y: number, s: number): void {
  const px = grid(ctx, x, y, s);
  px(0, 4, 11, 3, PALETTE.plank);
  for (let i = 0.5; i < 11; i += 2) px(i, 4, 0.4, 3, PALETTE.trunk);
  px(0, 3.4, 11, 0.6, PALETTE.trunk);
  px(0, 7, 11, 0.6, PALETTE.trunk);
}

export function drawRuins(ctx: Ctx, x: number, y: number, s: number): void {
  const px = grid(ctx, x, y, s);
  px(2, 5, 1.5, 4, '#a39d8c');
  px(2, 4, 1.5, 1, '#a39d8c');
  px(5, 6.5, 1.5, 2.5, '#a39d8c');
  px(7.5, 7.5, 2, 1.5, '#a39d8c');
  px(4, 9, 6, 0.6, '#6f6a5c');
  px(8, 5, 1, 1, PALETTE.gold);
}

const LETTER: Record<string, string> = { SPEARMAN: 'S', ARCHER: 'A', KNIGHT: 'K', RAM: 'R', SETTLER: 'V' };

/** An army: a square in the kingdom's colour with the first letter of the unit. */
export function drawUnit(ctx: Ctx, x: number, y: number, s: number, type: string, color: string, selected: boolean, letters: Record<string, string> = LETTER): void {
  const inset = Math.round(s * 8 / 44);
  const size = s - 2 * inset;
  const border = Math.max(2, Math.round(s * 3 / 44));
  ctx.fillStyle = selected ? '#ffffff' : PALETTE.ink;
  ctx.fillRect(x + inset, y + inset, size, size);
  ctx.fillStyle = color;
  ctx.fillRect(x + inset + border, y + inset + border, size - 2 * border, size - 2 * border);
  ctx.fillStyle = '#ffffff';
  ctx.font = `700 ${Math.round(s * 15 / 44)}px 'Pixelify Sans', monospace`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(letters[type] ?? '?', x + s / 2, y + s / 2 + 1);
}

/** Readable text colour on a kingdom colour (the yellow stag needs dark letters). */
export function inkOn(color: string): string {
  const n = parseInt(color.slice(1), 16);
  const r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
  return 0.299 * r + 0.587 * g + 0.114 * b > 160 ? '#2b1d0e' : '#ffffff';
}

export function tint(color: string, alpha: number): string {
  const n = parseInt(color.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${alpha})`;
}
