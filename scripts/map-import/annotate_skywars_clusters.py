"""SkyWars annotator v2: islands = tight chest clusters (works when islands are bridged to hubs/mid).
Usage: annotate_skywars_clusters.py <scan.json> <converted world> <out dir> "<Name>" <source world> [cluster_dist] [expect]"""
import json, math, os, sys
from collections import Counter, deque
# annotate_map.py lives next to this script; works even under `python -I` (which drops the script dir from sys.path).
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import annotate_map as A

scan, world, out, name, src = sys.argv[1:6]
CD = float(sys.argv[6]) if len(sys.argv) > 6 else 8
EXPECT = int(sys.argv[7]) if len(sys.argv) > 7 else 0
L, top = A.load(scan)
chests = L.get("chest", []) + L.get("trapped_chest", [])
# single-linkage clustering
cl = []
for c in chests:
    hit = [k for k in cl if any(math.hypot(c["x"] - o["x"], c["z"] - o["z"]) <= CD and abs(c["y"] - o["y"]) <= 12 for o in k)]
    new = [c] + [o for k in hit for o in k]
    cl = [k for k in cl if not any(k is h for h in hit)] + [new]
cen = lambda k: (sum(o["x"] for o in k) / len(k), sum(o["z"] for o in k) / len(k))
cx = sum(c["x"] for c in chests) / len(chests); cz = sum(c["z"] for c in chests) / len(chests)
isl = [k for k in cl if len(k) >= 2 and math.hypot(cen(k)[0] - cx, cen(k)[1] - cz) > 15]
if EXPECT and len(isl) > EXPECT:  # keep the outermost clusters
    isl.sort(key=lambda k: -math.hypot(cen(k)[0] - cx, cen(k)[1] - cz)); isl = isl[:EXPECT]
cx = sum(cen(k)[0] for k in isl) / len(isl); cz = sum(cen(k)[1] for k in isl) / len(isl)
midc = [c for c in chests if not any(c is o for k in isl for o in k)]
isl.sort(key=lambda k: A.angle_cw_from_north(cen(k)[0] - cx, cen(k)[1] - cz))
A.B = B = A.Blocks(src)
points = {}
warn = []
for i, k in enumerate(isl):
    kx, kz = cen(k)
    R = max(10, max(math.hypot(o["x"] - kx, o["z"] - kz) for o in k) + 7)
    cy = min(o["y"] for o in k)
    seen = set(); q = deque()
    for o in k:
        p = (o["x"], o["z"])
        if p in top: seen.add(p); q.append(p)
    while q:
        x, z = q.popleft()
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                n = (x + dx, z + dz)
                if n in seen or n not in top or math.hypot(n[0] - kx, n[1] - kz) > R or abs(top[n] - cy) > 25: continue
                seen.add(n); q.append(n)
    core = {p for p in seen if all((p[0] + dx, p[1] + dz) in seen for dx in (-1, 0, 1) for dz in (-1, 0, 1))} or seen
    def gtop(p):
        y = top[p]
        while y > 0 and (B.passable(p[0], y, p[1]) or any(t in B.name(p[0], y, p[1]) for t in ("leaves", "log", "wood", "fence", "vine"))):
            y -= 1
        return y
    G = {p: gtop(p) for p in core}
    mode = Counter(G.values()).most_common(1)[0][0]
    def score(p):
        flat = all(top.get((p[0] + dx, p[1] + dz), -99) <= G[p] + 1 for dx in (-1, 0, 1) for dz in (-1, 0, 1))
        stand = B.floor(p[0], p[1], top[p] + 1) == top[p] + 1
        inner = all((p[0] + dx, p[1] + dz) in core for dx in (-2, 0, 2) for dz in (-2, 0, 2))
        return (0 if (flat and stand) else 1, 0 if inner else 1, math.hypot(p[0] - cx, p[1] - cz))
    cand = [p for p in core if abs(G[p] - mode) <= 1 and top[p] == G[p]] or list(core)
    hx, hz = min(cand, key=score)
    if score((hx, hz))[0]: warn.append(f"island {i}: cage column {hx},{hz} not flat/standable")
    cage_y = top[(hx, hz)] + 5
    points[f"island.{i}.cage"] = [A.pt(hx + 0.5, cage_y, hz + 0.5, yaw=A.yaw_towards(hx + 0.5, hz + 0.5, cx, cz))]
    points[f"island.{i}.chests"] = [A.pt(o["x"], o["y"], o["z"]) for o in k]
    xs = [p[0] for p in seen]; zs = [p[1] for p in seen]; ys = [top[p] for p in seen]
    points[f"island.{i}.bounds"] = [A.pt(min(xs), min(ys) - 15, min(zs)), A.pt(max(xs), max(ys) + 5, max(zs))]
points["mid.chests"] = [A.pt(c["x"], c["y"], c["z"]) for c in midc]
# mid: standable spot next to the most central mid chest
mp = None
for c in sorted(midc, key=lambda c: math.hypot(c["x"] - cx, c["z"] - cz)):
    for r in (1, 2, 3):
        for dx in range(-r, r + 1):
            for dz in range(-r, r + 1):
                f = B.floor(c["x"] + dx, c["z"] + dz, c["y"])
                if f is not None and mp is None: mp = (c["x"] + dx + 0.5, f, c["z"] + dz + 0.5)
    if mp: break
if mp is None: mp = (cx + 0.5, top.get((round(cx), round(cz)), 80) + 1, cz + 0.5); warn.append("mid fallback")
points["mid"] = [A.pt(*mp)]
max_y = max(top.values())
points["spectator"] = [A.pt(cx + 0.5, max_y + 15, cz + 0.5)]
lobby_y = min(max_y + 30, 240)
points["waiting.lobby"] = [A.pt(math.floor(cx) + 0.5, lobby_y + 1, math.floor(cz) + 0.5)]
xs = [k[0] for k in top]; zs = [k[1] for k in top]
B.close()
A.write(world, out, name, "SKYWARS", [len(isl)], min(255, max_y + 20),
        (min(xs) - 20, 0, min(zs) - 20), (max(xs) + 20, 255, max(zs) + 20), points,
        lobby=(int(math.floor(cx)), lobby_y, int(math.floor(cz))))
print("islands", len(isl), "chests/island", [len(k) for k in isl], "mid chests", len(midc))
for w in warn: print("WARN", w)
