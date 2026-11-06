"""Sanity-check generated .cube LUTs: header, line count, value range."""
import os

NAMES = ["portra400", "velvia50", "trix400", "gold200", "superia400", "provia100f"]
BASE = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "luts")

ok = True
for name in NAMES:
    path = os.path.join(BASE, f"{name}.cube")
    n = 0
    bad = 0
    header_ok = False
    with open(path) as f:
        for line in f:
            if line.startswith("LUT_3D_SIZE"):
                header_ok = line.split()[1] == "33"
                continue
            if line.startswith(("#", "TITLE", "DOMAIN")):
                continue
            parts = line.split()
            if len(parts) == 3:
                vals = [float(x) for x in parts]
                if any(v < 0.0 or v > 1.0 for v in vals):
                    bad += 1
                n += 1
    good = header_ok and n == 33 ** 3 and bad == 0
    ok = ok and good
    print(f"{name}: lines={n} bad={bad} header33={header_ok} -> {'OK' if good else 'FAIL'}")

raise SystemExit(0 if ok else 1)
