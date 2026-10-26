"""validate.py <template dir> [source world]: structure + cage checks."""
import sys, os, re, glob, math, amulet
t = sys.argv[1]; w = os.path.join(t, "world"); src = sys.argv[2] if len(sys.argv) > 2 else w
errs = []
if not os.path.isfile(os.path.join(w, "level.dat")): errs.append("no level.dat")
if not glob.glob(os.path.join(w, "region", "*.mca")): errs.append("no region files")
y = open(os.path.join(t, "map.yml")).read()
teams = int(re.search(r"teams: \[(\d+)\]", y).group(1))
pts = {}; sec = None
for line in y.splitlines():
    m = re.match(r"  (\S+):$", line)
    if m: sec = m.group(1); pts[sec] = []; continue
    m = re.search(r"x: (-?[\d.]+), y: (-?[\d.]+), z: (-?[\d.]+)", line)
    if m and sec: pts[sec].append(tuple(map(float, m.groups())))
isl = sorted({int(k.split(".")[1]) for k in pts if k.startswith("island.")})
if len(isl) != teams: errs.append(f"islands {len(isl)} != teams {teams}")
lv = amulet.load_level(src); dim = "minecraft:overworld"
nm = lambda x, y, z: lv.get_block(math.floor(x), math.floor(y), math.floor(z), dim).base_name
AIRISH = ("air", "tall_grass", "grass", "fern", "snow_layer", "double_plant", "red_flower", "yellow_flower", "torch", "vine")
nch = 0
for i in isl:
    (x, cy, z), = pts[f"island.{i}.cage"]; a, b = pts[f"island.{i}.bounds"]
    if not (a[0] <= x <= b[0] + 1 and a[2] <= z <= b[2] + 1 and a[1] <= cy <= b[1]): errs.append(f"island {i} cage outside bounds")
    blocked = [(dx, dy, dz, nm(x + dx, cy + dy, z + dz)) for dx in (-1, 0, 1) for dz in (-1, 0, 1) for dy in (-1, 0, 1, 2, 3) if nm(x + dx, cy + dy, z + dz) not in AIRISH]
    if blocked: errs.append(f"island {i} cage space blocked: {blocked[:4]}")
    land = next((yy for yy in range(int(cy) - 2, int(cy) - 14, -1) if nm(x, yy, z) not in AIRISH), None)
    if land is None: errs.append(f"island {i}: nothing to land on below cage")
    elif cy - land - 1 > 6: errs.append(f"island {i}: drop {cy - land - 1}")
    for c in pts[f"island.{i}.chests"]:
        nch += 1
        if "chest" not in nm(*c): errs.append(f"island {i} chest {c} is {nm(*c)}")
for c in pts.get("mid.chests", []):
    if "chest" not in nm(*c): errs.append(f"mid chest {c} is {nm(*c)}")
mx, my, mz = pts["mid"][0]
if nm(mx, my - 1, mz) in AIRISH or nm(mx, my, mz) not in AIRISH: errs.append(f"mid point not standable ({nm(mx,my-1,mz)}/{nm(mx,my,mz)})")
lv.close()
print(os.path.basename(t), f"teams={teams} island_chests={nch} mid_chests={len(pts.get('mid.chests', []))}", "OK" if not errs else "")
for e in errs: print("   ", e)
