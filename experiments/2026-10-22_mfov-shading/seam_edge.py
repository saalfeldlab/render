"""Profile of the m0015 row 1 tiles across the m0014 coverage boundary (signed distance, positive = double exposed),
and quantiles inside vs. outside to tell additive from multiplicative.

Usage: python seam_edge.py <stack>   (writes seam/<stack>_edge.png)
"""
import os
import sys

import numpy as np
from scipy import ndimage

from seam_analysis import EXP_DIR, load_tiles, plt
from seam_step import coverage

stack = sys.argv[1]
tiles = load_tiles(stack)
x0 = min(t[2] for t in tiles.values())
y0 = min(t[3] for t in tiles.values())
shape = (max(t[3] + t[0].shape[0] for t in tiles.values()) - y0, max(t[2] + t[0].shape[1] for t in tiles.values()) - x0)
covered = coverage(tiles, "m0014", shape, (x0, y0))
signed = ndimage.distance_transform_edt(covered) - ndimage.distance_transform_edt(~covered)

values, distances = [], []
for (mfov, row, _), (image, mask, x, y) in tiles.items():
    if (mfov, row) == ("m0015", 1):
        d = signed[y - y0:y - y0 + image.shape[0], x - x0:x - x0 + image.shape[1]]
        values.append(image[mask])
        distances.append(d[mask])
values, distances = np.concatenate(values), np.concatenate(distances)
bins = np.arange(-600, 400, 10)
idx = np.digitize(distances, bins)
means = [values[idx == i].mean() for i in range(1, len(bins))]
fig, ax = plt.subplots(figsize=(8, 4))
ax.plot(bins[:-1] + 5, means)
ax.axvline(0, color="k", lw=0.5)
ax.set_xlabel("signed distance to m0014 coverage boundary (px, > 0 = double exposed)")
ax.set_ylabel("mean intensity, m0015 row 1")
ax.set_title(stack)
fig.savefig(os.path.join(EXP_DIR, "seam", f"{stack}_edge.png"), dpi=120)

inside = values[(distances > 20) & (distances < 300)]
outside = values[(distances < -20) & (distances > -300)]
print("quantile  inside  outside  diff  ratio")
for q in (5, 25, 50, 75, 95):
    a, b = np.percentile(inside, q), np.percentile(outside, q)
    print(f"{q:8d}  {a:6.1f}  {b:7.1f}  {a - b:+5.1f}  {a / b:.3f}")
