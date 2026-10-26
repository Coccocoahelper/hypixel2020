package net.h2020.watchdog.checks.moving;

/**
 * Snapshot of one serverbound flying packet (= one client tick), captured on the packet thread and evaluated on
 * the main thread once the server has processed it (PlayerMoveEvent or the tick task).
 */
final class FlyingEntry {
    final long seq;
    /** Wall-clock arrival time (ms). */
    final long time;
    final double x, y, z;
    final float yaw, pitch;
    /** Client-claimed onGround. */
    final boolean ground;
    final boolean hasPos, hasLook;
    /** Arrived while a server teleport was unconfirmed: the server ignores it, so do we. */
    final boolean ignored;
    /** The C06 that confirms a pending server teleport. */
    final boolean confirm;

    FlyingEntry(long seq, long time, double x, double y, double z, float yaw, float pitch, boolean ground,
                boolean hasPos, boolean hasLook, boolean ignored, boolean confirm) {
        this.seq = seq; this.time = time; this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        this.ground = ground; this.hasPos = hasPos; this.hasLook = hasLook; this.ignored = ignored; this.confirm = confirm;
    }
}
