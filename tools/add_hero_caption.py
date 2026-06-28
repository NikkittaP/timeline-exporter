#!/usr/bin/env python3
"""
Generate the captioned hero image (01_hero.png) for every locale.

screengrab captures a clean full-screen map as 03_map_fullscreen.png. This
script overlays the localized "100% on-device" caption (from
fastlane/screenshot_captions.txt) onto a copy of it and saves the result as
01_hero.png — for phone and both tablet sizes.

Run automatically by the Fastlane lanes after capture, or by hand:

    pip install Pillow
    python tools/add_hero_caption.py

Optional: pass a metadata root to process a different folder (used for tests):

    python tools/add_hero_caption.py path/to/metadata/android

Notes on fonts: captions span Latin, Cyrillic, Arabic, Devanagari, CJK, etc.
The script picks a system font that covers each script (Windows paths first,
macOS/Linux fallbacks). Correct Arabic/Hindi shaping needs a Pillow built with
the RAQM layout engine — the standard Windows/macOS wheels include it.

----------------------------------------------------------------------------
Style knobs — tweak these to restyle the banner.
"""

# Banner gradient (left -> right). Brand orange -> rose. RGB tuples.
BANNER_C1 = (255, 92, 38)
BANNER_C2 = (226, 42, 96)
BANNER_ALPHA = 255  # 255 = solid; lower for a hint of map showing
TEXT_COLOR = (255, 255, 255)
TEXT_SHADOW = (0, 0, 0, 120)  # soft drop shadow behind the text
ACCENT_COLOR = (255, 255, 255, 235)  # the little "kicker" bar above the text
BANNER_AT_TOP = True  # False = anchor the banner to the bottom edge
# ----------------------------------------------------------------------------

import glob
import os
import re
import sys
import time

try:
    from PIL import Image, ImageDraw, ImageFont, features
except ImportError:
    sys.exit("Pillow is required: pip install Pillow")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_META = os.path.join(ROOT, "fastlane", "metadata", "android")
CAPTIONS_FILE = os.path.join(ROOT, "fastlane", "screenshot_captions.txt")
TARGET_NAME = "01_hero.png"
DEVICE_DIRS = ("phoneScreenshots", "sevenInchScreenshots", "tenInchScreenshots")

WIN = r"C:\Windows\Fonts"


def _win(*names):
    return [os.path.join(WIN, n) for n in names]


# Per-script ordered font candidates (bold preferred). Windows first.
FONTS = {
    "latin": _win("seguisb.ttf", "arialbd.ttf", "arial.ttf"),
    "ar": _win("tahomabd.ttf", "tahoma.ttf", "arialbd.ttf", "arial.ttf"),
    "hi": _win("NirmalaB.ttf", "Nirmala.ttf", "mangalb.ttf", "mangal.ttf"),
    "zh-CN": _win("msyhbd.ttc", "msyh.ttc", "simhei.ttf"),
    "zh-TW": _win("msjhbd.ttc", "msjh.ttc"),
    "ja": _win("YuGothB.ttc", "meiryob.ttc", "meiryo.ttc", "msgothic.ttc"),
    "ko": _win("malgunbd.ttf", "malgun.ttf"),
}
FONTS_MAC = {
    "latin": ["/System/Library/Fonts/Supplemental/Arial Bold.ttf"],
    "ar": ["/System/Library/Fonts/Supplemental/Arial Bold.ttf"],
    "hi": ["/System/Library/Fonts/Supplemental/DevanagariMT.ttc"],
    "zh-CN": ["/System/Library/Fonts/PingFang.ttc"],
    "zh-TW": ["/System/Library/Fonts/PingFang.ttc"],
    "ja": ["/System/Library/Fonts/Hiragino Sans GB.ttc"],
    "ko": ["/System/Library/Fonts/AppleSDGothicNeo.ttc"],
}
# Linux fallbacks (handy for CI / testing; Windows uses the WIN paths).
FONTS_LINUX = {
    "latin": [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ],
}


