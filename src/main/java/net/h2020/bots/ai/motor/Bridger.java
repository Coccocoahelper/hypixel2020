package net.h2020.bots.ai.motor;

import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.perception.Sighting;
import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/**
 * Human bridging over the void in a cardinal direction, with the techniques 2020 players actually used.
 * <p>
 * Geometry: the bot faces away from the travel direction (yaw + 180 + technique offset) and walks backwards. Once its
 * eye is beyond the support block's edge by h, a ray at pitch p hits the support's travel-side face when
 * h * tan(p) * cos(offset) > eyeHeight, so the pitch is derived from a technique-specific target h (~0.2-0.27) and
 * only becomes "right" at the edge; the right-click is sent only when the client's own ray (targetedBlock/Face) hits
 * that face, so placement timing comes from real physics, never from a timer. A diagonal offset also moves the ray's
 * hit point sideways (by h * tan(offset)), so diagonal/sideways techniques walk slightly off the line's centre.
 * <p>
 * What makes the techniques look different on camera:
 * <ul>
 * <li>NOOB: half-sideways (50-62 degrees off the back), shift held all the time, walks with A/D, creeps to the edge,
 *   stops after every block, hesitates and looks around, sometimes steps back and tries again.</li>
 * <li>SHIFT: straight back, shift held the whole bridge, steady S, one block per step; can peek up at the
 *   destination while still walking (the crouch keeps it on the edge).</li>
 * <li>SPEED: straight back, S held uncrouched, a short crouch tap only at each edge, small yaw wobble.</li>
 * <li>NINJA: diagonal (about 45 degrees), S + A/D, crouch taps at the edge.</li>
 * <li>MOONWALK: diagonal like ninja but never crouches (the diagonal gives the time to place).</li>
 * <li>BREEZILY: nearly straight back, never crouches, S held while alternating A and D: the body zig-zags.</li>
 * </ul>
 * The technique comes from the persona and is adjusted per bridge (short gaps, long bridges, enemies close). Timing
 * jitter, late clicks caught by a last-instant crouch, step-backs, look-ups at the destination and glances toward
 * noises keep it from looking scripted.
 */
public final class Bridger {
    public enum Result { RUNNING, DONE, FAILED }

    /** Concrete technique for one bridge. */
    public enum Tech { NOOB, SHIFT, SPEED, NINJA, MOONWALK, BREEZILY }

    private enum Crouch { HOLD, TAP, NONE }

    private enum Phase { TURN, MOVE, GLANCE, PAUSE, STEPBACK, RECOVER }

    private final BotContext ctx;
    private Tech tech;
    private Crouch crouch;
    private int dx, dz;
    private int sx, sy, sz;           // current support block
    private int needed, placed;
    private Phase phase;
    private int phaseTicks, ticks, ticksOnBlock;
    private float travelYaw, offset, pitch, basePitch;
    private double hTarget, latTarget, lastProg;
    private int unsneakDelay;
    private int clickCooldown, clickWait;
    private boolean rayWasOn;
    private int blocksUntilGlance;
    private boolean forgetSneak;
    private boolean active;
    private int offsetSign = 1;
    private int pauseLen;
    private Location glancePoint;
    private float glanceYaw, glancePitch;
    private boolean peek;              // shift bridgers keep walking while looking up
    private boolean pressured;
    private double sloppy;
    private float wobbleAmp, wobbleFreq, wobblePhase;
    private float lastF = -1, lastS = 0;
    private int breezyDir = 1;
    private double breezyAmp;
    private boolean hesitated;
    private int lateTicks;
    private long lastGlanceTick = -1000;
    private boolean unconfirmed;       // support is our client's predicted block, not yet confirmed by the server
    private int invOpenTicks, swapWait;
    private boolean stacking;
    private int stackTicks;

    public Bridger(BotContext ctx) { this.ctx = ctx; }

    public boolean active() { return active; }
    public int placed() { return placed; }
    public int needed() { return needed; }
    public int dirX() { return dx; }
    public int dirZ() { return dz; }
    public Tech tech() { return tech; }

