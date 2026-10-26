"""Scan a (modern) world for map landmarks: beds, diamond/emerald blocks, chests, and the solid-column footprint.

Usage: python scan_map.py <world dir> [out.json]
Writes JSON with block positions (world coordinates) and a 2D occupancy grid used by annotate_map.py.
"""
import json
import sys
from collections import defaultdict

import amulet

LANDMARKS = ("bed", "diamond_block", "emerald_block", "chest", "trapped_chest", "iron_block", "gold_block", "ladder")


def main(path, out):
    level = amulet.load_level(path)
    dim = "minecraft:overworld" if "minecraft:overworld" in level.dimensions else level.dimensions[0]
    found = defaultdict(list)
    top = {}  # (x,z) -> highest non-air y
    for cx, cz in level.all_chunk_coords(dim):
        try:
            chunk = level.get_chunk(cx, cz, dim)
        except Exception:
            continue
        palette = chunk.block_palette
        for cy in chunk.blocks.sub_chunks:
            arr = chunk.blocks.get_sub_chunk(cy)
            ids = set(int(i) for i in set(arr.ravel().tolist()))
            interesting = {}
            for i in ids:
                b = palette[i]
                name = b.base_name
                if name == "air" or name == "cave_air" or name == "void_air":
                    continue
                interesting[i] = (name, b.properties)
            if not interesting:
                continue
            import numpy as np
            for i, (name, props) in interesting.items():
                xs, ys, zs = np.nonzero(arr == i)
                for x, y, z in zip(xs.tolist(), ys.tolist(), zs.tolist()):
                    wx, wy, wz = cx * 16 + x, cy * 16 + y, cz * 16 + z
                    k = (wx, wz)
                    if top.get(k, -999) < wy:
                        top[k] = wy
                    if any(name.endswith(l) for l in LANDMARKS):
                        found[name].append({"x": wx, "y": wy, "z": wz,
                                            "props": {pk: str(pv) for pk, pv in props.items()}})
    level.close()
    data = {"landmarks": found, "top": [[x, z, y] for (x, z), y in top.items()]}
    with open(out, "w") as f:
        json.dump(data, f)
    for k, v in sorted(found.items()):
        print(f"{k}: {len(v)}")
    xs = [t[0] for t in data["top"]]
    zs = [t[1] for t in data["top"]]
    print("columns:", len(xs), "x", min(xs), max(xs), "z", min(zs), max(zs))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "scan.json")