def _probe_raqm():
    """features.check('raqm') can report True on a build where rendering with
    a direction still fails. Actually try it once and see."""
    if not features.check("raqm"):
        return False
    for path in FONTS["latin"] + FONTS_LINUX["latin"]:
        if os.path.exists(path):
            try:
                f = ImageFont.truetype(path, 20, layout_engine=ImageFont.Layout.RAQM)
                ImageDraw.Draw(Image.new("RGB", (8, 8))).text(
                    (0, 0), "x", font=f, direction="ltr"
                )
                return True
            except Exception:
                return False
    return False


# True only when libraqm actually works (needed for Arabic/Indic shaping + RTL).
HAVE_RAQM = _probe_raqm()


def script_for(locale):
    if locale in ("zh-CN", "zh-TW"):
        return locale
    lang = locale.split("-")[0]
    if lang == "zh":
        return "zh-CN"
    if lang in ("ar", "hi", "ja", "ko"):
        return lang
    return "latin"


def find_font(locale, size):
    s = script_for(locale)
    candidates = (
        FONTS.get(s, [])
        + FONTS_MAC.get(s, [])
        + FONTS_LINUX.get(s, [])
        + FONTS["latin"]
        + FONTS_LINUX["latin"]
    )
    layout = ImageFont.Layout.RAQM if HAVE_RAQM else ImageFont.Layout.BASIC
    for path in candidates:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size, layout_engine=layout)
            except Exception:
                continue
    return ImageFont.load_default()


def load_captions():
    """Parse 'locale: caption' lines; ignore the prose header."""
    caps = {}
    rx = re.compile(r"^\s*([A-Za-z]{2,3}(?:-[A-Za-z]{2,4})?)\s*:\s*(.+?)\s*$")
    with open(CAPTIONS_FILE, encoding="utf-8") as f:
        for line in f:
            m = rx.match(line)
            if m:
                caps[m.group(1)] = m.group(2)
    return caps


def caption_for(folder_locale, caps):
    if folder_locale in caps:
        return caps[folder_locale]
    lang = folder_locale.split("-")[0]
    for key, val in caps.items():
        if key.split("-")[0] == lang:
            return val
    return caps.get("en-US")


def wrap(draw, text, font, max_w, char_wrap):
    if char_wrap:
        lines, cur = [], ""
        for ch in text:
            if draw.textlength(cur + ch, font=font) <= max_w:
                cur += ch
            else:
                lines.append(cur)
                cur = ch
        if cur:
            lines.append(cur)
        return lines
    lines, cur = [], ""
    for word in text.split():
        trial = (cur + " " + word).strip()
        if draw.textlength(trial, font=font) <= max_w:
            cur = trial
        else:
            if cur:
                lines.append(cur)
            cur = word
    if cur:
        lines.append(cur)
    return lines


def _gradient_band(w, h, c1, c2, alpha):
    """A left->right gradient between c1 and c2, returned as RGBA."""
    row = Image.new("L", (w, 1))
    px = row.load()
    for x in range(w):
        px[x, 0] = int(255 * x / max(1, w - 1))
    mask = row.resize((w, h))
    base = Image.new("RGB", (w, h), c1)
    base.paste(Image.new("RGB", (w, h), c2), (0, 0), mask)
    band = base.convert("RGBA")
    if alpha < 255:
        a = band.split()[3].point(lambda _v: alpha)
        band.putalpha(a)
    return band


def _draw_text(od, x, y, text, font, color, rtl):
    kwargs = {"font": font, "fill": color}
    if HAVE_RAQM:
        try:
            od.text((x, y), text, direction=("rtl" if rtl else "ltr"), **kwargs)
            return
        except Exception:
            pass
    od.text((x, y), text, **kwargs)


