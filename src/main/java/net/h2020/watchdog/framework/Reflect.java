package net.h2020.watchdog.framework;

import net.minecraft.server.v1_8_R3.PacketPlayInUseEntity;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityVelocity;
import net.minecraft.server.v1_8_R3.PacketPlayOutOpenWindow;

import java.lang.reflect.Field;

/** Cached reflective accessors for private NMS packet fields (names verified against spigot-1.8.8 v1_8_R3). */
public final class Reflect {
    private Reflect() {}

    private static final Field USE_ENTITY_ID = field(PacketPlayInUseEntity.class, "a");
    private static final Field VELOCITY_ID = field(PacketPlayOutEntityVelocity.class, "a");
    private static final Field VELOCITY_X = field(PacketPlayOutEntityVelocity.class, "b");
    private static final Field VELOCITY_Y = field(PacketPlayOutEntityVelocity.class, "c");
    private static final Field VELOCITY_Z = field(PacketPlayOutEntityVelocity.class, "d");
    private static final Field OPEN_WINDOW_ID = field(PacketPlayOutOpenWindow.class, "a");
    private static final Field OPEN_WINDOW_TYPE = field(PacketPlayOutOpenWindow.class, "b");

    public static Field field(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private static int getInt(Field f, Object o, int def) {
        if (f == null) return def;
        try { return f.getInt(o); } catch (Exception e) { return def; }
    }

    public static int useEntityTarget(PacketPlayInUseEntity p) { return getInt(USE_ENTITY_ID, p, -1); }

    public static int velocityEntity(PacketPlayOutEntityVelocity p) { return getInt(VELOCITY_ID, p, -1); }

    /** Velocity in blocks/tick (packet stores 1/8000 units). */
    public static double[] velocity(PacketPlayOutEntityVelocity p) {
        return new double[] {getInt(VELOCITY_X, p, 0) / 8000.0, getInt(VELOCITY_Y, p, 0) / 8000.0, getInt(VELOCITY_Z, p, 0) / 8000.0};
    }

    public static int openWindowId(PacketPlayOutOpenWindow p) { return getInt(OPEN_WINDOW_ID, p, -1); }

    public static String openWindowType(PacketPlayOutOpenWindow p) {
        if (OPEN_WINDOW_TYPE == null) return "";
        try { Object o = OPEN_WINDOW_TYPE.get(p); return o == null ? "" : o.toString(); } catch (Exception e) { return ""; }
    }
}
