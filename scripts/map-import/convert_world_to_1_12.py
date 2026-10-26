"""Convert a modern Java world (1.13+) to the pre-flattening Anvil format (1.12.2) that Spigot 1.8.8 can load.

Usage: python convert_world_to_1_12.py <input world dir> <output world dir>
Blocks that don't exist in 1.12 are translated by Amulet/PyMCTranslate to the closest legacy block (or air).
Run with the venv in .tools/amulet-venv.
"""
import os
import shutil
import sys

import amulet
from amulet.level.formats.anvil_world import AnvilFormat

JAVA_1_12_2_DATA_VERSION = 1343


def main(src, dst):
    if os.path.exists(dst):
        shutil.rmtree(dst)
    level = amulet.load_level(src)
    out = AnvilFormat(dst)
    out.create_and_open("java", JAVA_1_12_2_DATA_VERSION, overwrite=True)
    out.close()
    dims = level.dimensions
    print("dimensions:", dims)
    if "minecraft:overworld" in dims:
        out = AnvilFormat(dst)
        out.open()
        try:
            level.save(out)
        finally:
            out.close()
            level.close()
    else:
        # Schematics (.schem/.schematic) have a single "main" dimension: copy it into the overworld.
        target = amulet.load_level(dst)
        try:
            src_dim = dims[0]
            for cx, cz in level.all_chunk_coords(src_dim):
                chunk = level.get_chunk(cx, cz, src_dim)
                target.put_chunk(chunk, "minecraft:overworld")
            target.save()
        finally:
            target.close()
            level.close()
    print("converted", src, "->", dst)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
