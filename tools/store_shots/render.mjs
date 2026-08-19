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
      if (fs.existsSync(path.join(from, '.decorated'))) {
        console.log(`skip ${locale}/${slot.id}: already decorated (delete .decorated to re-ingest)`);
        continue;
      }
      const to = path.join(abs(cfg.paths.raw), locale, slot.id);
      fs.mkdirSync(to, { recursive: true });
      for (const f of fs.readdirSync(from)) {
        if (!/\.(png|jpe?g|webp)$/i.test(f)) continue;
        fs.renameSync(path.join(from, f), path.join(to, f));
        moved++;
      }
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

function capture(locale, slotId, name) {
  for (const loc of [locale, cfg.defaultLocale]) {
    for (const ext of ['png', 'jpg', 'jpeg', 'webp']) {
      const f = path.join(abs(cfg.paths.raw), loc, slotId, `${name}.${ext}`);
      if (!fs.existsSync(f)) continue;
      const { w, h } = ext === 'webp' ? { w: 1080, h: 2400 } : imageSize(f);
      return { src: `data:${MIME[ext]};base64,${fs.readFileSync(f).toString('base64')}`, aspect: h / w, w, h, fallback: loc !== locale };
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

/* ------------------------------------------------------------------ run */
const FONTS = fontCss();
const STYLES = css(cfg, pal, FONTS);
const TMP = path.join(HERE, '.tmp');
fs.mkdirSync(TMP, { recursive: true });

const warn = [];
const browser = await chromium.launch(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
let total = 0;

async function shoot(page, html, w, h, sel, outFile, ctx, autofit) {
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
      const cap = capture(locale, slot.id, f.src);
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
      ]);
      if (fix.overlap > 0) warn.push(`${locale}/${slot.id}/${f.out}: headline overlaps the device by ${fix.overlap}px — shorten the string`);
      else if (fix.shrunk) warn.push(`${locale}/${slot.id}/${f.out}: tight — title shrunk ${fix.shrunk}px`);
      made.push(outFile); total++;
    }

    if (!noFeature && cfg.featureGraphic.enabled && slot.id === cfg.slots[0].id && str._feature) {
      const cap = capture(locale, slot.id, cfg.featureGraphic.source);
      if (cap) {
        const out = previewOnly
          ? path.join(abs(cfg.paths.preview), '_frames', 'feature', `${locale}.png`)
          : path.join(abs(cfg.paths.metadata), locale, 'images', 'featureGraphic.png');
        await shoot(page, featureHtml(cfg, cfg.featureGraphic, str._feature, cap, ctx),
          cfg.featureGraphic.w, cfg.featureGraphic.h, '.fg', out, ctx,
          [`(${AUTOFIT})('.t1', ${Math.round(cfg.featureGraphic.w * .0605)}, 34, 2)`]);
      }
    }

    await page.close();
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
