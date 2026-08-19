// Aurora template — layout + styles for Play Store screenshots.
// Pure HTML/CSS: tweak here without touching the renderer.

export function css(cfg, pal, fontCss) {
  const dark = !!pal.dark;
  return `
${fontCss}
*{box-sizing:border-box;margin:0;padding:0}
html,body{background:#0c0c10}
body{-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility}

.frame{position:relative;overflow:hidden;background:${pal.bg};isolation:isolate}
.glow{position:absolute;border-radius:50%;filter:blur(120px)}
.g1{width:120%;height:57%;left:-33%;top:-22%;background:radial-gradient(closest-side,${pal.glow1},transparent 70%);opacity:${pal.glow1o ?? .85}}
.g2{width:111%;height:52%;right:-35%;top:3%;background:radial-gradient(closest-side,${pal.glow2},transparent 70%);opacity:${pal.glow2o ?? .45}}
.g3{width:130%;height:60%;left:-24%;bottom:-24%;background:radial-gradient(closest-side,${pal.glow3},transparent 70%);opacity:${pal.glow3o ?? .55}}
.grain{position:absolute;inset:0;opacity:${dark ? .18 : .35};background-image:radial-gradient(${dark ? '#ffffff10' : '#00000012'} 1px,transparent 1px);background-size:4px 4px}

.head{position:absolute;z-index:3;text-align:center}
.eyebrow{font-weight:700;letter-spacing:.22em;text-transform:uppercase;color:${pal.eyebrow}}
.title{font-weight:800;letter-spacing:-.035em;color:${pal.ink};text-wrap:balance;word-break:normal;overflow-wrap:break-word}
.title em{font-style:normal;color:${pal.accent}}
.sub{font-weight:500;color:${pal.muted};text-wrap:balance}

/* script-specific typography ------------------------------------------- */
.s-cjk .title,.s-cjk .sub{letter-spacing:0;word-break:normal;line-break:strict}
.s-cjk .eyebrow{letter-spacing:.14em}
.s-arabic .title,.s-arabic .sub,.s-arabic .eyebrow{letter-spacing:0}
.s-arabic .eyebrow{text-transform:none}
.s-indic .title{letter-spacing:-.01em}
.s-thai .title,.s-thai .sub{letter-spacing:0}

.dev{position:absolute;left:50%;transform:translateX(-50%);z-index:2}
.phone{position:relative;background:${cfg.device.bodyColor};box-shadow:
  0 2px 0 rgba(255,255,255,.18) inset,
  0 60px 120px -30px rgba(0,0,0,${dark ? .7 : .45}),
  0 18px 40px -12px rgba(0,0,0,${dark ? .55 : .30})}
.phone::after{content:'';position:absolute;inset:0;border:1px solid rgba(255,255,255,.14);pointer-events:none;border-radius:inherit}
.screen{overflow:hidden;background:#fff;width:100%;height:100%}
.screen img{display:block;width:100%;height:100%;object-fit:cover;object-position:top center}

/* feature graphic ------------------------------------------------------- */
.fg{position:relative;overflow:hidden;background:${pal.bg};display:flex;align-items:center}
.fg .txt{position:relative;z-index:3;max-width:64%}
.fg .t1{font-weight:800;letter-spacing:-.035em;color:${pal.ink}}
.fg .t2{font-weight:500;color:${pal.muted};margin-top:12px}
[dir="rtl"] .fg{flex-direction:row-reverse}
`;
}

const accent = (s) => String(s).replace(/\{(.+?)\}/g, '<em>$1</em>');

export function frameHtml(cfg, slot, t, cap, ctx) {
  const w = slot.w, h = slot.h;
  const d = { ...cfg.device, ...(slot.device || {}) };
  const k = slot.typeScale ?? (Math.min(w, h) / 1080);
  const ty = cfg.type;
  const sc = ctx.script;
  const landscape = cap.aspect < 1;

  // Device geometry is finalised in the browser (fitDevice) once the headline
  // has been auto-fitted — that is what keeps phone and tablet frames balanced.
  const geo = {
    aspect: cap.aspect,
    maxW: Math.round(w * (landscape ? (d.maxWidthRatioLandscape ?? 0.86) : (d.maxWidthRatio ?? 0.92))),
    minW: Math.round(w * (landscape ? 0.55 : (d.minWidthRatio ?? 0.42))),
    prefW: Math.round(w * (landscape ? 0.8 : d.widthRatio)),
    bezelRatio: d.bezel / 676,
    radiusRatio: d.radius / 676,
    bleed: Math.round(d.bleedBottom * k),
    gap: Math.round(h * 0.030)
  };

  return `
<div class="frame ${ctx.scriptClass}" dir="${ctx.dir}" style="width:${w}px;height:${h}px">
  <div class="glow g1"></div><div class="glow g2"></div><div class="glow g3"></div><div class="grain"></div>
  <div class="head" style="left:${Math.round(w * .072)}px;right:${Math.round(w * .072)}px;top:${Math.round(h * .058)}px">
    ${t.eyebrow ? `<div class="eyebrow" style="font-size:${Math.round(ty.eyebrow * k)}px;margin-bottom:${Math.round(h * .0115)}px">${t.eyebrow}</div>` : ''}
    <div class="title" style="font-size:${Math.round(ty.titleMax * k)}px;line-height:${sc?.titleLh ?? 1.02}">${accent(t.title)}</div>
    ${t.sub ? `<div class="sub" style="font-size:${Math.round(ty.subMax * k)}px;line-height:${sc?.subLh ?? 1.35};margin-top:${Math.round(h * .0125)}px">${accent(t.sub)}</div>` : ''}
  </div>
  <div class="dev" data-geo='${JSON.stringify(geo)}' style="bottom:${-geo.bleed}px">
    <div class="phone"><div class="screen"><img src="${cap.src}"></div></div>
  </div>
</div>`;
}

export function featureHtml(cfg, fg, meta, cap, ctx) {
  const w = fg.w, h = fg.h;
  const phoneW = Math.round(w * .22);
  const bezel = 6;
  const phoneH = Math.round((phoneW - bezel * 2) * cap.aspect) + bezel * 2;
  const pad = Math.round(w * .062);
  return `
<div class="fg ${ctx.scriptClass}" dir="${ctx.dir}" style="width:${w}px;height:${h}px">
  <div class="glow g1"></div><div class="glow g2"></div><div class="glow g3"></div><div class="grain"></div>
  <div class="txt" style="padding-inline-start:${pad}px">
    <div class="t1" style="font-size:${Math.round(w * .0605)}px;line-height:${ctx.script?.titleLh ?? 1.05}">${accent(meta.title)}</div>
    <div class="t2" style="font-size:${Math.round(w * .0225)}px;line-height:${ctx.script?.subLh ?? 1.35}">${accent(meta.sub)}</div>
  </div>
  <div class="dev" style="left:auto;right:${Math.round(w * .05)}px;transform:none;bottom:-38%">
    <div class="phone" style="width:${phoneW}px;height:${phoneH}px;padding:${bezel}px;border-radius:26px">
      <div class="screen" style="border-radius:20px"><img src="${cap.src}"></div>
    </div>
  </div>
</div>`;
}
