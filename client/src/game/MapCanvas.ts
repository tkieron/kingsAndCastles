import type { GameState, Pos } from '../types';
import { samePos, terrainAt, territory } from './rules';
import { drawBridge, drawCastle, drawMine, drawRuins, drawTerrain, drawUnit, drawVillage, drawWalls, PALETTE, tint } from './sprites';

/** Extra things to draw on top of the game: the child's selection and plans. */
export interface Overlay {
  selected?: Pos;
  reach?: Set<string>;
  targets?: Set<string>;
  moves: { from: Pos; path: Pos[] }[];
  attacks: { from: Pos; to: Pos }[];
  building: Pos[];
  letters: Record<string, string>;
}

/** The map: a crisp pixel canvas that scrolls inside its box. Field size can be changed (zoom). */
export class MapCanvas {
  readonly canvas: HTMLCanvasElement;
  private size = 33;
  private state?: GameState;
  private overlay: Overlay = { moves: [], attacks: [], building: [], letters: {} };

  constructor(private readonly onClick: (p: Pos) => void) {
    this.canvas = document.createElement('canvas');
    this.canvas.className = 'map-canvas';
    this.canvas.setAttribute('role', 'img');
    this.canvas.addEventListener('click', (e) => {
      const r = this.canvas.getBoundingClientRect();
      const scale = this.canvas.width / r.width;
      const x = Math.floor(((e.clientX - r.left) * scale) / this.pixel());
      const y = Math.floor(((e.clientY - r.top) * scale) / this.pixel());
      if (this.state && x >= 0 && y >= 0 && x < this.state.map.width && y < this.state.map.height) this.onClick({ x, y });
    });
  }

  /** Field size in CSS pixels; a multiple of 11 keeps the pixel art sharp. */
  setFieldSize(size: number): void {
    this.size = Math.max(22, Math.min(55, Math.round(size / 11) * 11));
    this.draw();
  }

  fieldSize(): number {
    return this.size;
  }

  private pixel(): number {
    return this.size * (window.devicePixelRatio || 1);
  }

  update(state: GameState, overlay: Overlay): void {
    this.state = state;
    this.overlay = overlay;
    this.draw();
  }