    /** Start bridging `count` blocks in (dx,dz) from the support block under (fx,fy-1,fz). */
    public void start(int dx, int dz, int fx, int fy, int fz, int count) {
        this.dx = dx; this.dz = dz;
        this.sx = fx; this.sy = fy - 1; this.sz = fz;
        this.needed = count; this.placed = 0;
        this.pressured = underPressure();
        this.tech = chooseTech(count, pressured);
        this.travelYaw = AIMath.yawOf(dx, dz);
        this.phase = Phase.TURN; this.phaseTicks = 0; this.ticks = 0; this.ticksOnBlock = 0;
        this.unsneakDelay = 0; this.clickCooldown = 0; this.clickWait = 0; this.rayWasOn = false;
        this.offsetSign = ctx.rng.nextBoolean() ? 1 : -1;
        double o = ctx.persona.skill.overall;
        this.sloppy = 0.03 + 0.2 * (1 - o) + (pressured ? 0.08 : 0);
        configure();
        this.blocksUntilGlance = nextGlanceBlocks();
        this.forgetSneak = false;
        this.hesitated = false;
        this.lateTicks = 0;
        this.unconfirmed = false;
        this.swapWait = 0; this.invOpenTicks = 0;
        this.lastF = -1; this.lastS = 0;
        this.breezyDir = ctx.rng.nextBoolean() ? 1 : -1;
        this.peek = false;
        this.stacking = false;
        this.stackTicks = 0;
        this.active = true;
    }

    public void cancel() { active = false; }

    /** Someone close by, or we were hit recently: bridge faster and sloppier. */
    private boolean underPressure() {
        if (ctx.perception.enemiesWithin(14) > 0) return true;
        for (Sighting s : ctx.perception.all()) if (ctx.tick - s.lastHurtMeTick < 80) return true;
        return false;
    }

    /** Persona technique, adjusted to the situation (players switch: a quick tap for a 2-block gap, safe on long ones). */
    private Tech chooseTech(int count, boolean pressure) {
        Tech base = ctx.persona.bridgeTech;
        double o = ctx.persona.skill.overall;
        double r = ctx.rng.nextDouble();
        switch (base) {
            case SHIFT:
                if (pressure && r < 0.1 + 0.5 * o) return Tech.SPEED;        // panicking: let go of shift to go faster
                if (count <= 2 && r < 0.45 * o) return Tech.SPEED;           // short gap: a couple of quick taps
                return Tech.SHIFT;
            case SPEED:
                if (count >= 14 && !pressure && r < 0.25) return Tech.SHIFT; // long bridge: play it safe
                if (pressure && o > 0.6 && r < 0.2) return Tech.NINJA;
                return Tech.SPEED;
            case NINJA:
            case MOONWALK:
                if (count <= 2 && r < 0.35) return Tech.SPEED;               // not worth setting up the diagonal
                if (count >= 16 && !pressure && r < 0.2) return Tech.SPEED;
                return base;
            case BREEZILY:
                if (count <= 2 && r < 0.4) return Tech.SPEED;
                if (count >= 18 && !pressure && r < 0.12) return Tech.NINJA;
                return Tech.BREEZILY;
            case NOOB:
            default:
                return base == null ? Tech.SHIFT : base;
        }
    }

