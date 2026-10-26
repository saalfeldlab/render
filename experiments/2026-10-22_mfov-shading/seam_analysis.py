"""Analyze the minimal seam example downloaded by fetch_seam.sh.

Usage: python seam_analysis.py <stack> [<stack> ...]
For each stack, writes seam/<stack>_mosaic.png (rescaled to [120, 200], later tiles drawn on top),
seam/<stack>_profiles.png (per-tile row means over world y) and seam/<stack>_overlaps.csv (mean intensity of both
tiles in each pairwise overlap).
"""
import itertools
import json
import os
import sys

import numpy as np
from PIL import Image

os.environ.setdefault("MPLCONFIGDIR", os.environ.get("TMPDIR", "/tmp"))
import matplotlib  # noqa: E402

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

from profile import mfov_row  # noqa: E402

EXP_DIR = os.path.dirname(os.path.abspath(__file__))
SPECS = {t["tileId"]: t for t in json.load(open(os.path.join(EXP_DIR, "tile_specs.json")))}


def load_tiles(stack):
    """Gray image, coverage mask and world offset of each tile, keyed by (mfov, row, tileId)."""
    tiles = {}
    folder = os.path.join(EXP_DIR, "seam", stack)
    for name in sorted(os.listdir(folder)):
        tile_id = name[:-4]
        rgba = np.array(Image.open(os.path.join(folder, name)))
        t = SPECS[tile_id]
        key = (tile_id.split("_")[3], mfov_row(tile_id), tile_id)
        tiles[key] = (rgba[..., 0].astype(float), rgba[..., 3] > 0, int(t["minX"]), int(t["minY"]))
    return tiles


def overlaps(tiles):
    """Rows (keyA, keyB, n, meanA, meanB, dx, dy) for every pair of tiles that overlap in at least 10k pixels."""
    rows = []
    for a, b in itertools.combinations(sorted(tiles), 2):
        ia, ma, xa, ya = tiles[a]
        ib, mb, xb, yb = tiles[b]
        x0, y0 = max(xa, xb), max(ya, yb)
        x1, y1 = min(xa + ia.shape[1], xb + ib.shape[1]), min(ya + ia.shape[0], yb + ib.shape[0])
        if x1 <= x0 or y1 <= y0:
            continue
        sa = (slice(y0 - ya, y1 - ya), slice(x0 - xa, x1 - xa))
        sb = (slice(y0 - yb, y1 - yb), slice(x0 - xb, x1 - xb))
        both = ma[sa] & mb[sb]
        if both.sum() < 10000:
            continue
        rows.append((a, b, both.sum(), ia[sa][both].mean(), ib[sb][both].mean(), xb - xa, yb - ya))
    return rows


def mosaic(tiles, scale=0.1):
    """Downsampled mosaic of all tiles, in the order of the keys (so m0015 overwrites m0014)."""
    x0 = min(t[2] for t in tiles.values())
    y0 = min(t[3] for t in tiles.values())
    x1 = max(t[2] + t[0].shape[1] for t in tiles.values())
    y1 = max(t[3] + t[0].shape[0] for t in tiles.values())
    out = np.zeros((int((y1 - y0) * scale) + 1, int((x1 - x0) * scale) + 1))
    for key in sorted(tiles):
        image, mask, x, y = tiles[key]
        h, w = int(image.shape[0] * scale), int(image.shape[1] * scale)
        small = np.array(Image.fromarray(image).resize((w, h), Image.BOX))
        small_mask = np.array(Image.fromarray(mask.astype(np.uint8) * 255).resize((w, h), Image.BOX)) == 255
        oy, ox = int((y - y0) * scale), int((x - x0) * scale)
        out[oy:oy + h, ox:ox + w][small_mask] = small[small_mask]
    return out


def main(stacks):
    for stack in stacks:
        tiles = load_tiles(stack)
        image = np.clip((mosaic(tiles) - 120) / 80 * 255, 0, 255).astype(np.uint8)
        Image.fromarray(image).save(os.path.join(EXP_DIR, "seam", f"{stack}_mosaic.png"))

        rows = overlaps(tiles)
        with open(os.path.join(EXP_DIR, "seam", f"{stack}_overlaps.csv"), "w") as f:
            f.write("tileA,mfovA,rowA,tileB,mfovB,rowB,pixels,meanA,meanB,BminusA,dx,dy\n")
            for a, b, n, mean_a, mean_b, dx, dy in rows:
                f.write(f"{a[2]},{a[0]},{a[1]},{b[2]},{b[0]},{b[1]},{n},{mean_a:.2f},{mean_b:.2f},"
                        f"{mean_b - mean_a:.2f},{dx},{dy}\n")
        print(f"\n{stack}: mean B-A in overlaps (B is lower in the image for vertical pairs)")
        for label, pick in [("m0014 r9 -> r10", lambda a, b: a[0] == b[0] == "m0014"),
                            ("m0015 r0 -> r1", lambda a, b: a[0] == b[0] == "m0015"),
                            ("m0014 -> m0015", lambda a, b: a[0] != b[0])]:
            d = [r[4] - r[3] for r in rows if pick(r[0], r[1]) and abs(r[6]) > 1000]
            if d:
                print(f"  {label:16s} vertical pairs {len(d):2d}: mean {np.mean(d):+.2f}  sd {np.std(d):.2f}")

        fig, ax = plt.subplots(figsize=(12, 6))
        colors = {("m0014", 9): "C0", ("m0014", 10): "C1", ("m0015", 0): "C2", ("m0015", 1): "C3"}
        for (mfov, row, _), (img, mask, x, y) in sorted(tiles.items()):
            counts = mask.sum(axis=1)
            profile = np.where(counts > 0, (img * mask).sum(axis=1) / np.maximum(counts, 1), np.nan)
            smooth = np.convolve(np.nan_to_num(profile), np.ones(25) / 25, mode="same")
            ax.plot(y + np.arange(len(profile)), smooth, color=colors[(mfov, row)], lw=0.6)
        for (mfov, row), c in colors.items():
            ax.plot([], [], color=c, label=f"{mfov} row {row}")
        ax.set_xlabel("world y")
        ax.set_ylabel("row mean")
        ax.set_title(stack)
        ax.legend()
        fig.savefig(os.path.join(EXP_DIR, "seam", f"{stack}_profiles.png"), dpi=120)
        plt.close(fig)


if __name__ == "__main__":
    main(sys.argv[1:])
