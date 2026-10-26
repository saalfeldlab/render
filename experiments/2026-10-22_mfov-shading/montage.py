"""Usage: montage.py <out.png> <ncols> <stack> ...  — labeled grid of renders_120_200/<stack>.png."""
import sys
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

out, ncols, stacks = sys.argv[1], int(sys.argv[2]), sys.argv[3:]
nrows = -(-len(stacks) // ncols)
fig, axes = plt.subplots(nrows, ncols, figsize=(6 * ncols, 4.8 * nrows), squeeze=False)
for ax in axes.flat:
    ax.axis("off")
for ax, stack in zip(axes.flat, stacks):
    ax.imshow(plt.imread(f"renders_120_200/{stack}.png"), cmap="gray", vmin=0, vmax=1)
    ax.set_title(stack.split("_ic_")[-1] if "_ic_" in stack else "raw", fontsize=16)
fig.tight_layout()
fig.savefig(out, dpi=80)