    private void configure() {
        double eyeH, depth;
        switch (tech) {
            case NOOB:
                crouch = Crouch.HOLD;
                offset = offsetSign * (float) AIMath.range(ctx.rng, 50, 62);
                hTarget = 0.26; depth = 0.45; eyeH = 1.54;
                break;
            case SHIFT:
                crouch = Crouch.HOLD;
                offset = (float) AIMath.range(ctx.rng, -4, 4);
                hTarget = 0.25; depth = 0.28; eyeH = 1.54;
                break;
            case SPEED:
                crouch = Crouch.TAP;
                offset = (float) AIMath.range(ctx.rng, -3, 3);
                hTarget = 0.21; depth = 0.22; eyeH = 1.62;
                break;
            case NINJA:
                crouch = Crouch.TAP;
                offset = offsetSign * (float) AIMath.range(ctx.rng, 38, 50);
                hTarget = 0.22; depth = 0.25; eyeH = 1.62;
                break;
            case MOONWALK:
                crouch = Crouch.NONE;
                offset = offsetSign * (float) AIMath.range(ctx.rng, 40, 50);
                hTarget = 0.2; depth = 0.22; eyeH = 1.62;
                break;
            case BREEZILY:
            default:
                crouch = Crouch.NONE;
                offset = offsetSign * (float) AIMath.range(ctx.rng, 10, 22);
                hTarget = 0.2; depth = 0.2; eyeH = 1.62;
                break;
        }
        double off = Math.toRadians(offset);
        // stand off-centre so the sideways-drifting ray still lands on the face (see class doc)
        latTarget = AIMath.clamp(hTarget * Math.tan(off) * 0.75, -0.28, 0.28);
        double p = Math.toDegrees(Math.atan((eyeH + depth) * Math.cos(off) / hTarget));
        p += ctx.rng.nextGaussian() * 0.4;
        // crouching beginners start a bit too flat and nudge down at the edge (the clamp keeps them on the block)
        if (crouch == Crouch.HOLD) p -= Math.abs(ctx.rng.nextGaussian()) * 2.5 * (1 - ctx.persona.skill.overall);
        pitch = (float) AIMath.clamp(p, 60, 89);
        basePitch = pitch;
        wobbleAmp = tech == Tech.SPEED ? (float) AIMath.range(ctx.rng, 1.0, 2.6) : (tech == Tech.SHIFT ? 0.6f : 0f);
        wobbleFreq = (float) AIMath.range(ctx.rng, 0.12, 0.28);
        wobblePhase = (float) (ctx.rng.nextDouble() * Math.PI * 2);
        breezyAmp = AIMath.range(ctx.rng, 0.1, 0.2);
    }

    private int nextGlanceBlocks() {
        double aw = ctx.persona.skill.awareness;
        if (tech == Tech.NOOB) return AIMath.range(ctx.rng, 2, 4);
        int lo = (int) (4 + (1 - aw) * 7), hi = (int) (8 + (1 - aw) * 12);
        if (pressured) { lo += 2; hi += 4; } // eyes on the bridge when someone is coming
        return AIMath.range(ctx.rng, lo, hi);
    }

    private float lookYaw() {
        float w = wobbleAmp == 0 ? 0 : wobbleAmp * (float) Math.sin(ticks * wobbleFreq + wobblePhase);
        return AIMath.wrap(travelYaw + 180f + offset + w);
    }

    /** Signed distance of the body centre past the support's travel-side edge (negative = still on the block). */
    private double pastEdge(Location p) {
        double along = (p.getX() - (sx + 0.5)) * dx + (p.getZ() - (sz + 0.5)) * dz;
        return along - 0.5;
    }

    /** Lateral error of the body centre from the bridge line. */
    private double lateral(Location p) {
        // lateral axis = travel rotated 90deg: (-dz, dx)
        return (p.getX() - (sx + 0.5)) * -dz + (p.getZ() - (sz + 0.5)) * dx;
    }

