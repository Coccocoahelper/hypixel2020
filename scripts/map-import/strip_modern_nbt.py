"""Make a converted 1.12-format world loadable by Spigot 1.8.8.

Amulet writes legacy Blocks/Data arrays but also leaves modern TAG_Long_Array entries (e.g. section BlockStates),
which the 1.8 NBT reader cannot parse (NullPointerException in NBTTagCompound.load). This rewrites every region
file, removing all long-array tags (they are never needed by 1.8).

Usage: python strip_modern_nbt.py <world dir>
"""
import glob
import os
import struct
import sys
import zlib

import amulet_nbt


def strip(tag):
    removed = 0
    name = type(tag).__name__
    if name == "CompoundTag":
        for k in list(tag.keys()):
            v = tag[k]
            if type(v).__name__ == "LongArrayTag":
                del tag[k]
                removed += 1
            else:
                removed += strip(v)
    elif name == "ListTag":
        for v in tag:
            removed += strip(v)
    return removed


# 1.13+ namespaced ids -> 1.8 ids (anything not listed is dropped: 1.8 crashes on unknown entity ids)
ENTITY_IDS = {
    "minecraft:armor_stand": "ArmorStand", "minecraft:item_frame": "ItemFrame", "minecraft:painting": "Painting",
    "minecraft:villager": "Villager", "minecraft:leash_knot": "LeashKnot",
}
TILE_IDS = {
    "minecraft:chest": "Chest", "minecraft:trapped_chest": "Chest", "minecraft:ender_chest": "EnderChest",
    "minecraft:sign": "Sign", "minecraft:skull": "Skull", "minecraft:banner": "Banner",
    "minecraft:furnace": "Furnace", "minecraft:flower_pot": "FlowerPot", "minecraft:beacon": "Beacon",
    "minecraft:enchanting_table": "EnchantTable", "minecraft:brewing_stand": "Cauldron",
    "minecraft:dispenser": "Trap", "minecraft:dropper": "Dropper", "minecraft:hopper": "Hopper",
    "minecraft:jukebox": "RecordPlayer", "minecraft:noteblock": "Music", "minecraft:mob_spawner": "MobSpawner",
    "minecraft:daylight_detector": "DLDetector", "minecraft:comparator": "Comparator",
    "minecraft:end_portal": "Airportal",
}


def legacy_ids(level):
    """Rename/drop entities and tile entities so 1.8 can load them; ensure the arrays 1.8 requires exist."""
    fixed = 0
    if "Entities" in level:
        keep = amulet_nbt.ListTag()
        for ent in level["Entities"]:
            eid = str(ent.get("id", amulet_nbt.StringTag("")).py_str) if "id" in ent else ""
            new = ENTITY_IDS.get(eid, eid if ":" not in eid else None)
            if new:
                ent["id"] = amulet_nbt.StringTag(new)
                keep.append(ent)
            fixed += 1
        level["Entities"] = keep
    if "TileEntities" in level:
        keep = amulet_nbt.ListTag()
        for te in level["TileEntities"]:
            tid = str(te["id"].py_str) if "id" in te else ""
            new = TILE_IDS.get(tid, tid if ":" not in tid else None)
            if new:
                te["id"] = amulet_nbt.StringTag(new)
                keep.append(te)
            fixed += 1
        level["TileEntities"] = keep
    # scheduled block ticks reference modern block names (null in 1.8 -> NPE in WorldServer); a static map needs none
    if "TileTicks" in level:
        del level["TileTicks"]
        fixed += 1
    if "Sections" not in level:
        level["Sections"] = amulet_nbt.ListTag()
        fixed += 1
    if "HeightMap" not in level or len(level["HeightMap"]) != 256:
        level["HeightMap"] = amulet_nbt.IntArrayTag([0] * 256)
        fixed += 1
    return fixed


def rewrite_region(path):
    data = open(path, "rb").read()
    if len(data) < 8192:
        return 0
    chunks = {}
    timestamps = data[4096:8192]
    removed = 0
    for i in range(1024):
        off = struct.unpack(">I", b"\0" + data[i * 4:i * 4 + 3])[0]
        if not off:
            continue
        o = off * 4096
        ln = struct.unpack(">I", data[o:o + 4])[0]
        comp = data[o + 4]
        raw = data[o + 5:o + 4 + ln]
        nbt = amulet_nbt.load(zlib.decompress(raw))
        removed += strip(nbt.compound)
        if "Level" in nbt.compound:
            removed += legacy_ids(nbt.compound["Level"])
        chunks[i] = zlib.compress(nbt.save_to(compressed=False, little_endian=False))
    header = bytearray(4096)
    body = bytearray()
    sector = 2
    for i in sorted(chunks):
        payload = struct.pack(">I", len(chunks[i]) + 1) + b"\x02" + chunks[i]
        pad = (-len(payload)) % 4096
        payload += b"\0" * pad
        n = len(payload) // 4096
        header[i * 4:i * 4 + 4] = struct.pack(">I", (sector << 8) | n)
        body += payload
        sector += n
    with open(path, "wb") as f:
        f.write(bytes(header) + timestamps + bytes(body))
    return removed


def main(world):
    total = 0
    for path in glob.glob(os.path.join(world, "region", "*.mca")):
        total += rewrite_region(path)
    print(f"fixed {total} tags/ids in {world}")


if __name__ == "__main__":
    main(sys.argv[1])
