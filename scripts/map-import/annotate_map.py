"""Turn a converted community map into a Hypixel2020 map template (world + map.yml).

Usage:
  python annotate_map.py bedwars <scan.json> <converted world dir> <out template dir> "<Map Name>"
  python annotate_map.py skywars <scan.json> <converted world dir> <out template dir> "<Map Name>"

Landmarks come from scan_map.py. Heuristics (Hypixel layouts):
- Bed Wars: one team per bed; team colours assigned clockwise from north in Hypixel order. The team chest + ender
  chest flank the spawn at the back of the island; the forge is just behind them. Shop/upgrades NPCs go in the
  remake's barrier-capped villager pens when it has them (shop right, upgrades left, facing mid), else symmetric
  either side of the spawn along the island's own (bed) axis; see npc_spots. Generator markers are isolated diamond/emerald blocks (decorative clusters are ignored).
- SkyWars: islands are connected footprints holding chests; the one nearest the centre is mid; the cage goes above
  the island's highest column, offset toward the map centre.
A glass waiting lobby is built high above the map centre (Hypixel style) and stored as waiting.lobby.
Print-out lists every derived point so it can be checked; fix anything wrong by editing map.yml.
"""
import json
import math
import os
import shutil
import sys
from collections import deque

TEAM_ORDER = ["red", "blue", "green", "yellow", "aqua", "white", "pink", "gray"]


class Blocks:
    """Cached block-name lookups in the source (modern) world, for floor/standability checks."""
    PASSABLE = ("air", "cave_air", "void_air", "grass", "tall_grass", "fern", "flower", "torch", "carpet", "sign",
                "rail", "button", "pressure_plate", "snow", "vine", "banner", "dandelion", "poppy", "tulip",
                "orchid", "allium", "bluet", "daisy", "lever", "redstone_wire", "sapling")

    def __init__(self, path):
        import amulet
        self.level = amulet.load_level(path)
        self.dim = "minecraft:overworld" if "minecraft:overworld" in self.level.dimensions else self.level.dimensions[0]
        self.cache = {}

    def name(self, x, y, z):
        k = (x, y, z)
        if k not in self.cache:
            try:
                self.cache[k] = self.level.get_block(x, y, z, self.dim).base_name
            except Exception:
                self.cache[k] = "air"
        return self.cache[k]

    def bottom_slab(self, x, y, z):
        """A lower-half slab (forge pits are floored with them)."""
        if not self.name(x, y, z).endswith("slab"):
            return False
        try:
            p = self.level.get_block(x, y, z, self.dim).properties
        except Exception:
            return False
        return str(p.get("type", p.get("half", ""))).strip('"') == "bottom"

    def passable(self, x, y, z):
        n = self.name(x, y, z)
        return any(n == p or n.endswith(p) for p in self.PASSABLE)

    def floor(self, x, z, y):
        """Standing y near y (feet), or None: solid below, two passable blocks for the body."""
        for dy in (0, 1, -1):
            yy = y + dy
            if not self.passable(x, yy - 1, z) and self.passable(x, yy, z) and self.passable(x, yy + 1, z):
                return yy
        return None

    def close(self):
        self.level.close()


B = None


def load(scan):
    d = json.load(open(scan))
    top = {(x, z): y for x, z, y in d["top"]}
    return d["landmarks"], top


def isolated(blocks, radius=2):
    out = []
    for b in blocks:
        n = sum(1 for o in blocks if o is not b and abs(o["x"] - b["x"]) <= radius
                and abs(o["y"] - b["y"]) <= radius and abs(o["z"] - b["z"]) <= radius)
        if n == 0:
            out.append(b)
    return out


def component(top, start, maxr=40, maxdy=12):
    """Connected footprint (8-neighbour) around start column, limited to maxr and to columns near start height."""
    sx, sz = start
    sy = top.get(start)
    if sy is None:
        return set()
    seen = {start}
    q = deque([start])
    while q:
        x, z = q.popleft()
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                n = (x + dx, z + dz)
                if n in seen or n not in top:
                    continue
                if abs(n[0] - sx) > maxr or abs(n[1] - sz) > maxr or abs(top[n] - sy) > maxdy:
                    continue
                seen.add(n)
                q.append(n)
    return seen