    public Result tick() {
        if (!active) return Result.FAILED;
        ticks++;
        phaseTicks++;
        ticksOnBlock++;
        WorldView wv = ctx.world();
        Location pos = ctx.pos();

        // fell off?
        if (pos.getY() < sy + 1 - 0.7) { active = false; return Result.FAILED; }
        // a chest left open (late window from a cancelled loot) freezes keys and mouse: close it
        if (ctx.c.containerOpen() && (!Inv.ownInventoryOpen(ctx.player, ctx.c) || ++invOpenTicks > 8)) { ctx.c.closeWindow(); invOpenTicks = 0; }
        else if (!ctx.c.containerOpen()) invOpenTicks = 0;
        // support advanced (we or someone placed the next block; our own client sees its block before the server does)
        int nx = sx + dx, nz = sz + dz;
        if (unconfirmed) {
            if (Shape.floor(wv.shape(sx, sy, sz))) unconfirmed = false;
            else if (!clientPredicted(sx, sy, sz)) {
                // the server never put it there (rejected): it was a ghost block, back to the real support
                sx -= dx; sz -= dz; placed--; unconfirmed = false;
                nx = sx + dx; nz = sz + dz;
            }
        }
        boolean serverHas = Shape.floor(wv.shape(nx, sy, nz));
        if (serverHas || clientPredicted(nx, sy, nz)) {
            unconfirmed = !serverHas;
            sx = nx; sz = nz;
            placed++;
            ticksOnBlock = 0;
            rayWasOn = false;
            clickWait = 0;
            lateTicks = 0;
            hesitated = false;
            // crouch-tap bridgers let go of shift a beat after the click; shift bridgers never do
            unsneakDelay = crouch == Crouch.TAP ? AIMath.range(ctx.rng, 0, 2) : 0;
            // small re-aim drift between blocks, like a hand resting on the mouse
            if (ctx.rng.nextDouble() < 0.35) pitch = (float) AIMath.clamp(basePitch + ctx.rng.nextGaussian() * (0.25 + 0.4 * sloppy), 60, 89);
            if (placed >= needed && !unconfirmed) { active = false; return Result.DONE; }
            blocksUntilGlance--;
            if (tech == Tech.NOOB) {
                phase = Phase.PAUSE; phaseTicks = 0;
                pauseLen = AIMath.range(ctx.rng, pressured ? 2 : 4, pressured ? 8 : 16);
            }
            forgetSneak = tech == Tech.NOOB && ctx.rng.nextDouble() < 0.025 * (1.0 - ctx.persona.skill.overall);
            nx = sx + dx; nz = sz + dz;
        }
        if (placed >= needed && !unconfirmed) { active = false; return Result.DONE; }
        if (phase == Phase.RECOVER) return recover(pos);
        if (ticksOnBlock > (tech == Tech.NOOB ? 240 : 120)) return giveUp();
        double h = pastEdge(pos);
        double lat = lateral(pos);
        if (!Inv.holding(ctx.player, Inv.BLOCK)) {
            if (!Inv.has(ctx.player, Inv.BLOCK)) return giveUp();
            // stack ran out. Refilling the hotbar means opening the inventory, which lets go of shift: first crouch-step
            // back onto the block like a player would
            if (Inv.findHotbar(ctx.player, Inv.BLOCK) < 0 && h > -0.3) {
                ctx.motor.sneak(true);
                ctx.motor.move(1, 0);
                return Result.RUNNING;
            }
            // (a hotbar swap is a window click the server applies one ping later: wait for it instead of clicking
            // again every tick, which just swaps the stack back and forth)
            if (swapWait > 0 && Inv.findHotbar(ctx.player, Inv.BLOCK) < 0) { swapWait--; ctx.motor.sneak(true); return Result.RUNNING; }
            if (!Inv.equip(ctx.player, ctx.c, Inv.BLOCK, 1)) {
                swapWait = 8;
                ctx.motor.sneak(true);
                return Result.RUNNING;
            }
        }

        switch (phase) {
            case TURN: {
                ctx.motor.aimRotation(Motor.P_TASK, lookYaw(), pitch, AimIntent.PRECISE, 0.4);
                ctx.motor.sneak(crouch == Crouch.HOLD || h > -0.35);
                // start backing up once the turn is nearly done (the last correction happens on the way to the edge)
                if ((ctx.c.aimSettled() && phaseTicks > 3) || (aimError() < 6 && phaseTicks > 2) || phaseTicks > 25) { phase = Phase.MOVE; phaseTicks = 0; }
                return Result.RUNNING;
            }
            case PAUSE: {
                // beginners stop after each block (and sometimes at the edge), sometimes peeking around
                ctx.motor.sneak(true);
                if (pauseLen > 12 && phaseTicks > 3 && phaseTicks < pauseLen - 4) {
                    if (phaseTicks == 4) { glanceYaw = AIMath.wrap(ctx.c.yaw() + (float) ctx.rng.nextGaussian() * 50f); glancePitch = (float) AIMath.range(ctx.rng, 15, 50); }
                    ctx.motor.aimRotation(Motor.P_TASK, glanceYaw, glancePitch, AimIntent.GLANCE, 4);
                } else {
                    ctx.motor.aimRotation(Motor.P_TASK, lookYaw(), pitch, AimIntent.PRECISE, 0.6);
                }
                if (phaseTicks > pauseLen) { phase = blocksUntilGlance <= 0 ? Phase.GLANCE : Phase.MOVE; phaseTicks = 0; }
                return Result.RUNNING;
            }
            case STEPBACK: {
                // missed the face: crouch-step back onto the block, then walk out again
                ctx.motor.sneak(true);
                ctx.motor.aimRotation(Motor.P_TASK, lookYaw(), pitch, AimIntent.PRECISE, 0.6);
                ctx.motor.move(1, 0);
                if (phaseTicks >= pauseLen || h < -0.3) { phase = Phase.MOVE; phaseTicks = 0; }
                return Result.RUNNING;
            }
            case GLANCE: {
                boolean safeStand = crouch == Crouch.HOLD || h > -0.15;
                ctx.motor.sneak(safeStand);
                if (glancePoint != null) ctx.motor.aimPoint(Motor.P_TASK, glancePoint, AimIntent.GLANCE, 1.0);
                else {
                    if (phaseTicks == 1) {
                        glanceYaw = AIMath.wrap(travelYaw + (float) ctx.rng.nextGaussian() * 15f);
                        glancePitch = (float) AIMath.range(ctx.rng, 5, 25);
                    }
                    ctx.motor.aimRotation(Motor.P_TASK, glanceYaw, glancePitch, AimIntent.GLANCE, 4);
                }
                // a shift bridger keeps backing up while looking: the crouch holds it on the edge
                if (peek) steer(lat - latTarget, h);
                int len = peek ? 8 : 10 + (tech == Tech.NOOB ? 10 : 0);
                if (phaseTicks > len) {
                    phase = Phase.TURN; phaseTicks = 0; blocksUntilGlance = nextGlanceBlocks(); peek = false;
                }
                return Result.RUNNING;
            }
            default:
                break;
        }

        // MOVE
        boolean mayGlance = ctx.tick - lastGlanceTick > 50 && !unconfirmed;
        if (h < -0.25 && mayGlance && ctx.perception.glanceDue()) {
            // heard something (someone bridging at us?): look up from the bridge toward it
            glancePoint = ctx.perception.takeGlance();
            phase = Phase.GLANCE; phaseTicks = 0; peek = false; lastGlanceTick = ctx.tick;
            return Result.RUNNING;
        }
        if (blocksUntilGlance <= 0 && h < -0.25 && mayGlance) {
            glancePoint = null; phase = Phase.GLANCE; phaseTicks = 0; lastGlanceTick = ctx.tick;
            peek = tech == Tech.SHIFT && ctx.rng.nextDouble() < 0.7;
            return Result.RUNNING;
        }
        // beginners stop at the edge to think about it
        if (tech == Tech.NOOB && !hesitated && h > -0.05) {
            hesitated = true;
            double r = ctx.rng.nextDouble();
            if (r < 0.12) { phase = Phase.STEPBACK; phaseTicks = 0; pauseLen = AIMath.range(ctx.rng, 3, 6); return Result.RUNNING; }
            if (r < 0.35) { phase = Phase.PAUSE; phaseTicks = 0; pauseLen = AIMath.range(ctx.rng, 8, 26); return Result.RUNNING; }
        }
        ctx.motor.aimRotation(Motor.P_TASK, lookYaw(), placePitch(pos, h), AimIntent.PRECISE, 0.35);
        if (Math.abs(AIMath.angleDiff(ctx.c.yaw(), lookYaw())) > 50) {
            // the mouse is somewhere else (flicked to a hit, looking at someone): don't walk blind over the void
            ctx.motor.move(0, 0);
            ctx.motor.sneak(h > -0.3 || crouch == Crouch.HOLD);
            return Result.RUNNING;
        }
        boolean clicked = false;
        // place when the client's ray is on the support's travel-side face
        if (clickCooldown > 0) clickCooldown--;
        Block tb = ctx.c.targetedBlock();
        BlockFace tf = ctx.c.targetedFace();
        BlockFace want = faceOf(dx, dz);
        boolean rayOn = tb != null && tf == want && tb.getX() == sx && tb.getY() == sy && tb.getZ() == sz;

        if (shouldStack(h, pos)) {
            stacking = true;
            stackTicks = 0;
            ctx.motor.sneak(true);
            ctx.motor.aimRotation(Motor.P_TASK, lookYaw(), pitch, AimIntent.PRECISE, 0.35);
            return Result.RUNNING;
        }

        if (stacking) {
            stackTicks++;
            ctx.motor.sneak(true);
            if (ctx.c.onGround() && stackTicks <= 2) {
                ctx.motor.jump(true);
                return Result.RUNNING;
            }
            Block st = ctx.player.getWorld().getBlockAt(sx, sy, sz);
            BlockFace stackFace = BlockFace.UP;
            Block stackTarget = ctx.c.targetedBlock();
            BlockFace stackTf = ctx.c.targetedFace();
            if (stackTarget != null && stackTarget.equals(st) && stackTf == stackFace && stackTicks >= 2) {
                ctx.c.rightClick();
                stacking = false;
                stackTicks = 0;
                return Result.RUNNING;
            }
            if (stackTicks > 9) {
                stacking = false;
                stackTicks = 0;
            }
            return Result.RUNNING;
        }

        if (rayOn && wv.passable(nx, sy, nz)) {
            if (!rayWasOn) {
                rayWasOn = true;
                // reaction: usually on the beat, sometimes a tick or two late (then the last-instant crouch saves it)
                // (sneakless bridgers live on that timing: only their sloppiness shows, as a save-crouch)
                clickWait = ctx.rng.nextDouble() < sloppy ? AIMath.range(ctx.rng, 1, 3)
                        : (crouch != Crouch.NONE && ctx.rng.nextDouble() < 0.25 ? 1 : 0);
            }
            if (clickWait > 0) clickWait--;
            else if (clickCooldown == 0) {
                ctx.c.rightClick();
                clicked = true;
                clickCooldown = Math.max(1, AIMath.msToTicks(ctx.rng, ctx.persona.bridgeClickMs) - 1);
            }
        }
        steer(lat - latTarget, h);
        sneakPolicy(h, clicked);

        // overshot the face and still nothing placed: step back and try again (crouching styles are held by the clamp)
        if (h > hTarget + 0.02 && ctx.motor.sneaking()) lateTicks++; else lateTicks = 0;
        if (lateTicks > 6 && ctx.rng.nextDouble() < 0.3) {
            lateTicks = 0;
            phase = Phase.STEPBACK; phaseTicks = 0; pauseLen = AIMath.range(ctx.rng, 2, 4);
        }
        return Result.RUNNING;
    }

