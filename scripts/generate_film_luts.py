#!/usr/bin/env python3
"""Generate built-in film-simulation LUTs (.cube, 33^3) for PhotonCamera.

Pure standard library (colorsys, math). Deterministic. Idempotent.

Each film look is defined by:
  - per-channel tone curve built from black lift / white shoulder / contrast / gamma
  - luminance-aware saturation scale
  - hue-band shift (e.g. greens toward teal for Superia)
  - split toning: shadow and highlight tints weighted by luminance
  - optional channel mixing (e.g. Tri-X red-weighted B&W luminance)

Outputs are written next to the other built-in LUT payloads:
  app/src/main/assets/luts/<file>.cube

Usage:
  python scripts/generate_film_luts.py [--out app/src/main/assets/luts]
"""

import argparse
import colorsys
import json
import math
import os
import sys

LUT_SIZE = 33

# ---------------------------------------------------------------------------
# Curve building
# ---------------------------------------------------------------------------

def smoothstep(edge0, edge1, x):
    t = max(0.0, min(1.0, (x - edge0) / (edge1 - edge0)))
    return t * t * (3.0 - 2.0 * t)


def build_tone_lut(size, black_lift, white_shoulder, contrast, gamma):
    """Per-channel 256-entry tone curve.

    black_lift:   output value at input 0 (fade/parade of blacks, 0..0.2)
    white_shoulder: 0 = linear top, >0 softens highlights (film shoulder)
    contrast:     pivot at mid gray; >1 steeper
    gamma:        exponent applied around mid gray (1 = neutral)
    """
    lut = []
    for i in range(size):
        v = i / (size - 1)
        # gamma around mid gray
        g = ((v - 0.5) * (1.0 / gamma)) + 0.5 if gamma != 1.0 else v
        # contrast around pivot 0.45 (film negatives sit slightly low)
        pivot = 0.45
        c = pivot + (g - pivot) * contrast
        # black lift (fade)
        c = black_lift + c * (1.0 - black_lift)
        # highlight shoulder: compress the top end
        if white_shoulder > 0.0:
            over = max(0.0, c - (1.0 - 2.5 * white_shoulder))
            compress = over * over / (over + white_shoulder)
            c = c - compress
        lut.append(max(0.0, min(1.0, c)))
    return lut


def sample_lut(lut, v):
    if v <= 0.0:
        return lut[0]
    if v >= 1.0:
        return lut[-1]
    x = v * (len(lut) - 1)
    i = int(x)
    f = x - i
    j = min(i + 1, len(lut) - 1)
    return lut[i] * (1.0 - f) + lut[j] * f


# ---------------------------------------------------------------------------
# Color math
# ---------------------------------------------------------------------------

def hue_band_weight(hue, center, width):
    """1 inside the band (wrapping), smooth falloff outside."""
    d = abs(hue - center)
    d = min(d, 1.0 - d)  # wrap-around distance on hue circle
    return 1.0 - smoothstep(width * 0.5, width, d)


def split_tone(rgb, lum, shadow_tint, shadow_strength, highlight_tint, highlight_strength):
    sw = (1.0 - lum) ** 2 * shadow_strength
    hw = lum ** 2 * highlight_strength
    r = rgb[0] + shadow_tint[0] * sw + highlight_tint[0] * hw
    g = rgb[1] + shadow_tint[1] * sw + highlight_tint[1] * hw
    b = rgb[2] + shadow_tint[2] * sw + highlight_tint[2] * hw
    return (r, g, b)


def hue_shift_band(rgb, band_center, band_width, shift_degrees, sat_boost):
    """Rotate hue and boost saturation inside a hue band (greens->teal etc.)."""
    r, g, b = rgb
    h, l, s = colorsys.rgb_to_hls(max(0.0, min(1.0, r)), max(0.0, min(1.0, g)), max(0.0, min(1.0, b)))
    if s <= 0.003:
        return rgb
    w = hue_band_weight(h, band_center, band_width)
    if w <= 0.0:
        return rgb
    h = (h + (shift_degrees / 360.0) * w) % 1.0
    s = min(1.0, s * (1.0 + (sat_boost - 1.0) * w))
    r2, g2, b2 = colorsys.hls_to_rgb(h, l, s)
    # blend by weight so out-of-band pixels are untouched
    return (r + (r2 - r) * w, g + (g2 - g) * w, b + (b2 - b) * w)