def angle_cw_from_north(dx, dz):
    # north = -z; clockwise = toward +x (east)
    return (math.degrees(math.atan2(dx, -dz)) + 360.0) % 360.0


def yaw_towards(fx, fz, tx, tz):
    # Minecraft yaw: 0 = +z (south), 90 = -x (west)
    return round((math.degrees(math.atan2(-(tx - fx), tz - fz)) + 360.0) % 360.0 - 0.0, 1)


def pt(x, y, z, yaw=None):
    p = {"x": round(x, 3), "y": round(y, 3), "z": round(z, 3)}
    if yaw:
        p["yaw"] = yaw
    return p


def walk_to_edge(top, x, z, dx, dz, floor_y, limit=14):
    """Step from (x,z) along (dx,dz) while there is a standable floor near floor_y (feet y = floor_y + 1).
    Walls stop the walk too. Returns the last standable column."""
    last = (int(math.floor(x)), int(math.floor(z)))
    feet = floor_y + 1
    for i in range(1, limit + 1):
        c = (int(math.floor(x + dx * i)), int(math.floor(z + dz * i)))
        f = B.floor(c[0], c[1], feet)
        if f is None:
            break
        feet = f
        last = c
    return last


def island_frame(foot, head, centre):
    """(forward, right) unit vectors of a team island. The island's own axis is the bed axis (the bed lies along it,
    on the island centreline), NOT the radial direction from the map centre: on 8-team maps the islands sit at an
    angle to the centre but are still built axis-aligned. forward points toward mid, right is the player's right
    when facing mid (Minecraft: x east, z south)."""
    ax, az = head["x"] - foot["x"], head["z"] - foot["z"]
    if ax == 0 and az == 0:
        ax, az = centre[0] - foot["x"], centre[1] - foot["z"]
    if ax * (centre[0] - foot["x"]) + az * (centre[1] - foot["z"]) < 0:
        ax, az = -ax, -az
    al = math.hypot(ax, az) or 1.0
    fx, fz = ax / al, az / al
    return (fx, fz), (-fz, fx)


def find_pens(sx, sz, floor, r=16):
    """NPC pens near a spawn: remakes mark villager spots with a barrier block capping a 2-high cell (usually over a
    sea lantern / lit glass floor). Returns pens as lists of feet cells (x, y, z), 4-connected at the same y."""
    cells = set()
    for x in range(int(sx) - r, int(sx) + r + 1):
        for z in range(int(sz) - r, int(sz) + r + 1):
            for y in range(floor - 5, floor + 8):
                if B.name(x, y, z) == "barrier" and B.passable(x, y - 1, z) and B.passable(x, y - 2, z) \
                        and not B.passable(x, y - 3, z):
                    cells.add((x, y - 2, z))
    pens, seen = [], set()
    for c in sorted(cells):
        if c in seen:
            continue
        pen, q = [], deque([c])
        seen.add(c)
        while q:
            x, y, z = q.popleft()
            pen.append((x, y, z))
            for n in ((x + 1, y, z), (x - 1, y, z), (x, y, z + 1), (x, y, z - 1)):
                if n in cells and n not in seen:
                    seen.add(n)
                    q.append(n)
        if len(pen) <= 6:  # bigger barrier roofs are build-limit caps / decoration, not villager pens
            pens.append(pen)
    return pens