    /** Legit players stack up during a rush or when a nearby enemy forces the pace, by jumping and placing a block
     * underneath their feet instead of just walking the bridge line. This is a high-pressure, short-lived tactic, not
     * a constant pillar. */
    private boolean shouldStack(double h, Location pos) {
        if (ctx.perception.enemiesWithin(16) == 0) return false;
        if (ctx.c.onGround() == false || h > 0.15 || h < -0.2) return false;
        if (placed < 1 || !Inv.holding(ctx.player, Inv.BLOCK)) return false;
        if (ctx.rng.nextDouble() > (pressured ? 0.32 : 0.12)) return false;
        return true;
    }

    /**
     * The technique pitch only lines the ray up with the face at the target edge distance. Overshooting (lag, a late
     * crouch) the ray passes under the support (or into the block below it) and no click ever lands, so the player
     * nudges the mouse toward the face like anyone would.
     */
    private float placePitch(Location pos, double h) {
        // (crouching at the edge clamp the body stops near 0.3: a slightly-off pitch shows as lingering there)
        if (h <= hTarget + 0.04 && !(lateTicks > 3 && h > hTarget - 0.02)) return pitch;
        double drop = pos.getY() + 1.58 - (sy + 0.5);
        double p = Math.toDegrees(Math.atan(drop * Math.cos(Math.toRadians(offset)) / h));
        return (float) AIMath.clamp(p, 60, 89);
    }

