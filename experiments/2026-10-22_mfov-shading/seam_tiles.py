"""Print tile ids of the minimal seam example: the bottom 2 rows of m0014 and the top 2 rows of m0015."""
import json
import os

from profile import mfov_row

EXP_DIR = os.path.dirname(os.path.abspath(__file__))
SEAM = {"m0014": (9, 10), "m0015": (0, 1)}

if __name__ == "__main__":
    for t in json.load(open(os.path.join(EXP_DIR, "tile_specs.json"))):
        mfov = t["tileId"].split("_")[3]
        if mfov_row(t["tileId"]) in SEAM.get(mfov, ()):
            print(t["tileId"])