def npc_spots(foot, head, spawn, centre, other_feet):
    """Item shop + team upgrades NPC standing points (block centre, feet y, yaw toward the spawn).

    1. Marked pens (see find_pens) owned by this island: two pens -> shop in the one on the right (facing mid),
       upgrades on the left, each in the pen cell nearest the spawn; one long pen -> both NPCs in it, shop at the
       end toward mid.
    2. Otherwise symmetric spots either side of the spawn along the island's own lateral axis, one block in from
       the island edge or the nearest wall, on solid ground with 2 blocks of air."""
    (fx, fz), (rx, rz) = island_frame(foot, head, centre)
    sx, sy, sz = spawn
    floor = int(sy)
    lat = lambda c: (c[0] + 0.5 - sx) * rx + (c[2] + 0.5 - sz) * rz
    fwd = lambda c: (c[0] + 0.5 - sx) * fx + (c[2] + 0.5 - sz) * fz
    dist = lambda c: math.hypot(c[0] + 0.5 - sx, c[2] + 0.5 - sz)
    mine = lambda c: all(math.hypot(c[0] - foot["x"], c[2] - foot["z"]) < math.hypot(c[0] - ox, c[2] - oz)
                         for ox, oz in other_feet)
    pens = [p for p in find_pens(sx, sz, floor) if all(mine(c) for c in p)]
    pens.sort(key=lambda p: min(dist(c) for c in p))
    chosen = None
    if len(pens) >= 2:
        a, b = pens[0], pens[1]
        ca, cb = min(a, key=dist), min(b, key=dist)
        if lat(ca) < lat(cb):
            ca, cb = cb, ca
        chosen = (ca, cb)
    elif len(pens) == 1 and len(pens[0]) >= 2:
        p = sorted(pens[0], key=fwd)
        chosen = (p[-1], p[0])
    if chosen is None:
        def stand(c):
            return B.floor(c[0], c[2], floor) == floor
        def cell(d, f):
            return (int(math.floor(sx + rx * d + fx * f)), floor, int(math.floor(sz + rz * d + fz * f)))
        best = None
        for f in (0, -1, 1, -2, 2, -3):
            # how far the floor reaches on each side of the spawn row (walls and edges stop it)
            reach = []
            for side in (1, -1):
                n = 0
                for d in range(1, 13):
                    if not stand(cell(side * d, f)):
                        break
                    n = d
                reach.append(n)
            d = min(min(reach) - 1, 6)
            if d >= 2:
                best = (cell(d, f), cell(-d, f))
                break
        if best is None:  # no room on the sides: put them behind the spawn, either side of the centreline
            best = (cell(2, -2), cell(-2, -2))
        chosen = best
    out = {}
    for key, c in zip(("shop", "upgrades"), chosen):
        y = B.floor(c[0], c[2], c[1]) or c[1]
        out[key] = pt(c[0] + 0.5, y, c[2] + 0.5, yaw=yaw_towards(c[0] + 0.5, c[2] + 0.5, sx, sz))
    return out


def forge_spot(foot, head, spawn, centre):
    """Team generator (iron/gold forge): remakes build the forge as a recess floored with bottom slabs one half-block
    below the island floor, behind the spawn on the island axis. Returns the pit cell nearest the pit's centre
    (ties: the one nearer the spawn) as (x, feet y, z) block centre, or None if no pit is found."""
    (fx, fz), (rx, rz) = island_frame(foot, head, centre)
    sx, sy, sz = spawn
    floor = int(sy)
    fwd = lambda c: (c[0] + 0.5 - sx) * fx + (c[1] + 0.5 - sz) * fz
    lat = lambda c: (c[0] + 0.5 - sx) * rx + (c[1] + 0.5 - sz) * rz
    cells = set()
    for x in range(int(math.floor(sx)) - 14, int(math.floor(sx)) + 15):
        for z in range(int(math.floor(sz)) - 14, int(math.floor(sz)) + 15):
            c = (x, z)
            if fwd(c) < -1 and abs(lat(c)) <= 4 and B.bottom_slab(x, floor - 1, z) \
                    and B.passable(x, floor, z) and B.passable(x, floor + 1, z):
                cells.add(c)
    pits, seen = [], set()
    for c in sorted(cells):
        if c in seen:
            continue
        pit, q = [], deque([c])
        seen.add(c)
        while q:
            x, z = q.popleft()
            pit.append((x, z))
            for n in ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)):
                if n in cells and n not in seen:
                    seen.add(n)
                    q.append(n)
        if 3 <= len(pit) <= 16:
            pits.append(pit)
    if not pits:
        return None
    pit = max(pits, key=lambda p: (len(p), -abs(sum(lat(c) for c in p) / len(p))))
    mx = sum(c[0] for c in pit) / len(pit)
    mz = sum(c[1] for c in pit) / len(pit)
    best = min(pit, key=lambda c: (round(math.hypot(c[0] - mx, c[1] - mz), 3), -fwd(c)))
    return (best[0] + 0.5, floor, best[1] + 0.5)


