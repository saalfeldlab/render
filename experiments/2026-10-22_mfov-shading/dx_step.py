"""Step across the boundary of the double-exposed area: mean of renders/<stack>.png just inside minus just outside.

The double-exposed area is taken from where renders/<source>_dx3.png is darker than renders/<source>.png.
Usage: python dx_step.py <stack> [<stack> ...]
"""
import os
import sys

import numpy as np
from PIL import Image
from scipy import ndimage

from profile import EXP_DIR, SOURCE_STACK


def load(stack):
    return np.asarray(Image.open(os.path.join(EXP_DIR, "renders", f"{stack}.png")).convert("L")).astype(float)


raw = load(SOURCE_STACK)
exposed = load(f"{SOURCE_STACK}_dx3") - raw <= -2
inside_distance = ndimage.distance_transform_edt(exposed)
outside_distance = ndimage.distance_transform_edt(~exposed)

print(f"{'stack':45s} {'step 5px':>9s} {'step 15px':>10s}  (inside - outside of double-exposed area)")
for stack in sys.argv[1:]:
    image = load(stack)
    steps = []
    for width in (5, 15):
        inside = exposed & (inside_distance <= width) & (raw > 0)
        outside = ~exposed & (outside_distance <= width) & (raw > 0)
        steps.append(image[inside].mean() - image[outside].mean())
    print(f"{stack:45s} {steps[0]:9.2f} {steps[1]:10.2f}")