def render(src, dst, locale, caption):
    img = Image.open(src).convert("RGBA")
    W, H = img.size
    probe = ImageDraw.Draw(img)
    char_wrap = script_for(locale) in ("zh-CN", "zh-TW", "ja")
    rtl = locale.split("-")[0] == "ar"
    max_w = int(W * 0.88)

    # Shrink the font until the caption fits in at most two lines.
    size = max(28, int(W / 13))
    font = find_font(locale, size)
    lines = wrap(probe, caption, font, max_w, char_wrap)
    while len(lines) > 2 and size > 20:
        size -= 2
        font = find_font(locale, size)
        lines = wrap(probe, caption, font, max_w, char_wrap)

    line_h = int(size * 1.30)
    pad = int(size * 0.85)
    accent_h = max(4, int(size * 0.16))
    accent_w = int(W * 0.12)
    accent_gap = int(size * 0.55)
    text_h = line_h * len(lines)
    banner_h = pad + accent_h + accent_gap + text_h + pad
    shadow_h = int(size * 0.7)

    band = _gradient_band(W, banner_h, BANNER_C1, BANNER_C2, BANNER_ALPHA)

    # Soft drop shadow that fades away from the banner edge, so it floats.
    shadow = Image.new("RGBA", (W, shadow_h), (0, 0, 0, 0))
    sd = shadow.load()
    for yy in range(shadow_h):
        a = int(95 * (1 - yy / shadow_h))
        for xx in range(W):
            sd[xx, yy] = (0, 0, 0, a)

    overlay = Image.new("RGBA", (W, banner_h + shadow_h), (0, 0, 0, 0))
    overlay.paste(band, (0, 0))
    overlay.alpha_composite(shadow, (0, banner_h))

    od = ImageDraw.Draw(overlay)

    # Kicker accent bar, centered.
    ax = (W - accent_w) // 2
    ay = pad
    try:
        od.rounded_rectangle(
            [ax, ay, ax + accent_w, ay + accent_h],
            radius=accent_h // 2,
            fill=ACCENT_COLOR,
        )
    except Exception:
        od.rectangle([ax, ay, ax + accent_w, ay + accent_h], fill=ACCENT_COLOR)

    # Caption lines, centered, each with a soft shadow for legibility.
    y = pad + accent_h + accent_gap
    for ln in lines:
        w = od.textlength(ln, font=font)
        x = int((W - w) / 2)
        _draw_text(od, x + 2, y + 2, ln, font, TEXT_SHADOW, rtl)
        _draw_text(od, x, y, ln, font, TEXT_COLOR, rtl)
        y += line_h

    top = 0 if BANNER_AT_TOP else (H - overlay.size[1])
    img.alpha_composite(overlay, (0, top))
    img.convert("RGB").save(dst)


def main():
    meta = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_META
    if not os.path.isdir(meta):
        sys.exit(f"Metadata folder not found: {meta}")
    caps = load_captions()
    if not caps:
        sys.exit(f"No captions parsed from {CAPTIONS_FILE}")

    count = 0
    failures = []
    for locale in sorted(os.listdir(meta)):
        images = os.path.join(meta, locale, "images")
        if not os.path.isdir(images):
            continue
        caption = caption_for(locale, caps)
        if not caption:
            print(f"  ! no caption for {locale}, skipping")
            continue
        for dev in DEVICE_DIRS:
            dev_dir = os.path.join(images, dev)
            # screengrab may append a timestamp (03_map_fullscreen_<ts>.png), so
            # match by prefix and take the newest.
            matches = sorted(glob.glob(os.path.join(dev_dir, "03_map_fullscreen*.png")))
            if not matches:
                continue
            src, dst = matches[-1], os.path.join(dev_dir, TARGET_NAME)
            # Retry: when run right after capture, the freshly-written PNG can be
            # momentarily locked (antivirus / slow flush). One failure must NOT
            # abort the whole batch, otherwise only the first locale gets done.
            err = None
            for attempt in range(4):
                try:
                    render(src, dst, locale, caption)
                    err = None
                    break
                except Exception as e:  # noqa: BLE001 - keep going on any error
                    err = e
                    time.sleep(0.5)
            if err is None:
                count += 1
                print(f'  {locale}/{dev}  <- "{caption[:48]}..."')
            else:
                failures.append(f"{locale}/{dev}: {err}")
                print(f"  ! {locale}/{dev} failed: {err}")

    print(f"Done. Wrote {count} hero image(s).")
    if failures:
        print(f"WARNING: {len(failures)} image(s) failed (see above). Just re-run "
              "`python tools/add_hero_caption.py` to finish them.")
    if not HAVE_RAQM:
        print(
            "WARNING: this Pillow build lacks libraqm; Arabic/Hindi may render "
            "unshaped. Reinstall Pillow from a wheel that bundles raqm."
        )


if __name__ == "__main__":
    main()