def mid_spot(cx, cz, y0, r=20):
    """Standing point on the centre island: the solid, 2-high cell nearest the map centre (and near the mid
    generators' height y0, within 6) that is part of a walkable floor, not a ledge or decoration top. The exact
    centre is often a hole or the gap between several mid platforms."""
    ix, iz = int(math.floor(cx)), int(math.floor(cz))
    def stand(x, y, z):
        return not B.passable(x, y - 1, z) and B.passable(x, y, z) and B.passable(x, y + 1, z)
    best = None
    for d in range(0, r + 1):
        ring = [(x, z) for x in range(ix - d, ix + d + 1) for z in range(iz - d, iz + d + 1)
                if max(abs(x - ix), abs(z - iz)) == d]
        for x, z in ring:
            for dy in sorted(range(-6, 7), key=abs):
                y = y0 + dy
                if not stand(x, y, z):
                    continue
                # real floor, not a wing / rail / roof ridge: (nearly) the whole 5x5 around it is walkable too
                patch = sum(1 for ax in range(x - 2, x + 3) for az in range(z - 2, z + 3) if stand(ax, y, az))
                if patch < 20:
                    continue
                key = math.hypot(x + 0.5 - cx, z + 0.5 - cz) + 0.5 * abs(dy)
                if best is None or key < best[0]:
                    best = (key, (x + 0.5, y, z + 0.5))
        if best is not None and best[0] <= d - 1:  # nothing in a later ring can score lower
            break
    return best[1] if best else (ix + 0.5, y0, iz + 0.5)


