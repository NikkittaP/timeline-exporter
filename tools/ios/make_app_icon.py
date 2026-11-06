#!/usr/bin/env python3
"""Renders the Android launcher vector icon as the 1024 px opaque iOS app icon.

Usage: pip install resvg-py pillow; python tools/ios/make_app_icon.py
"""
import io
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"
ROOT = Path(__file__).resolve().parents[2]
BACKGROUND = ROOT / "app/src/main/res/drawable/ic_launcher_background.xml"
FOREGROUND = ROOT / "app/src/main/res/drawable/ic_launcher_foreground.xml"
OUTPUT = ROOT / "ios/TimelineExporter/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"


def _color(value, prop):
    """'#AARRGGBB' or '#RRGGBB' -> SVG attributes (colour plus opacity if not opaque)."""
    h = value.lstrip("#")
    if len(h) == 8:
        alpha = int(h[:2], 16) / 255
        out = f'{prop}="#{h[2:].upper()}"'
        if alpha < 1:
            out += f' {prop}-opacity="{alpha:.3f}"'
        return out
    return f'{prop}="#{h.upper()}"'


def _paths(xml):
    parts = []
    for p in ET.fromstring(xml).iter("path"):
        attrs = [f'd="{p.get(ANDROID + "pathData")}"']
        fill = p.get(ANDROID + "fillColor")
        stroke = p.get(ANDROID + "strokeColor")
        attrs.append(_color(fill, "fill") if fill else 'fill="none"')
        if stroke:
            attrs.append(_color(stroke, "stroke"))
            for android, svg in (("strokeWidth", "stroke-width"),
                                 ("strokeLineCap", "stroke-linecap"),
                                 ("strokeLineJoin", "stroke-linejoin")):
                v = p.get(ANDROID + android)
                if v:
                    attrs.append(f'{svg}="{v}"')
        parts.append("<path " + " ".join(attrs) + "/>")
    return parts


def vector_to_svg(background_xml, foreground_xml):
    body = _paths(background_xml) + _paths(foreground_xml)
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">'
            + "".join(body) + "</svg>")


def render_png(svg, size=1024):
    import resvg_py  # lazy: only the CLI needs it, CI tests do not
    from PIL import Image
    png = bytes(resvg_py.svg_to_bytes(svg_string=svg, width=size, height=size))
    img = Image.open(io.BytesIO(png)).convert("RGBA")
    flat = Image.new("RGB", img.size, (255, 255, 255))
    flat.paste(img, mask=img.getchannel("A"))  # App Store icons must have no alpha
    return flat


def main():
    svg = vector_to_svg(BACKGROUND.read_text(encoding="utf-8"),
                        FOREGROUND.read_text(encoding="utf-8"))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    render_png(svg).save(OUTPUT, "PNG")
    print(f"wrote {OUTPUT}")


if __name__ == "__main__":
    main()
