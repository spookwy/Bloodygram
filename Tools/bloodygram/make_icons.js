// Builds Bloodygram icon PNGs from the source art in Tools/bloodygram/art:
//   Bloodygram-trans.png -> in-app logo + adaptive-icon foreground (+ monochrome)
//   Bloodygram.png       -> legacy (pre-Android 8) launcher icon
// usage: node Tools/bloodygram/make_icons.js
const fs = require('fs'), path = require('path'), zlib = require('zlib');

const root = path.join(__dirname, '..', '..');
const res = path.join(root, 'TMessagesProj', 'src', 'main', 'res', 'drawable-nodpi');

function decode(file) {
  const buf = fs.readFileSync(file);
  let pos = 8, w, h, colorType, idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos), type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.slice(pos + 8, pos + 8 + len);
    if (type === 'IHDR') { w = data.readUInt32BE(0); h = data.readUInt32BE(4); colorType = data[9]; if (data[8] !== 8 || data[12]) throw new Error('only 8-bit non-interlaced PNG'); }
    if (type === 'IDAT') idat.push(data);
    pos += 12 + len;
  }
  const bpp = colorType === 6 ? 4 : colorType === 2 ? 3 : 0;
  if (!bpp) throw new Error('unsupported color type ' + colorType);
  const raw = zlib.inflateSync(Buffer.concat(idat)), stride = w * bpp, px = Buffer.alloc(h * stride);
  for (let y = 0; y < h; y++) {
    const f = raw[y * (stride + 1)], o = y * (stride + 1) + 1;
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? px[y * stride + x - bpp] : 0, b = y ? px[(y - 1) * stride + x] : 0, c = x >= bpp && y ? px[(y - 1) * stride + x - bpp] : 0;
      let v = raw[o + x];
      if (f === 1) v += a; else if (f === 2) v += b; else if (f === 3) v += (a + b) >> 1;
      else if (f === 4) { const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c); v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }
      px[y * stride + x] = v & 255;
    }
  }
  const rgba = new Float32Array(w * h * 4);
  for (let i = 0; i < w * h; i++) {
    rgba[i * 4] = px[i * bpp]; rgba[i * 4 + 1] = px[i * bpp + 1]; rgba[i * 4 + 2] = px[i * bpp + 2];
    rgba[i * 4 + 3] = bpp === 4 ? px[i * bpp + 3] : 255;
  }
  return { w, h, rgba };
}

function crc32(buf) {
  let c, crc = 0xFFFFFFFF;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 255;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

function encode(img, file) {
  const { w, h, rgba } = img, raw = Buffer.alloc(h * (w * 4 + 1));
  for (let y = 0; y < h; y++) for (let x = 0; x < w * 4; x++) raw[y * (w * 4 + 1) + 1 + x] = Math.max(0, Math.min(255, Math.round(rgba[y * w * 4 + x])));
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type, 'ascii'), data]), crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  fs.writeFileSync(file, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]));
  console.log('wrote', path.relative(root, file), w + 'x' + h);
}

// bounding box of pixels that are visible (alpha) and, for opaque sources, not near-black background
function bbox(img, isBg) {
  let x0 = img.w, y0 = img.h, x1 = -1, y1 = -1;
  for (let y = 0; y < img.h; y++) for (let x = 0; x < img.w; x++) {
    const i = (y * img.w + x) * 4;
    if (!isBg(img.rgba, i)) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
  }
  return { x0, y0, x1, y1 };
}

// area-average downscale of the box into a size x size canvas, content fitted into `fit` (0..1) and centered
function place(img, box, size, fit, bg) {
  const bw = box.x1 - box.x0 + 1, bh = box.y1 - box.y0 + 1, scale = Math.min(size * fit / bw, size * fit / bh);
  const dw = bw * scale, dh = bh * scale, ox = (size - dw) / 2, oy = (size - dh) / 2;
  const out = new Float32Array(size * size * 4);
  for (let i = 0; i < size * size; i++) out.set(bg, i * 4);
  const ss = 4; // supersamples per axis
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    let r = 0, g = 0, b = 0, a = 0;
    for (let sy = 0; sy < ss; sy++) for (let sx = 0; sx < ss; sx++) {
      const fx = (x + (sx + 0.5) / ss - ox) / scale + box.x0, fy = (y + (sy + 0.5) / ss - oy) / scale + box.y0;
      const ix = Math.floor(fx), iy = Math.floor(fy);
      if (ix < box.x0 || iy < box.y0 || ix > box.x1 || iy > box.y1) continue;
      const i = (iy * img.w + ix) * 4, pa = img.rgba[i + 3] / 255;
      r += img.rgba[i] * pa; g += img.rgba[i + 1] * pa; b += img.rgba[i + 2] * pa; a += pa;
    }
    const n = ss * ss, o = (y * size + x) * 4;
    if (a <= 0) continue;
    const srcA = a / n, dstA = out[o + 3] / 255, outA = srcA + dstA * (1 - srcA);
    for (let c = 0; c < 3; c++) {
      const src = [r, g, b][c] / a;
      out[o + c] = (src * srcA + out[o + c] * dstA * (1 - srcA)) / outA;
    }
    out[o + 3] = outA * 255;
  }
  return { w: size, h: size, rgba: out };
}

const trans = decode(path.join(__dirname, 'art', 'Bloodygram-trans.png'));
const transBox = bbox(trans, (p, i) => p[i + 3] < 8);
// in-app logo: tight, a little breathing room
encode(place(trans, transBox, 512, 0.96, [0, 0, 0, 0]), path.join(res, 'bloody_logo.png'));
// adaptive icon foreground: 108dp layer, keep art inside the ~66dp safe zone
encode(place(trans, transBox, 432, 0.62, [0, 0, 0, 0]), path.join(res, 'bloody_icon_fg.png'));

const full = decode(path.join(__dirname, 'art', 'Bloodygram.png'));
const fullBox = bbox(full, (p, i) => p[i] < 40 && p[i + 1] < 40 && p[i + 2] < 40);
encode(place(full, fullBox, 192, 0.8, [0, 0, 0, 255]), path.join(res, 'bloody_icon_legacy.png'));