def bedwars(scan, world, out, name):
    global B
    L, top = load(scan)
    B = Blocks(SOURCE)
    beds = L.get("bed", [])
    heads = [b for b in beds if b["props"].get("part") == "head"]
    teams = []
    for h in heads:
        foot = min((b for b in beds if b["props"].get("part") == "foot"),
                   key=lambda f: abs(f["x"] - h["x"]) + abs(f["y"] - h["y"]) + abs(f["z"] - h["z"]))
        teams.append({"head": h, "foot": foot})
    n = len(teams)
    if n not in (2, 4, 8):
        raise SystemExit(f"unexpected bed count {n}")
    cx = sum(t["head"]["x"] for t in teams) / n
    cz = sum(t["head"]["z"] for t in teams) / n
    teams.sort(key=lambda t: angle_cw_from_north(t["head"]["x"] - cx, t["head"]["z"] - cz))
    chests = L.get("chest", []) + L.get("trapped_chest", [])
    enders = L.get("ender_chest", [])
    points = {}
    max_y = 0
    for i, t in enumerate(teams):
        color = TEAM_ORDER[i]
        h, f = t["head"], t["foot"]
        bed_y = f["y"]
        max_y = max(max_y, bed_y)
        points[f"team.{color}.bed"] = [pt(f["x"], f["y"], f["z"]), pt(h["x"], h["y"], h["z"])]
        # outward unit vector (from centre through the bed)
        ox, oz = f["x"] - cx, f["z"] - cz
        ol = math.hypot(ox, oz) or 1.0
        ox, oz = ox / ol, oz / ol
        near = lambda b: math.hypot(b["x"] - f["x"], b["z"] - f["z"])
        tc = min(chests, key=near) if chests else None
        ec = min(enders, key=near) if enders else None
        if tc and ec and near(tc) < 30 and near(ec) < 30:
            mx, mz, floor = (tc["x"] + ec["x"]) / 2.0 + 0.5, (tc["z"] + ec["z"]) / 2.0 + 0.5, tc["y"]
        else:  # no chests: assume spawn ~10 blocks behind the bed
            mx, mz, floor = f["x"] + 0.5 + ox * 10, f["z"] + 0.5 + oz * 10, bed_y
        spawn = (math.floor(mx - ox * 1.0) + 0.5, floor, math.floor(mz - oz * 1.0) + 0.5)
        # the bed lies on the island centreline: snap the spawn onto it when the chest midpoint rounding put it one
        # block off (chests at -4/+3 vs -3/+4 on rotated islands floor to different blocks)
        (afx, afz), _ = island_frame(f, h, (cx, cz))
        if abs(afz) > 0.99 and abs(spawn[0] - (f["x"] + 0.5)) <= 1:
            spawn = (f["x"] + 0.5, spawn[1], spawn[2])
        elif abs(afx) > 0.99 and abs(spawn[2] - (f["z"] + 0.5)) <= 1:
            spawn = (spawn[0], spawn[1], f["z"] + 0.5)
        gen = forge_spot(f, h, spawn, (cx, cz))
        if gen is None:  # no slab pit: the forge is straight behind the chests along the island axis
            gx, gz = walk_to_edge(top, mx, mz, -afx, -afz, floor - 1, limit=6)
            gen = (gx + 0.5, floor, gz + 0.5)  # the forge sits level with the team chests
        face_mid = yaw_towards(spawn[0], spawn[2], cx, cz)
        points[f"team.{color}.spawn"] = [pt(*spawn, yaw=face_mid)]
        points[f"team.{color}.generator"] = [pt(*gen)]
        others = [(o["foot"]["x"], o["foot"]["z"]) for o in teams if o is not t]
        for key, p in npc_spots(f, h, spawn, (cx, cz), others).items():
            points[f"team.{color}.{key}"] = [p]
        comp = component(top, (f["x"], f["z"]), maxr=26)
        xs = [c[0] for c in comp]; zs = [c[1] for c in comp]
        points[f"team.{color}.base"] = [pt(min(xs), bed_y - 10, min(zs)), pt(max(xs), bed_y + 20, max(zs))]
    for kind, key in (("diamond_block", "gen.diamond"), ("emerald_block", "gen.emerald")):
        cand = isolated(L.get(kind, []))
        # keep markers away from team bases
        cand = [b for b in cand if all(math.hypot(b["x"] - t["foot"]["x"], b["z"] - t["foot"]["z"]) > 12 for t in teams)]
        # a real generator marker is walkable on top (players stand on it to collect)
        stand = [b for b in cand if B.passable(b["x"], b["y"] + 1, b["z"]) and B.passable(b["x"], b["y"] + 2, b["z"])]
        cand = stand or cand
        # ...and open to the sky: decorative copies on an island's underside (Aquarium) have the island above them
        sky = [b for b in cand if all(B.passable(b["x"], b["y"] + k, b["z"]) for k in range(1, 8))]
        cand = sky or cand
        # one generator per horizontal cluster (stacked decorative copies): keep the lowest
        chosen = []
        for b in sorted(cand, key=lambda b: b["y"]):
            if all(math.hypot(b["x"] - c["x"], b["z"] - c["z"]) > 8 for c in chosen):
                chosen.append(b)
        points[key] = [pt(b["x"] + 0.5, b["y"] + 1, b["z"] + 0.5) for b in chosen]
    ems = points.get("gen.emerald") or []
    mid_y = (sum(p["y"] for p in ems) / len(ems)) if ems else max_y + 1  # column tops can be trees/roofs
    points["mid"] = [pt(*mid_spot(cx, cz, round(mid_y)))]
    points["spectator"] = [pt(cx + 0.5, max_y + 25, cz + 0.5)]
    xs = [k[0] for k in top]; zs = [k[1] for k in top]
    lobby_y = max(max(top.values()) + 15, max_y + 40)
    points["waiting.lobby"] = [pt(cx + 0.5, lobby_y + 1, cz + 0.5)]
    teams_cfg = [n]
    B.close()
    write(world, out, name, "BEDWARS", teams_cfg, max_y + 30,
          (min(xs) - 15, 0, min(zs) - 15), (max(xs) + 15, 255, max(zs) + 15), points,
          lobby=(int(math.floor(cx)), lobby_y, int(math.floor(cz))))


