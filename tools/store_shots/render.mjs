#!/usr/bin/env node
/**
 * Timeline Exporter — Play Store screenshot decorator ("Aurora" template).
 *
 *   node render.mjs --ingest --slot phone     move fresh screengrab output into screenshots_raw/
 *   node render.mjs                            decorate every locale × every slot
 *   node render.mjs --slot phone --locales ru-RU,ar
 *   node render.mjs --palette orange --preview-only
 *
 * Reads  : <paths.raw>/<locale>/<slot>/<src>.png        (raw device captures)
 * Writes : <paths.metadata>/<locale>/images/<slotDir>/<out>.png
 *          <paths.metadata>/<locale>/images/featureGraphic.png
 *          <paths.preview>/<slot>_<locale>.png          (contact sheet for review)
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { chromium } from 'playwright';
import { css, frameHtml, featureHtml } from './template/theme.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const cfg = JSON.parse(fs.readFileSync(path.join(HERE, 'config.json'), 'utf8'));
const abs = (p) => path.resolve(HERE, p);

/* ------------------------------------------------------------------ args */
const argv = process.argv.slice(2);
const flag = (n) => argv.includes('--' + n);
const opt = (n, d = null) => { const i = argv.indexOf('--' + n); return i > -1 && argv[i + 1] ? argv[i + 1] : d; };

const paletteName = opt('palette', cfg.palette);
const pal = cfg.palettes[paletteName];
if (!pal) { console.error(`unknown palette "${paletteName}" — have: ${Object.keys(cfg.palettes).join(', ')}`); process.exit(2); }

const slots = cfg.slots.filter(s => !opt('slot') || opt('slot').split(',').includes(s.id));
const locales = (opt('locales') || cfg.locales.join(',')).split(',').map(s => s.trim()).filter(Boolean);
const frames = cfg.frames.filter(f => !opt('frames') || opt('frames').split(',').includes(f.out));
const previewOnly = flag('preview-only');
const noFeature = flag('no-feature');

/* --------------------------------------------------------------- ingest */
// screengrab drops raw PNGs straight into the metadata folder. Move them out
// so decorating is idempotent: raw stays raw, metadata holds finished art.
if (flag('ingest')) {
  let moved = 0;
  for (const locale of locales) {
    for (const slot of slots) {
      const from = path.join(abs(cfg.paths.metadata), locale, 'images', slot.dir);
      if (!fs.existsSync(from)) continue;
      // The folder holds last run's finished art AND, right after a screengrab
      // pass, the fresh captures. screengrab's clear_previous_screenshots only
      // deletes *.png, so .decorated survives a capture -- skipping on its mere
      // presence silently strands the new shots. Tell them apart by mtime.
      const marker = path.join(from, '.decorated');
      const decoratedAt = fs.existsSync(marker) ? fs.statSync(marker).mtimeMs : 0;
      const to = path.join(abs(cfg.paths.raw), locale, slot.id);
      let took = 0;
      for (const f of fs.readdirSync(from)) {
        if (!/\.(png|jpe?g|webp)$/i.test(f)) continue;
        const src = path.join(from, f);
        if (fs.statSync(src).mtimeMs <= decoratedAt) continue;   // our own output
        if (!took) fs.mkdirSync(to, { recursive: true });
        fs.renameSync(src, path.join(to, f));
        took++; moved++;
      }
      // the marker now describes art we are about to re-render -- drop it
      if (took && decoratedAt) fs.rmSync(marker, { force: true });
      if (!took && decoratedAt) console.log(`skip ${locale}/${slot.id}: no capture newer than the last decorate`);
    }
  }
  console.log(`ingested ${moved} raw capture(s) → ${cfg.paths.raw}`);
  if (!flag('and-render')) process.exit(0);
}

