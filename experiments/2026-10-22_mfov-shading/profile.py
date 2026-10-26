"""Quantify MFOV shading in layer renders produced by render.sh.

For each stack, every tile footprint in renders/<stack>.png is split into 4 horizontal bands (the intensity correction's
coefficient rows), and band means are averaged over all tiles at the same height within their MFOV (11 rows x 4 bands).
Also computes the per-pixel median over all MFOVs, which suppresses tissue and leaves the common shading pattern.

Usage: python profile.py <stack> [<stack> ...]
Writes profiles.png, profiles.csv and mfov_medians.png and prints top-minus-bottom differences and fitted slopes.
"""
import json
import os
import sys
import urllib.request

import numpy as np
from PIL import Image

os.environ.setdefault("MPLCONFIGDIR", os.environ.get("TMPDIR", "/tmp"))
import matplotlib  # noqa: E402

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

BASE_URL = "http://em-services-1.int.janelia.org:8080/render-ws/v1"
OWNER, PROJECT, SOURCE_STACK = "hess_wafers_60_61", "test_ic2d", "w61_s109_r00_gc_z1"
SCALE = 0.023  # must match render.sh
BANDS = 4
ROW_SIZES = [6, 7, 8, 9, 10, 11, 10, 9, 8, 7, 6]
ROW_STARTS = np.cumsum([0] + ROW_SIZES)
EXP_DIR = os.path.dirname(os.path.abspath(__file__))


def cached_json(name, path):
    """Fetch json from the render web service once and cache it next to this script."""
    file = os.path.join(EXP_DIR, name)
    if not os.path.exists(file):
        with urllib.request.urlopen(f"{BASE_URL}/owner/{OWNER}/project/{PROJECT}/stack/{SOURCE_STACK}{path}") as r:
            with open(file, "wb") as f:
                f.write(r.read())
    with open(file) as f:
        return json.load(f)


def mfov_row(tile_id):
    """0-based row (0 = top) of the tile within its MFOV, from the r-number (row-major render order)."""
    render_index = int(tile_id.split("_")[4][1:]) - 1
    return int(np.searchsorted(ROW_STARTS, render_index, side="right")) - 1


def tile_boxes(tiles, origin):
    """Tile bounding boxes in render pixel coordinates as (mfov, row, x0, y0, x1, y1)."""
    boxes = []
    for t in tiles:
        x0, y0 = [int(round((t[k] - o) * SCALE)) for k, o in zip(("minX", "minY"), origin)]
        x1, y1 = [int(round((t[k] - o) * SCALE)) for k, o in zip(("maxX", "maxY"), origin)]
        boxes.append((t["tileId"].split("_")[3], mfov_row(t["tileId"]), x0, y0, x1, y1))
    return boxes


def band_profile(image, boxes):
    """Mean intensity for each of the 11 * BANDS heights within an MFOV, averaged over all tiles at that height.
    Only pixels covered by a single tile are used, since overlaps show whichever tile was rendered last."""
    coverage = np.zeros(image.shape, int)
    for _, _, x0, y0, x1, y1 in boxes:
        coverage[y0:y1, x0:x1] += 1
    single = (coverage == 1) & (image > 0)  # 0 = outside of all tiles
    sums, counts = np.zeros(11 * BANDS), np.zeros(11 * BANDS)
    for _, row, x0, y0, x1, y1 in boxes:
        edges = np.linspace(y0, y1, BANDS + 1).round().astype(int)
        for b in range(BANDS):
            band = image[edges[b]:edges[b + 1], x0:x1][single[edges[b]:edges[b + 1], x0:x1]]
            if band.size:
                sums[row * BANDS + b] += band.mean()
                counts[row * BANDS + b] += 1
    return sums / counts


def mfov_median(image, boxes):
    """Per-pixel median over all MFOVs, each cropped at its bounding box (cropped to the smallest MFOV)."""
    crops = []
    for mfov in sorted({b[0] for b in boxes}):
        mb = np.array([b[2:] for b in boxes if b[0] == mfov])
        x0, y0 = mb[:, :2].min(axis=0)
        x1, y1 = mb[:, 2:].max(axis=0)
        crops.append(image[y0:y1, x0:x1].astype(float))
    h = min(c.shape[0] for c in crops)
    w = min(c.shape[1] for c in crops)
    stack = np.stack([c[:h, :w] for c in crops])
    stack[stack == 0] = np.nan
    return np.nanmedian(stack, axis=0)


def main(stacks):
    tiles = cached_json("tile_specs.json", "/z/1/tile-specs")
    bounds = cached_json("stack_metadata.json", "")["stats"]["stackBounds"]
    boxes = tile_boxes(tiles, (bounds["minX"], bounds["minY"]))
    y = (np.arange(11 * BANDS) + 0.5) / (11 * BANDS)  # height within MFOV, 0 = top, 1 = bottom

    profiles, medians = {}, {}
    for stack in stacks:
        image = np.asarray(Image.open(os.path.join(EXP_DIR, "renders", f"{stack}.png")).convert("L"))
        profiles[stack] = band_profile(image, boxes)
        medians[stack] = mfov_median(image, boxes)

    print(f"{'stack':45s} {'top-bottom':>10s} {'slope':>8s} {'p2p':>6s}  (gray levels; slope per MFOV height)")
    for stack, p in profiles.items():
        top_minus_bottom = p[:2 * BANDS].mean() - p[-2 * BANDS:].mean()
        slope = np.polyfit(y, p, 1)[0]
        print(f"{stack:45s} {top_minus_bottom:10.2f} {slope:8.2f} {np.ptp(p):6.2f}")

    np.savetxt(os.path.join(EXP_DIR, "profiles.csv"), np.column_stack([y] + list(profiles.values())),
               delimiter=",", header=",".join(["y"] + list(profiles)), comments="")

    fig, ax = plt.subplots(figsize=(8, 5))
    for stack, p in profiles.items():
        ax.plot(y, p - p.mean(), label=stack, marker=".")
    for k in range(1, 11):  # MFOV row boundaries
        ax.axvline(k / 11, color="0.9", zorder=0)
    ax.set(xlabel="height within MFOV (0 = top row, 1 = bottom row)", ylabel="mean intensity - layer mean",
           title="MFOV shading profile")
    ax.legend(fontsize=7)
    fig.tight_layout()
    fig.savefig(os.path.join(EXP_DIR, "profiles.png"), dpi=150)

    n = len(medians)
    fig, axes = plt.subplots(1, n, figsize=(4 * n, 4), squeeze=False)
    for ax, (stack, m) in zip(axes[0], medians.items()):
        im = ax.imshow(m - np.nanmean(m), cmap="RdBu_r", vmin=-15, vmax=15)
        ax.set_title(stack, fontsize=7)
        ax.axis("off")
    fig.colorbar(im, ax=axes[0].tolist(), shrink=0.8, label="median over MFOVs - mean")
    fig.savefig(os.path.join(EXP_DIR, "mfov_medians.png"), dpi=150)


if __name__ == "__main__":
    main(sys.argv[1:])