def skywars(scan, world, out, name):
    L, top = load(scan)
    chests = L.get("chest", []) + L.get("trapped_chest", [])
    islands = []
    used = set()
    for c in chests:
        key = (c["x"], c["z"])
        if key in used:
            continue
        comp = component(top, key, maxr=30, maxdy=40)
        if not comp:
            comp = {key}
        members = [o for o in chests if (o["x"], o["z"]) in comp]
        for o in members:
            used.add((o["x"], o["z"]))
        islands.append({"cols": comp, "chests": members})
    cx = sum(c["x"] for c in chests) / len(chests)
    cz = sum(c["z"] for c in chests) / len(chests)
    def centroid(isl):
        xs = [c[0] for c in isl["cols"]]; zs = [c[1] for c in isl["cols"]]
        return sum(xs) / len(xs), sum(zs) / len(zs)
    # mid can be several disconnected platforms (pillars with chests): merge everything near the centre
    near_mid = [i for i in islands if math.hypot(centroid(i)[0] - cx, centroid(i)[1] - cz) < 30]
    if not near_mid:
        near_mid = [min(islands, key=lambda i: math.hypot(centroid(i)[0] - cx, centroid(i)[1] - cz))]
    mid = {"cols": set().union(*[i["cols"] for i in near_mid]),
           "chests": list({(c["x"], c["y"], c["z"]): c for i in near_mid for c in i["chests"]}.values())}
    others = [i for i in islands if not any(i is m for m in near_mid)]
    others.sort(key=lambda i: angle_cw_from_north(centroid(i)[0] - cx, centroid(i)[1] - cz))
    points = {}
    max_y = max(top.values())
    for idx, isl in enumerate(others):
        ix, iz = centroid(isl)
        # cage: Hypixel cages hang ~5 blocks above the island's ground (not its roofs/trees), on the side facing mid.
        # Ground = columns within 2 blocks of the island's lowest walkable top; take the one nearest the centre.
        low = min(top[c] for c in isl["cols"])
        ground = [c for c in isl["cols"] if top[c] <= low + 2] or list(isl["cols"])
        hx, hz = min(ground, key=lambda c: math.hypot(c[0] - cx, c[1] - cz))
        # step one block back toward the island so the drop lands on the island, not its edge
        ix0, iz0 = centroid(isl)
        bx = hx + (1 if ix0 > hx else -1 if ix0 < hx else 0)
        bz = hz + (1 if iz0 > hz else -1 if iz0 < hz else 0)
        if (bx, bz) in isl["cols"] and top[(bx, bz)] <= low + 2:
            hx, hz = bx, bz
        cage_y = top[(hx, hz)] + 5
        # Enclosed islands (temples entered through a roof hole, e.g. Memorial): drop players onto the ladder top
        ladders = [l for l in L.get("ladder", []) if (l["x"], l["z"]) in isl["cols"]]
        if ladders:
            lad = max(ladders, key=lambda l: l["y"])
            hx, hz = lad["x"], lad["z"]
            cage_y = top[(hx, hz)] + 5
        yaw = yaw_towards(hx + 0.5, hz + 0.5, cx, cz)
        points[f"island.{idx}.cage"] = [pt(hx + 0.5, cage_y, hz + 0.5, yaw=yaw)]
        points[f"island.{idx}.chests"] = [pt(c["x"], c["y"], c["z"]) for c in isl["chests"]]
        xs = [c[0] for c in isl["cols"]]; zs = [c[1] for c in isl["cols"]]
        ys = [top[c] for c in isl["cols"]]
        points[f"island.{idx}.bounds"] = [pt(min(xs), min(ys) - 15, min(zs)), pt(max(xs), max(ys) + 5, max(zs))]
    points["mid.chests"] = [pt(c["x"], c["y"], c["z"]) for c in mid["chests"]]
    my = top.get((int(round(cx)), int(round(cz))), max_y)
    points["mid"] = [pt(cx + 0.5, my + 1, cz + 0.5)]
    points["spectator"] = [pt(cx + 0.5, max_y + 15, cz + 0.5)]
    lobby_y = max_y + 30
    points["waiting.lobby"] = [pt(cx + 0.5, lobby_y + 1, cz + 0.5)]
    xs = [k[0] for k in top]; zs = [k[1] for k in top]
    write(world, out, name, "SKYWARS", [len(others)], min(255, max_y + 20),
          (min(xs) - 20, 0, min(zs) - 20), (max(xs) + 20, 255, max(zs) + 20), points,
          lobby=(int(math.floor(cx)), lobby_y, int(math.floor(cz))))