/* ---------------------------------------------------------------- fonts */
function fontCss() {
  const out = [];
  // 1. @fontsource packages: reuse their per-weight CSS (correct unicode-ranges)
  for (const pkg of cfg.fonts.fontsource || []) {
    const dir = path.join(HERE, 'node_modules', '@fontsource', pkg);
    if (!fs.existsSync(dir)) continue;
    for (const w of [500, 700, 800]) {
      const file = path.join(dir, `${w}.css`);
      if (!fs.existsSync(file)) continue;
      out.push(fs.readFileSync(file, 'utf8').replace(
        /url\(\.\/files\/([^)]+)\)/g,
        (_, f) => `url(${pathToFileURL(path.join(dir, 'files', f)).href})`));
    }
  }
  // 2. loose TTF/OTF files (tools/fonts) — one face covering the whole weight range
  const seen = new Set();
  for (const d of cfg.paths.fontDirs || []) {
    const dir = abs(d);
    if (!fs.existsSync(dir)) continue;
    for (const f of fs.readdirSync(dir)) {
      const m = /^(.+)\.(ttf|otf)$/i.exec(f);
      if (!m) continue;
      const family = cfg.fonts.files?.[m[1]];
      if (!family || seen.has(family)) continue;
      seen.add(family);
      out.push(`@font-face{font-family:'${family}';font-style:normal;font-weight:100 900;font-display:block;` +
        `src:url(${pathToFileURL(path.join(dir, f)).href}) format('${m[2].toLowerCase() === 'otf' ? 'opentype' : 'truetype'}');}`);
    }
  }
  if (!out.length) console.warn('! no fonts found — check fonts.fontsource / paths.fontDirs');
  return out.join('\n');
}

/* -------------------------------------------------------------- helpers */
function imageSize(file) {
  const b = fs.readFileSync(file);
  if (b.slice(0, 8).toString('hex') === '89504e470d0a1a0a') return { w: b.readUInt32BE(16), h: b.readUInt32BE(20) };
  if (b[0] === 0xff && b[1] === 0xd8) {
    let i = 2;
    while (i < b.length) {
      if (b[i] !== 0xff) { i++; continue; }
      const m = b[i + 1];
      if (m >= 0xc0 && m <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(m)) return { h: b.readUInt16BE(i + 5), w: b.readUInt16BE(i + 7) };
      i += 2 + b.readUInt16BE(i + 2);
    }
  }
  throw new Error('unsupported image: ' + file);
}
const MIME = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', webp: 'image/webp' };

function capture(locale, slot, name) {
  for (const loc of [locale, cfg.defaultLocale]) {
    for (const ext of ['png', 'jpg', 'jpeg', 'webp']) {
      const f = path.join(abs(cfg.paths.raw), loc, slot.id, `${name}.${ext}`);
      if (!fs.existsSync(f)) continue;
      const { w, h } = ext === 'webp' ? { w: 1080, h: 2400 } : imageSize(f);
      // How much of the top of the raw capture is the device's own status bar.
      // It is cropped off and redrawn (see template/theme.js sysbarHtml), so
      // the store art does not carry the emulator's clock, its notification
      // icons, or whatever that AVD's SystemUI does with its side padding.
      const enabled = cfg.statusBar && cfg.statusBar.enabled !== false;
      const crop = enabled ? Math.min(slot.statusBarPx || 0, Math.round(h * 0.2)) : 0;
      // ...and the system bar at the bottom: the gesture pill on the phone, the
      // taskbar full of Google's own app icons on a tablet. Cropped, not
      // redrawn — there is nothing there a store screenshot wants.
      const bottom = Math.min(slot.bottomCropPx || 0, Math.round(h * 0.2));
      const shown = h - crop - bottom;
      return {
        src: `data:${MIME[ext]};base64,${fs.readFileSync(f).toString('base64')}`,
        aspect: shown / w,               // the device frame is sized to what is left
        w, h, crop, bottom,
        cropPct: +(crop / h * 100).toFixed(4),      // of the image's own height
        barPct:  +(crop / shown * 100).toFixed(4),  // of the visible screen's height
        fallback: loc !== locale,
      };
    }
  }
  return null;
}