  draw(): void {
    const s = this.state;
    if (!s) return;
    const dpr = window.devicePixelRatio || 1;
    const f = this.size * dpr;
    const w = s.map.width * f;
    const hgt = s.map.height * f;
    if (this.canvas.width !== w || this.canvas.height !== hgt) {
      this.canvas.width = w;
      this.canvas.height = hgt;
    }
    this.canvas.style.width = `${s.map.width * this.size}px`;
    this.canvas.style.height = `${s.map.height * this.size}px`;
    const ctx = this.canvas.getContext('2d');
    if (!ctx) return;
    ctx.imageSmoothingEnabled = false;
    const color = new Map(s.kingdoms.map((k) => [k.id, k.color]));
    const owner = territory(s);

    for (let y = 0; y < s.map.height; y++) {
      for (let x = 0; x < s.map.width; x++) {
        drawTerrain(ctx, terrainAt(s.map, { x, y }), x * f, y * f, f, (x * 7 + y * 13) % 5);
      }
    }
    for (const i of s.map.improvements) {
      if (i.building === 'BRIDGE') drawBridge(ctx, i.pos.x * f, i.pos.y * f, f);
      if (i.building === 'GOLD_MINE') drawMine(ctx, i.pos.x * f, i.pos.y * f, f);
    }
    for (const r of s.map.ruins) drawRuins(ctx, r.x * f, r.y * f, f);

    // land: a light tint and a thick border in the kingdom's colour
    const edge = Math.max(2, Math.round(f * 3 / 44));
    for (let y = 0; y < s.map.height; y++) {
      for (let x = 0; x < s.map.width; x++) {
        const o = owner[y][x];
        if (!o) continue;
        const c = color.get(o) ?? '#ffffff';
        ctx.fillStyle = tint(c, 0.28);
        ctx.fillRect(x * f, y * f, f, f);
        ctx.fillStyle = c;
        if (owner[y - 1]?.[x] !== o) ctx.fillRect(x * f, y * f, f, edge);
        if (owner[y + 1]?.[x] !== o) ctx.fillRect(x * f, (y + 1) * f - edge, f, edge);
        if (owner[y][x - 1] !== o) ctx.fillRect(x * f, y * f, edge, f);
        if (owner[y][x + 1] !== o) ctx.fillRect((x + 1) * f - edge, y * f, edge, f);
      }
    }

    // soft grid so children can count fields
    ctx.fillStyle = 'rgba(0,0,0,0.08)';
    for (let x = 1; x < s.map.width; x++) ctx.fillRect(x * f, 0, 1, hgt);
    for (let y = 1; y < s.map.height; y++) ctx.fillRect(0, y * f, w, 1);

    const o = this.overlay;
    ctx.lineWidth = Math.max(2, dpr * 2);
    for (const k of o.reach ?? []) {
      const [x, y] = k.split(',').map(Number);
      ctx.strokeStyle = 'rgba(255,255,255,0.95)';
      ctx.setLineDash([f / 8, f / 10]);
      const inset = f * 6 / 44;
      ctx.strokeRect(x * f + inset, y * f + inset, f - 2 * inset, f - 2 * inset);
    }
    ctx.setLineDash([]);
    for (const k of o.targets ?? []) {
      const [x, y] = k.split(',').map(Number);
      const inset = f * 3 / 44;
      ctx.fillStyle = 'rgba(242,177,52,0.35)';
      ctx.fillRect(x * f + inset, y * f + inset, f - 2 * inset, f - 2 * inset);
      ctx.strokeStyle = PALETTE.gold;
      ctx.lineWidth = Math.max(2, f * 3 / 44);
      ctx.strokeRect(x * f + inset, y * f + inset, f - 2 * inset, f - 2 * inset);
    }

    for (const st of s.settlements) {
      const c = color.get(st.owner) ?? '#ffffff';
      if (st.castle) drawCastle(ctx, st.pos.x * f, st.pos.y * f, f, c);
      else drawVillage(ctx, st.pos.x * f, st.pos.y * f, f, c);
      if (st.buildings.includes('WALLS')) drawWalls(ctx, st.pos.x * f, st.pos.y * f, f);
    }

    // planned moves: a dotted line to the new field
    const centre = (p: Pos) => [p.x * f + f / 2, p.y * f + f / 2] as const;
    ctx.lineWidth = Math.max(3, f / 10);
    ctx.lineCap = 'round';
    for (const m of o.moves) {
      ctx.strokeStyle = 'rgba(255,255,255,0.9)';
      ctx.setLineDash([f / 6, f / 8]);
      ctx.beginPath();
      ctx.moveTo(...centre(m.from));
      for (const p of m.path) ctx.lineTo(...centre(p));
      ctx.stroke();
      const end = m.path[m.path.length - 1];
      ctx.setLineDash([]);
      ctx.fillStyle = '#ffffff';
      ctx.beginPath();
      ctx.arc(...centre(end), f / 7, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.setLineDash([]);
    for (const a of o.attacks) {
      ctx.strokeStyle = '#e0414b';
      ctx.beginPath();
      ctx.moveTo(...centre(a.from));
      ctx.lineTo(...centre(a.to));
      ctx.stroke();
      const [tx, ty] = centre(a.to);
      ctx.lineWidth = Math.max(3, f / 12);
      ctx.beginPath();
      ctx.moveTo(tx - f / 5, ty - f / 5);
      ctx.lineTo(tx + f / 5, ty + f / 5);
      ctx.moveTo(tx + f / 5, ty - f / 5);
      ctx.lineTo(tx - f / 5, ty + f / 5);
      ctx.stroke();
      ctx.lineWidth = Math.max(3, f / 10);
    }

    for (const u of s.units) {
      const sel = !!o.selected && samePos(o.selected, u.pos);
      const inPlace = s.settlements.some((st) => samePos(st.pos, u.pos));
      if (inPlace) {
        // an army inside a castle or village: a smaller badge in the corner, so the building stays visible
        const small = f * 0.62;
        drawUnit(ctx, u.pos.x * f + f - small + f * 0.06, u.pos.y * f + f - small + f * 0.06, small, u.type, color.get(u.owner) ?? '#888', sel, o.letters);
      } else {
        drawUnit(ctx, u.pos.x * f, u.pos.y * f, f, u.type, color.get(u.owner) ?? '#888', sel, o.letters);
      }
    }

    // a hammer mark where something will be built this turn
    for (const p of o.building) {
      const px = p.x * f + f * 0.68;
      const py = p.y * f + f * 0.05;
      ctx.fillStyle = PALETTE.ink;
      ctx.fillRect(px - 1, py - 1, f * 0.3 + 2, f * 0.3 + 2);
      ctx.fillStyle = PALETTE.gold;
      ctx.fillRect(px, py, f * 0.3, f * 0.3);
      ctx.fillStyle = PALETTE.ink;
      ctx.font = `700 ${Math.round(f * 0.24)}px 'Pixelify Sans', monospace`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('+', px + f * 0.15, py + f * 0.16);
    }

    if (o.selected && !s.units.some((u) => samePos(u.pos, o.selected!))) {
      ctx.strokeStyle = '#ffffff';
      ctx.lineWidth = Math.max(2, f / 16);
      ctx.strokeRect(o.selected.x * f + 1, o.selected.y * f + 1, f - 2, f - 2);
    }
  }
}