def build_waiting_box(world_dir, center):
    """Glass floor + walls (Hypixel-style waiting box) in the converted 1.12 world."""
    import amulet
    from amulet.api.block import Block
    from amulet_nbt import StringTag
    level = amulet.load_level(world_dir)
    dim = "minecraft:overworld"
    ver = ("java", (1, 12, 2))
    glass = Block("minecraft", "stained_glass", {"color": StringTag("white")})
    barrier = Block("minecraft", "barrier")
    x0, y0, z0 = center
    r = 8
    for x in range(x0 - r, x0 + r + 1):
        for z in range(z0 - r, z0 + r + 1):
            level.set_version_block(x, y0, z, dim, ver, glass)
            edge = x in (x0 - r, x0 + r) or z in (z0 - r, z0 + r)
            if edge:
                for y in range(y0 + 1, y0 + 5):
                    level.set_version_block(x, y, z, dim, ver, glass if y <= y0 + 2 else barrier)
    level.save()
    level.close()


def write(world, out, name, family, teams, build_limit, bmin, bmax, points, lobby):
    if os.path.exists(out):
        shutil.rmtree(out)
    shutil.copytree(world, os.path.join(out, "world"), ignore=shutil.ignore_patterns("session.lock", "uid.dat"))
    build_waiting_box(os.path.join(out, "world"), lobby)
    lines = [
        "# Annotated by scripts/map-import/annotate_map.py. Check points in-game and edit if needed.",
        f"name: {json.dumps(name)}",
        f"family: {family}",
        f"teams: {json.dumps(teams)}",
        f"build-limit: {build_limit}",
        "bounds:",
        f"  min: {{x: {bmin[0]}, y: {bmin[1]}, z: {bmin[2]}}}",
        f"  max: {{x: {bmax[0]}, y: {bmax[1]}, z: {bmax[2]}}}",
        "points:",
    ]
    for k, v in points.items():
        lines.append(f"  {k}:")
        for p in v:
            lines.append("    - {" + ", ".join(f"{a}: {b}" for a, b in p.items()) + "}")
    with open(os.path.join(out, "map.yml"), "w") as f:
        f.write("\n".join(lines) + "\n")
    print(f"== {name} ({family}, teams {teams})")
    for k, v in points.items():
        print(f"  {k}: " + "; ".join(f"{p['x']},{p['y']},{p['z']}" + (f" yaw {p['yaw']}" if 'yaw' in p else "") for p in v))


SOURCE = None

if __name__ == "__main__":
    mode, scan, world, out, name = sys.argv[1:6]
    SOURCE = sys.argv[6] if len(sys.argv) > 6 else world
    (bedwars if mode == "bedwars" else skywars)(scan, world, out, name)