function localeCtx(locale) {
  const lang = locale.split('-')[0];
  const dir = (cfg.rtl || []).some(r => r === locale || r === lang) ? 'rtl' : 'ltr';
  let scriptClass = '', script = null;
  for (const [name, s] of Object.entries(cfg.script || {})) {
    if (s.locales.includes(locale) || s.locales.includes(lang)) { scriptClass = 's-' + name; script = s; break; }
  }
  const stack = cfg.fonts.stacks[locale] || cfg.fonts.stacks[lang] || cfg.fonts.stacks.default;
  return { dir, scriptClass, script, stack };
}

const AUTOFIT = `(sel, max, min, maxLines) => {
  const el = document.querySelector(sel); if (!el) return;
  const cs = getComputedStyle(el);
  const ratio = parseFloat(cs.lineHeight) / parseFloat(cs.fontSize);
  for (let s = max; s >= min; s -= 1) {
    el.style.fontSize = s + 'px';
    if (Math.round(el.scrollHeight / (s * ratio)) <= maxLines && el.scrollWidth <= el.clientWidth + 1) return;
  }
  el.style.fontSize = min + 'px';
}`;

const FIT_DEVICE = `({frameW, frameH, titleMin}) => {
  const head = document.querySelector('.head');
  const dev  = document.querySelector('.dev');
  const phone = dev.querySelector('.phone');
  const screen = dev.querySelector('.screen');
  const title = document.querySelector('.title');
  const g = JSON.parse(dev.dataset.geo);

  const apply = (w) => {
    const bezel = Math.max(4, Math.round(g.bezelRatio * w));
    const radius = Math.round(g.radiusRatio * w);
    const hgt = Math.round((w - bezel * 2) * g.aspect) + bezel * 2;
    phone.style.width = w + 'px';
    phone.style.height = hgt + 'px';
    phone.style.padding = bezel + 'px';
    phone.style.borderRadius = radius + 'px';
    screen.style.borderRadius = Math.max(0, radius - bezel) + 'px';
    return hgt;
  };

  const fit = () => {
    const top = head.getBoundingClientRect().bottom + g.gap;
    const avail = (frameH + g.bleed) - top;                  // vertical room for the device
    let w = Math.round((avail - 2 * g.bezelRatio * g.prefW) / g.aspect + 2 * g.bezelRatio * g.prefW);
    w = Math.min(g.maxW, Math.max(g.minW, w));
    const hgt = apply(w);
    // a device that does not fill the space below the headline is centred there
    // instead of hanging off the bottom edge (landscape tablet captures)
    const room = frameH - top;
    const bottom = hgt < room - g.gap ? Math.round((room - hgt) / 2) : -g.bleed;
    dev.style.bottom = bottom + 'px';
    return { top, hgt, deviceTop: frameH - bottom - hgt };
  };

  let r = fit();
  let shrunk = 0;
  // if the device had to stay at minW and now collides with the headline, shrink the title
  while (r.deviceTop < r.top - 1 && parseFloat(title.style.fontSize) > titleMin) {
    title.style.fontSize = (parseFloat(title.style.fontSize) - 2) + 'px';
    shrunk += 2;
    r = fit();
  }
  return { width: parseInt(phone.style.width), shrunk, overlap: Math.max(0, Math.round(r.top - r.deviceTop) - 3) };
}`;

