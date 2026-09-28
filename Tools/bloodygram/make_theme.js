// Generates TMessagesProj/src/main/assets/bloodygram.attheme (black-red Bloodygram theme)
// from Telegram's night.attheme: blues/purples -> red, bluish dark grays -> neutral black.
// Run: node Tools/bloodygram/make_theme.js
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..', '..');
const src = path.join(root, 'TMessagesProj/src/main/assets/night.attheme');
const dst = path.join(root, 'TMessagesProj/src/main/assets/bloodygram.attheme');

const RED_HUE = 356;

// hand-picked colors for the key surfaces
const OVERRIDES = {
    windowBackgroundWhite: 0xFF0E0E0F,
    windowBackgroundGray: 0xFF000000,
    actionBarDefault: 0xFF0E0E0F,
    actionBarDefaultArchived: 0xFF0E0E0F,
    actionBarWhiteSelector: 0x2FFFFFFF,
    chat_wallpaper: 0xFF050303,
    chat_inBubble: 0xFF1C1B1D,
    // outgoing bubbles: screen-anchored red -> dark red -> almost black gradient, shifts while scrolling
    chat_outBubble: 0xFFE01E38,
    chat_outBubbleGradient: 0xFF9A1026,
    chat_outBubbleGradient2: 0xFF480812,
    chat_outBubbleGradient3: 0xFF180206,
    chat_outBubbleGradientAnimated: 1,
    chat_outBubbleSelected: 0xFFA5263A,
    chat_inBubbleSelected: 0xFF2A282B,
    chat_messagePanelBackground: 0xFF0E0E0F,
    chats_actionBackground: 0xFFE0243C,
    chats_actionPressedBackground: 0xFFB81C31,
    chats_unreadCounter: 0xFFE0243C,
    chats_onlineCircle: 0xFFE0243C,
    featuredStickers_addButton: 0xFFE0243C,
    featuredStickers_addButtonPressed: 0xFFB81C31,
    // voice/file buttons in outgoing bubbles: black circle, the icon is cut out and shows the red bubble
    chat_outLoader: 0xFF0B0B0C,
    chat_outLoaderSelected: 0xFF1C1C1E,
    chat_messagePanelSend: 0xFFFF3B55,
};

function toArgb(v) {
    return v.startsWith('#') ? parseInt(v.slice(1), 16) >>> 0 : (parseInt(v, 10) >>> 0);
}

function toSigned(argb) {
    return argb | 0;
}

function rgbToHsl(r, g, b) {
    r /= 255; g /= 255; b /= 255;
    const max = Math.max(r, g, b), min = Math.min(r, g, b);
    let h = 0, s = 0;
    const l = (max + min) / 2;
    if (max !== min) {
        const d = max - min;
        s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
        if (max === r) h = (g - b) / d + (g < b ? 6 : 0);
        else if (max === g) h = (b - r) / d + 2;
        else h = (r - g) / d + 4;
        h *= 60;
    }
    return [h, s, l];
}

function hslToRgb(h, s, l) {
    h = ((h % 360) + 360) % 360 / 360;
    if (s === 0) {
        const v = Math.round(l * 255);
        return [v, v, v];
    }
    const hue = (p, q, t) => {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1 / 6) return p + (q - p) * 6 * t;
        if (t < 1 / 2) return q;
        if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
        return p;
    };
    const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
    const p = 2 * l - q;
    return [hue(p, q, h + 1 / 3), hue(p, q, h), hue(p, q, h - 1 / 3)].map(x => Math.round(x * 255));
}

function recolor(argb) {
    const a = (argb >>> 24) & 0xFF, r = (argb >>> 16) & 0xFF, g = (argb >>> 8) & 0xFF, b = argb & 0xFF;
    let [h, s, l] = rgbToHsl(r, g, b);
    if (s >= 0.18 && h >= 170 && h <= 290) {
        // blue / cyan / purple accents -> red
        h = RED_HUE;
        s = Math.min(1, s * 1.1);
    } else if (s < 0.3 && l < 0.4) {
        // bluish dark grays (backgrounds, bars) -> neutral, darker
        s = 0;
        l = l * 0.55;
    } else if (s < 0.3 && h >= 170 && h <= 290) {
        // bluish light grays (secondary text) -> neutral
        s = 0;
    }
    const [nr, ng, nb] = hslToRgb(h, s, l);
    return ((a << 24) | (nr << 16) | (ng << 8) | nb) >>> 0;
}

const out = [];
const seen = new Set();
for (const line of fs.readFileSync(src, 'utf8').split('\n')) {
    const idx = line.indexOf('=');
    if (idx <= 0) {
        continue;
    }
    const key = line.slice(0, idx);
    const value = line.slice(idx + 1).trim();
    if (!/^(#[0-9a-fA-F]{6,8}|-?\d+)$/.test(value)) {
        out.push(line);
        continue;
    }
    seen.add(key);
    const argb = key in OVERRIDES ? OVERRIDES[key] >>> 0 : recolor(toArgb(value));
    out.push(`${key}=${toSigned(argb)}`);
}
for (const key of Object.keys(OVERRIDES)) {
    if (!seen.has(key)) {
        out.push(`${key}=${toSigned(OVERRIDES[key] >>> 0)}`);
    }
}
fs.writeFileSync(dst, out.join('\n') + '\n');
console.log(`wrote ${out.length} colors to ${path.relative(root, dst)}`);
