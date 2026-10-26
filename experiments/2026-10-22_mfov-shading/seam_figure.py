"""Figure for the seam example: one m0015 row 1 tile (raw and base IC, [120, 200]) with the m0014 coverage boundary,
next to the raw edge profile. Writes seam/seam_figure.png."""
import os

import numpy as np
from PIL import Image

from seam_analysis import EXP_DIR, SPECS, plt

TILE = "w61_magc0145_scan004_m0015_r10_s44"
STACKS = ["w61_s109_r00_gc_z1", "w61_s109_r00_gc_z1_ic_base"]
m0014_bottom = max(t["maxY"] for t in SPECS.values() if t["tileId"].split("_")[3] == "m0014"
                   and abs(t["minX"] - SPECS[TILE]["minX"]) < 1500)

fig, axes = plt.subplots(1, 3, figsize=(18, 5), gridspec_kw={"width_ratios": [1, 1, 1.2]})
for ax, stack in zip(axes, STACKS):
    image = np.array(Image.open(os.path.join(EXP_DIR, "seam", stack, TILE + ".png")))[..., 0].astype(float)
    ax.imshow(image, cmap="gray", vmin=120, vmax=200)
    ax.axhline(m0014_bottom - SPECS[TILE]["minY"], color="r", lw=0.8, ls="--")
    ax.set_title(f"{TILE}\n{stack.split('z1')[-1] or 'raw'}; red = bottom edge of m0014")
    ax.axis("off")
axes[2].imshow(Image.open(os.path.join(EXP_DIR, "seam", STACKS[0] + "_edge.png")))
axes[2].axis("off")
fig.tight_layout()
fig.savefig(os.path.join(EXP_DIR, "seam", "seam_figure.png"), dpi=110)