    /** Giving up mid-bridge: first crouch-walk back onto the support, never just let go of shift over the void. */
    private Result giveUp() {
        phase = Phase.RECOVER;
        phaseTicks = 0;
        // this tick too: one tick without shift at the edge is all it takes to slide off
        ctx.motor.sneak(true);
        ctx.motor.move(0, 0);
        return Result.RUNNING;
    }

    private Result recover(Location pos) {
        double h = pastEdge(pos);
        ctx.motor.sneak(true);
        double tx = sx + 0.5 - pos.getX(), tz = sz + 0.5 - pos.getZ();
        if (h < -0.2 || phaseTicks > 40) { ctx.motor.move(0, 0); active = false; return Result.FAILED; }
        ctx.motor.moveToward(tx, tz);
        return Result.RUNNING;
    }

    /**
     * Key choice. Candidates are S, S+A, S+D, A, D, W+A, W+D; each technique has its habitual keys (S for straight
     * back, S + one strafe for diagonals, strafing for half-sideways beginners) and only corrects with another
     * combo when the body has drifted off the line, holding a choice for a few ticks like fingers do. Breezily
     * alternates A and D on purpose.
     */
    private void steer(double err, double h) {
        float yaw = ctx.c.yaw();
        double rad = Math.toRadians(yaw);
        double fx = -Math.sin(rad), fz = Math.cos(rad);       // look dir
        double lx = Math.cos(rad), lz = Math.sin(rad);        // left (A)
        double latX = -dz, latZ = dx;

        if (tech == Tech.BREEZILY) {
            // S held, A/D swapped whenever the body has swung past its amplitude on the current side
            double latA = ((-fx + lx) * latX + (-fz + lz) * latZ), latD = ((-fx - lx) * latX + (-fz - lz) * latZ);
            if (latA * latD < 0) {                       // the two diagonals drift to opposite sides, as they should
                int toPlus = latA > 0 ? 1 : -1;          // key that drifts toward +lateral
                if (err > breezyAmp) breezyDir = -toPlus;
                else if (err < -breezyAmp) breezyDir = toPlus;
                setKeys(-1, breezyDir);
                return;
            }
            // mouse not where it should be (both diagonals drift the same way): plain line keeping below
        }

        float[][] opts = {{-1, 0}, {-1, 1}, {-1, -1}, {0, 1}, {0, -1}, {1, 1}, {1, -1}};
        float bf = -1, bs = 0;
        double best = Double.MAX_VALUE;
        for (float[] o : opts) {
            double mx = o[0] * fx + o[1] * lx, mz = o[0] * fz + o[1] * lz;
            double len = Math.sqrt(mx * mx + mz * mz);
            mx /= len; mz /= len;
            double prog = mx * dx + mz * dz;
            if (prog < 0.3) continue;
            double latV = mx * latX + mz * latZ;
            double predicted = err + latV * 0.35;
            double score = Math.abs(predicted) * 5.0 - prog * 1.2 + habit(o[0], o[1]);
            if (o[0] != lastF || o[1] != lastS) score += 0.45; // fingers stay on the keys they hold
            if (score < best) { best = score; bf = o[0]; bs = o[1]; }
        }
        // beginners creep the last stretch before the edge
        if (tech == Tech.NOOB && h > -0.15 && ctx.tick % 2 == 0) { ctx.motor.move(0, 0); return; }
        setKeys(bf, bs);
    }

