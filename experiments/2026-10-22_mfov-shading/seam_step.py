"""Measure the intensity step inside each tile at the boundary of the area covered by the other MFOV.

Usage: python seam_step.py <stack> [<stack> ...]
For every tile, pixels within 150 px of the other MFOV's coverage boundary (excluding 20 px at the boundary) are split
into inside (covered by the other MFOV, i.e. double exposed) and outside; prints inside minus outside per tile.
"""
import sys

import numpy as np
from scipy import ndimage

from seam_analysis import load_tiles

NEAR, GAP = 150, 20


def coverage(tiles, mfov, shape, origin):
    """World-space coverage mask of all tiles of the given MFOV."""
    mask = np.zeros(shape, bool)
    for (m, _, _), (_, tile_mask, x, y) in tiles.items():
        if m == mfov:
            mask[y - origin[1]:y - origin[1] + tile_mask.shape[0], x - origin[0]:x - origin[0] + tile_mask.shape[1]] |= tile_mask
    return mask


def main(stacks):
    for stack in stacks:
        tiles = load_tiles(stack)
        x0 = min(t[2] for t in tiles.values())
        y0 = min(t[3] for t in tiles.values())
        shape = (max(t[3] + t[0].shape[0] for t in tiles.values()) - y0,
                 max(t[2] + t[0].shape[1] for t in tiles.values()) - x0)
        covered = {m: coverage(tiles, m, shape, (x0, y0)) for m in ("m0014", "m0015")}
        dist_in = {m: ndimage.distance_transform_edt(c) for m, c in covered.items()}
        dist_out = {m: ndimage.distance_transform_edt(~c) for m, c in covered.items()}
        print(f"\n{stack}: inside (double exposed) minus outside, within {NEAR} px of the other MFOV's boundary")
        steps = {}
        for (mfov, row, tile_id), (image, mask, x, y) in sorted(tiles.items()):
            other = "m0015" if mfov == "m0014" else "m0014"
            window = (slice(y - y0, y - y0 + image.shape[0]), slice(x - x0, x - x0 + image.shape[1]))
            d_in, d_out = dist_in[other][window], dist_out[other][window]
            inside = mask & (d_in > GAP) & (d_in < NEAR)
            outside = mask & (d_out > GAP) & (d_out < NEAR)
            if inside.sum() > 20000 and outside.sum() > 20000:
                step = image[inside].mean() - image[outside].mean()
                steps.setdefault((mfov, row), []).append(step)
                print(f"  {tile_id}  {step:+6.2f}")
        for key, s in steps.items():
            print(f"  {key[0]} row {key[1]}: mean {np.mean(s):+.2f} (n={len(s)})")


if __name__ == "__main__":
    main(sys.argv[1:])
