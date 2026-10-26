"""Self-check for profile.py: a synthetic +15 ramp from MFOV top to bottom must be recovered."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np, profile as pr
from PIL import Image
tiles = pr.cached_json("tile_specs.json", "/z/1/tile-specs")
b = pr.cached_json("stack_metadata.json", "")["stats"]["stackBounds"]
boxes = pr.tile_boxes(tiles, (b["minX"], b["minY"]))
img = np.zeros((2377, 2972), np.uint8)
for _, row, x0, y0, x1, y1 in boxes:
    yy = (row + (np.arange(y1 - y0) + 0.5) / (y1 - y0)) / 11
    t = img[y0:y1, x0:x1]; t[:] = (120 + 15 * yy)[:t.shape[0], None].round()
p = pr.band_profile(img, boxes)
y = (np.arange(11 * pr.BANDS) + 0.5) / (11 * pr.BANDS)
slope = np.polyfit(y, p, 1)[0]
print("recovered slope", slope, "max residual", np.abs(p - np.polyval(np.polyfit(y, p, 1), y)).max())
assert abs(slope - 15) < 1.5, slope