    private double habit(float f, float s) {
        switch (tech) {
            case SHIFT: case SPEED:
                return f == -1 && s == 0 ? 0 : (f == -1 ? 0.35 : 0.9);
            case NINJA: case MOONWALK:
                return f == -1 && s != 0 ? 0 : 0.3;
            default:
                return 0;
        }
    }

    private boolean clientPredicted(int x, int y, int z) {
        return ctx.c instanceof net.h2020.bots.client.VirtualClient
                && ((net.h2020.bots.client.VirtualClient) ctx.c).phys.isPredicted(x, y, z);
    }

    private double aimError() {
        double ey = AIMath.angleDiff(ctx.c.yaw(), lookYaw()), ep = ctx.c.pitch() - pitch;
        return Math.sqrt(ey * ey + ep * ep);
    }

    private void setKeys(float f, float s) {
        lastF = f; lastS = s;
        double rad = Math.toRadians(ctx.c.yaw());
        double mx = f * -Math.sin(rad) + s * Math.cos(rad), mz = f * Math.cos(rad) + s * Math.sin(rad);
        double len = Math.sqrt(mx * mx + mz * mz);
        lastProg = len < 1e-6 ? 0 : (mx * dx + mz * dz) / len;
        ctx.motor.move(f, s);
    }

    private void sneakPolicy(double h, boolean clicked) {
        org.bukkit.util.Vector v = ctx.c.velocity();
        double vAlong = Math.max(0, v.getX() * dx + v.getZ() * dz);
        if (crouch == Crouch.NONE) {
            // Sneakless: never crouch on purpose. This tick moves the body by the carried motion plus ~0.1 of key
            // acceleration; the ray is on the face only for h up to ~0.28, so a step that would carry past it is
            // shortened by easing off the keys for a tick (the moonwalker's stutter), and only a real miss (ray not
            // on the face with the foot about to leave the block) gets the save-crouch.
            double dKeys = vAlong + 0.1 * Math.max(0, lastProg);
            boolean sneak;
            // (0.28: a little further and the ray passes under the face, a lot further and the foot leaves the block)
            if (h + dKeys <= 0.28) sneak = false;
            else if (clicked || h + vAlong <= 0.28) { ctx.motor.move(0, 0); sneak = false; }
            else sneak = true;
            ctx.motor.sneak(sneak);
            return;
        }
        boolean sneak;
        switch (crouch) {
            case HOLD:
                sneak = !forgetSneak || h < -0.3;
                break;
            case TAP:
                // walk uncrouched, crouch from just past the edge until the block is down (+ a beat after)
                if (unsneakDelay > 0) { unsneakDelay--; sneak = true; }
                else sneak = h > (tech == Tech.NINJA ? 0.05 : 0.08);
                break;
            case NONE:
            default:
                sneak = false;
                break;
        }
        // last-instant failsafe every non-beginner has: crouch if about to fall off with no block placed. The hitbox is
        // 0.3 wide each side, so at 0.16 past the edge there is still 0.14 of foot on the block for sneak to hold.
        // (judged on where this tick's move takes us: a moonwalking breezily bridger covers ~0.18 per tick)
        double hNext = h + vAlong * 1.2 + 0.03;
        if (!sneak && (h > 0.16 || hNext > 0.22) && !(tech == Tech.NOOB && forgetSneak)) sneak = true;
        ctx.motor.sneak(sneak);
    }

    public static BlockFace faceOf(int dx, int dz) {
        if (dx > 0) return BlockFace.EAST;
        if (dx < 0) return BlockFace.WEST;
        if (dz > 0) return BlockFace.SOUTH;
        return BlockFace.NORTH;
    }
}