# ---------------------------------------------------------------------------
# Film definitions
# ---------------------------------------------------------------------------

# tone params: (black_lift, white_shoulder, contrast, gamma)
# split tone: (tint rgb offset, strength)

FILMS = {
    "portra400": {
        "title": "Kodak Portra 400",
        "file": "portra400.cube",
        "tone": (0.045, 0.05, 0.92, 0.97),
        "channel_tone": {
            # slightly stronger lift in red, softer blue shoulder (warm cast)
            "r": (0.055, 0.05, 0.92, 0.97),
            "b": (0.035, 0.06, 0.92, 0.97),
        },
        "saturation": 0.92,
        "mid_saturation": 0.98,   # near-neutral skin saturation
        "hue_bands": [
            # gentle warm push inside orange/skin hues
            {"center": 0.06, "width": 0.16, "shift_degrees": 3.0, "sat_boost": 1.04},
            # greens slightly toward yellow-green
            {"center": 0.30, "width": 0.14, "shift_degrees": -4.0, "sat_boost": 0.96},
        ],
        "split": ((0.012, 0.006, -0.004), 0.55, (0.020, 0.012, -0.010), 0.55),
        "mono": None,
    },
    "velvia50": {
        "title": "Fujifilm Velvia 50",
        "file": "velvia50.cube",
        "tone": (0.010, 0.02, 1.28, 1.02),
        "channel_tone": {
            "r": (0.010, 0.02, 1.30, 1.02),
            "b": (0.016, 0.02, 1.26, 1.02),
        },
        "saturation": 1.32,
        "mid_saturation": 1.32,
        "hue_bands": [
            {"center": 0.30, "width": 0.18, "shift_degrees": -8.0, "sat_boost": 1.12},
            {"center": 0.58, "width": 0.16, "shift_degrees": 4.0, "sat_boost": 1.10},
        ],
        "split": ((-0.008, 0.000, 0.010), 0.5, (0.000, 0.002, 0.008), 0.35),
        "mono": None,
    },
    "trix400": {
        "title": "Kodak Tri-X 400",
        "file": "trix400.cube",
        "tone": (0.030, 0.0, 1.42, 1.05),
        "channel_tone": {},
        "saturation": 0.0,
        "mid_saturation": 0.0,
        "hue_bands": [],
        "split": ((0.0, 0.0, 0.0), 0.0, (0.0, 0.0, 0.0), 0.0),
        # classic panchromatic rendering: red-weighted luminance keeps skin bright
        "mono": {"r": 0.34, "g": 0.44, "b": 0.22},
    },
    "gold200": {
        "title": "Kodak Gold 200",
        "file": "gold200.cube",
        "tone": (0.055, 0.04, 1.02, 0.96),
        "channel_tone": {
            "r": (0.070, 0.04, 1.02, 0.96),
            "b": (0.040, 0.05, 1.00, 0.98),
        },
        "saturation": 1.08,
        "mid_saturation": 1.10,
        "hue_bands": [
            {"center": 0.10, "width": 0.20, "shift_degrees": 2.0, "sat_boost": 1.10},
        ],
        "split": ((0.020, 0.010, -0.006), 0.7, (0.030, 0.020, -0.012), 0.8),
        "mono": None,
    },
    "superia400": {
        "title": "Fujifilm Superia 400",
        "file": "superia400.cube",
        "tone": (0.040, 0.03, 1.08, 1.0),
        "channel_tone": {
            "g": (0.048, 0.03, 1.08, 1.0),
        },
        "saturation": 1.12,
        "mid_saturation": 1.10,
        "hue_bands": [
            # Fuji signature: greens toward emerald/teal
            {"center": 0.30, "width": 0.16, "shift_degrees": 10.0, "sat_boost": 1.15},
            # skin keeps a slight magenta balance
            {"center": 0.06, "width": 0.14, "shift_degrees": -2.0, "sat_boost": 1.02},
        ],
        "split": ((-0.004, 0.000, 0.010), 0.5, (0.004, 0.006, 0.008), 0.4),
        "mono": None,
    },
    "provia100f": {
        "title": "Fujifilm Provia 100F",
        "file": "provia100f.cube",
        "tone": (0.012, 0.02, 1.10, 1.0),
        "channel_tone": {},
        "saturation": 1.15,
        "mid_saturation": 1.15,
        "hue_bands": [],
        "split": ((-0.006, 0.000, 0.006), 0.35, (0.0, 0.0, 0.0), 0.0),
        "mono": None,
    },
}