const STYLE_BAR = `({crop, rawW, pad, text, icon, gap}) => {
  const bar = document.querySelector('.sysbar');
  if (!bar || !crop) return null;
  const screen = document.querySelector('.screen');
  const img = screen.querySelector('img');
  const S = screen.getBoundingClientRect().width;
  const barPx = S * crop / rawW;

  // Background: the app's own pixels one row below where the status bar ended.
  // A median across the middle of that row, so a stray icon or a map label does
  // not decide the colour. This is what lets one bar sit on a near-white screen
  // and on a dark map without either looking like a pasted-on band.
  let bg = [255, 255, 255];
  try {
    const c = document.createElement('canvas');
    c.width = rawW; c.height = 1;
    const g = c.getContext('2d', { willReadFrequently: true });
    g.drawImage(img, 0, -(crop + 2), rawW, img.naturalHeight);
    const d = g.getImageData(0, 0, rawW, 1).data;
    const ch = [[], [], []];
    for (let x = Math.round(rawW * 0.08); x < rawW * 0.92; x++) {
      ch[0].push(d[x * 4]); ch[1].push(d[x * 4 + 1]); ch[2].push(d[x * 4 + 2]);
    }
    bg = ch.map(a => { a.sort((p, q) => p - q); return a[a.length >> 1]; });
  } catch (e) { /* tainted canvas: fall back to white */ }

  const lum = (0.2126 * bg[0] + 0.7152 * bg[1] + 0.0722 * bg[2]) / 255;
  bar.style.background = 'rgb(' + bg.join(',') + ')';
  bar.style.color = lum > 0.55 ? 'rgba(0,0,0,.84)' : 'rgba(255,255,255,.95)';
  // The screen is rounded, and on a wide tablet that arc is deeper than the bar
  // is tall — a clock at the nominal inset would be clipped away by it.
  const r = parseFloat(getComputedStyle(screen).borderTopLeftRadius) || 0;
  const inset = Math.max(barPx * pad, r * 0.42);
  bar.style.fontSize = (barPx * text) + 'px';
  bar.style.paddingLeft = bar.style.paddingRight = inset + 'px';
  bar.querySelector('.sb-icons').style.gap = (barPx * gap) + 'px';
  for (const svg of bar.querySelectorAll('svg')) { svg.style.height = (barPx * icon) + 'px'; svg.style.width = 'auto'; }
  return { barPx: Math.round(barPx), bg, lum: +lum.toFixed(3) };
}`;

/* ------------------------------------------------------------------ run */
const FONTS = fontCss();
const STYLES = css(cfg, pal, FONTS);
const TMP = path.join(HERE, '.tmp');
fs.mkdirSync(TMP, { recursive: true });

const warn = [];
const browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
let total = 0;

async function shoot(page, html, w, h, sel, outFile, ctx, autofit, cap) {
  const file = path.join(TMP, 'page.html');
  fs.writeFileSync(file, `<!doctype html><html dir="${ctx.dir}"><head><meta charset="utf-8"><style>${STYLES}
    body{font-family:${ctx.stack.map(f => `'${f}'`).join(',')},system-ui,sans-serif}</style></head><body>${html}</body></html>`);
  await page.setViewportSize({ width: w, height: h });
  await page.goto(pathToFileURL(file).href, { waitUntil: 'load' });
  await page.evaluate(() => document.fonts.ready);
  for (const a of autofit) await page.evaluate(a);
  const fix = autofit.length > 1
    ? await page.evaluate(`(${FIT_DEVICE})({frameW:${w}, frameH:${h}, titleMin:${Math.round((cfg.type.titleMin - 10) * (Math.min(w,h)/1080))}})`)
    : { width: 0, shrunk: 0, overlap: 0 };
  // after fitDevice: the bar is sized from the device's final on-frame width
  if (cap && cap.crop) {
    const sb = cfg.statusBar || {};
    await page.evaluate(`(${STYLE_BAR})(${JSON.stringify({
      crop: cap.crop, rawW: cap.w,
      pad: sb.padRatio ?? 0.26, text: sb.textRatio ?? 0.27,
      icon: sb.iconRatio ?? 0.30, gap: sb.gapRatio ?? 0.11,
    })})`);
  }
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  await page.locator(sel).screenshot({ path: outFile });
  return fix;
}

