// Draws the app icon (the pixel castle from the logo) and writes PNG files, with no image libraries.
import { writeFileSync, mkdirSync } from 'node:fs';
import { deflateSync } from 'node:zlib';

const GRID = [
  '................',
  '.......#........',
  '.......#........',
  '..#....#....#...',
  '..#..#.#.#..#...',
  '..##########....',
  '..##########....',
  '..#####r####....',
  '..#####r####....',
  '..##########....',
  '..oooooooooo....',
  '................',
].map((r) => r.padEnd(16, '.'));
const COLORS = { '#': [0xf2, 0xb1, 0x34], o: [0xb5, 0x7d, 0x14], r: [0xe0, 0x41, 0x4b] };
const BG = [0x14, 0x12, 0x2a];

function crcTable() {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
}
const TABLE = crcTable();
const crc = (buf) => {
  let c = 0xffffffff;
  for (const b of buf) c = TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
};
function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type), data]);
  const c = Buffer.alloc(4);
  c.writeUInt32BE(crc(body));
  return Buffer.concat([len, body, c]);
}

function png(size) {
  // the castle is 10 columns wide and 9 rows high inside the 16×12 grid: centre it on a square
  const cell = Math.floor(size / 14);
  const offX = Math.floor((size - 10 * cell) / 2) - 2 * cell;
  const offY = Math.floor((size - 9 * cell) / 2) - 1 * cell;
  const raw = Buffer.alloc((size * 3 + 1) * size);
  for (let y = 0; y < size; y++) {
    raw[y * (size * 3 + 1)] = 0;
    for (let x = 0; x < size; x++) {
      const gx = Math.floor((x - offX) / cell);
      const gy = Math.floor((y - offY) / cell);
      const ch = GRID[gy]?.[gx] ?? '.';
      const rgb = COLORS[ch] ?? BG;
      const i = y * (size * 3 + 1) + 1 + x * 3;
      raw[i] = rgb[0];
      raw[i + 1] = rgb[1];
      raw[i + 2] = rgb[2];
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;
  ihdr[9] = 2;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

mkdirSync(new URL('../public/', import.meta.url), { recursive: true });
for (const size of [192, 512]) writeFileSync(new URL(`../public/icon-${size}.png`, import.meta.url), png(size));
console.log('icons written');