# ---------------------------------------------------------------------------
# Cube generation
# ---------------------------------------------------------------------------

def apply_film(rgb, film, tone_luts):
    mono = film["mono"]
    if mono is not None:
        lum = (
            rgb[0] * mono["r"] + rgb[1] * mono["g"] + rgb[2] * mono["b"]
        )
        v = sample_lut(tone_luts["g"], lum)
        return (v, v, v)

    r = sample_lut(tone_luts["r"], rgb[0])
    g = sample_lut(tone_luts["g"], rgb[1])
    b = sample_lut(tone_luts["b"], rgb[2])

    # saturation: scale chroma around luminance; mids use the boosted value
    lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
    mid_band = 1.0 - abs(lum - 0.5) * 2.0
    sat = film["saturation"] + (film["mid_saturation"] - film["saturation"]) * mid_band
    r = lum + (r - lum) * sat
    g = lum + (g - lum) * sat
    b = lum + (b - lum) * sat

    for band in film["hue_bands"]:
        r, g, b = hue_shift_band((r, g, b), band["center"], band["width"],
                                 band["shift_degrees"], band["sat_boost"])

    st = film["split"]
    r, g, b = split_tone((r, g, b), lum, st[0], st[1], st[2], st[3])
    return (r, g, b)


def build_tone_luts(film):
    base = film["tone"]
    luts = {
        "r": build_tone_lut(256, *(film["channel_tone"].get("r", base))),
        "g": build_tone_lut(256, *(film["channel_tone"].get("g", base))),
        "b": build_tone_lut(256, *(film["channel_tone"].get("b", base))),
    }
    return luts


def generate_cube(film, out_dir):
    tone_luts = build_tone_luts(film)
    path = os.path.join(out_dir, film["file"])
    with open(path, "w", encoding="ascii", newline="\n") as f:
        f.write('# Generated by scripts/generate_film_luts.py - do not edit by hand\n')
        f.write('TITLE "%s"\n' % film["title"])
        f.write('LUT_3D_SIZE %d\n' % LUT_SIZE)
        f.write('DOMAIN_MIN 0.0 0.0 0.0\n')
        f.write('DOMAIN_MAX 1.0 1.0 1.0\n')
        # .cube ordering: red index fastest
        for bi in range(LUT_SIZE):
            for gi in range(LUT_SIZE):
                for ri in range(LUT_SIZE):
                    rgb = apply_film(
                        (ri / (LUT_SIZE - 1), gi / (LUT_SIZE - 1), bi / (LUT_SIZE - 1)),
                        film,
                        tone_luts,
                    )
                    f.write("%.6f %.6f %.6f\n" % (
                        max(0.0, min(1.0, rgb[0])),
                        max(0.0, min(1.0, rgb[1])),
                        max(0.0, min(1.0, rgb[2])),
                    ))
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    default_out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "luts")
    parser.add_argument("--out", default=os.path.normpath(default_out))
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    for key in sorted(FILMS):
        path = generate_cube(FILMS[key], args.out)
        print("wrote %s" % path)


if __name__ == "__main__":
    sys.exit(main())