for (const slot of slots) {
  for (const locale of locales) {
    const strFile = path.join(HERE, 'locales', `${locale}.json`);
    if (!fs.existsSync(strFile)) { warn.push(`${locale}: no locales/${locale}.json`); continue; }
    const str = JSON.parse(fs.readFileSync(strFile, 'utf8'));
    const ctx = localeCtx(locale);
    const outDir = previewOnly
      ? path.join(abs(cfg.paths.preview), '_frames', slot.id, locale)
      : path.join(abs(cfg.paths.metadata), locale, 'images', slot.dir);

    const page = await browser.newPage({ deviceScaleFactor: 1 });
    const made = [];

    for (const f of frames) {
      const t = str[f.key];
      if (!t) { warn.push(`${locale}: missing key "${f.key}"`); continue; }
      const cap = capture(locale, slot, f.src);
      if (!cap) { warn.push(`${locale}/${slot.id}: no raw capture "${f.src}"`); continue; }
      if (cap.fallback) warn.push(`${locale}/${slot.id}/${f.src}: using ${cfg.defaultLocale} capture`);

      // a landscape capture gets a landscape frame (Play accepts 16:9 too)
      const box = cap.aspect < 1 ? (slot.landscape || { w: slot.h, h: slot.w }) : slot;
      const view = { ...slot, w: box.w, h: box.h };
      const k2 = view.typeScale ?? (Math.min(box.w, box.h) / 1080);

      const outFile = path.join(outDir, `${f.out}.png`);
      const fix = await shoot(page, frameHtml(cfg, view, t, cap, ctx), box.w, box.h, '.frame', outFile, ctx, [
        `(${AUTOFIT})('.title', ${Math.round(cfg.type.titleMax * k2)}, ${Math.round(cfg.type.titleMin * k2)}, ${cfg.type.titleLines})`,
        `(${AUTOFIT})('.sub', ${Math.round(cfg.type.subMax * k2)}, ${Math.round(cfg.type.subMin * k2)}, ${cfg.type.subLines})`,
      ], cap);
      if (fix.overlap > 0) warn.push(`${locale}/${slot.id}/${f.out}: headline overlaps the device by ${fix.overlap}px — shorten the string`);
      else if (fix.shrunk) warn.push(`${locale}/${slot.id}/${f.out}: tight — title shrunk ${fix.shrunk}px`);
      made.push(outFile); total++;
    }

    if (!noFeature && cfg.featureGraphic.enabled && slot.id === cfg.slots[0].id && str._feature) {
      const cap = capture(locale, slot, cfg.featureGraphic.source);
      if (cap) {
        const out = previewOnly
          ? path.join(abs(cfg.paths.preview), '_frames', 'feature', `${locale}.png`)
          : path.join(abs(cfg.paths.metadata), locale, 'images', 'featureGraphic.png');
        await shoot(page, featureHtml(cfg, cfg.featureGraphic, str._feature, cap, ctx),
          cfg.featureGraphic.w, cfg.featureGraphic.h, '.fg', out, ctx,
          [`(${AUTOFIT})('.t1', ${Math.round(cfg.featureGraphic.w * .0605)}, 34, 2)`], cap);
      }
    }

    await page.close();

    // Renaming or dropping a frame leaves last run's PNG in the metadata
    // folder, and `supply` uploads whatever it finds there — which is how a
    // renamed set becomes twelve images and Play refuses the lot (it takes
    // eight). Only when this run was asked for every frame, so a deliberate
    // `--frames 01_map` does not delete the other seven.
    if (made.length && !previewOnly && !opt('frames')) {
      const keep = new Set(made.map(f => path.basename(f)));
      for (const f of fs.readdirSync(outDir)) {
        if (!/\.(png|jpe?g|webp)$/i.test(f) || keep.has(f)) continue;
        fs.rmSync(path.join(outDir, f), { force: true });
        console.log(`  removed stale ${slot.id}/${locale}/${f}`);
      }
    }
    if (made.length && !previewOnly) fs.writeFileSync(path.join(outDir, '.decorated'), new Date().toISOString());
    console.log(`${slot.id.padEnd(10)} ${locale.padEnd(6)} ${made.length} shot(s)`);
  }
}

await browser.close();
console.log(`\n${total} screenshot(s) rendered with the "${paletteName}" palette.`);
if (warn.length) {
  console.log('\nwarnings:');
  for (const w of [...new Set(warn)]) console.log('  ! ' + w);
  process.exitCode = warn.some(w => /missing key|no raw capture|no locales/.test(w)) ? 1 : 0;
}
