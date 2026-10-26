package net.h2020.watchdog.checks.moving;

import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.PacketPlayOutBlockChange;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityVelocity;
import net.minecraft.server.v1_8_R3.PacketPlayOutExplosion;
import net.minecraft.server.v1_8_R3.PacketPlayOutKeepAlive;
import net.minecraft.server.v1_8_R3.PacketPlayOutMultiBlockChange;
import net.minecraft.server.v1_8_R3.PacketPlayOutPosition;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Reflective access to private fields of clientbound packets (names verified against spigot-1.8.8 v1_8_R3). */
final class PacketFields {
    private static final Field POS_X = field(PacketPlayOutPosition.class, "a");
    private static final Field POS_Y = field(PacketPlayOutPosition.class, "b");
    private static final Field POS_Z = field(PacketPlayOutPosition.class, "c");
    private static final Field POS_FLAGS = field(PacketPlayOutPosition.class, "f");
    private static final Field VEL_ID = field(PacketPlayOutEntityVelocity.class, "a");
    private static final Field VEL_X = field(PacketPlayOutEntityVelocity.class, "b");
    private static final Field VEL_Y = field(PacketPlayOutEntityVelocity.class, "c");
    private static final Field VEL_Z = field(PacketPlayOutEntityVelocity.class, "d");
    private static final Field EXP_BLOCKS = field(PacketPlayOutExplosion.class, "e");
    private static final Field EXP_X = field(PacketPlayOutExplosion.class, "f");
    private static final Field EXP_Y = field(PacketPlayOutExplosion.class, "g");
    private static final Field EXP_Z = field(PacketPlayOutExplosion.class, "h");
    private static final Field KA_ID = field(PacketPlayOutKeepAlive.class, "a");
    private static final Field BC_POS = field(PacketPlayOutBlockChange.class, "a");
    private static final Field MBC_INFOS = field(PacketPlayOutMultiBlockChange.class, "b");

    private PacketFields() {}

    private static Field field(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object get(Field f, Object o) {
        if (f == null) return null;
        try { return f.get(o); } catch (Throwable t) { return null; }
    }

    private static double d(Field f, Object o) {
        Object v = get(f, o);
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    static double posX(PacketPlayOutPosition p) { return d(POS_X, p); }
    static double posY(PacketPlayOutPosition p) { return d(POS_Y, p); }
    static double posZ(PacketPlayOutPosition p) { return d(POS_Z, p); }

    /** Relative flags by name (X, Y, Z, Y_ROT, X_ROT). */
    @SuppressWarnings("unchecked")
    static Set<? extends Enum<?>> posFlags(PacketPlayOutPosition p) {
        Object v = get(POS_FLAGS, p);
        return v instanceof Set ? (Set<? extends Enum<?>>) v : Collections.<Enum<?>>emptySet();
    }

    static int velocityEntity(PacketPlayOutEntityVelocity p) { return (int) d(VEL_ID, p); }
    /** Velocity in blocks/tick (the packet carries 1/8000 units). */
    static double velX(PacketPlayOutEntityVelocity p) { return d(VEL_X, p) / 8000.0; }
    static double velY(PacketPlayOutEntityVelocity p) { return d(VEL_Y, p) / 8000.0; }
    static double velZ(PacketPlayOutEntityVelocity p) { return d(VEL_Z, p) / 8000.0; }

    static double explosionX(PacketPlayOutExplosion p) { return d(EXP_X, p); }
    static double explosionY(PacketPlayOutExplosion p) { return d(EXP_Y, p); }
    static double explosionZ(PacketPlayOutExplosion p) { return d(EXP_Z, p); }

    @SuppressWarnings("unchecked")
    static List<BlockPosition> explosionBlocks(PacketPlayOutExplosion p) {
        Object v = get(EXP_BLOCKS, p);
        return v instanceof List ? (List<BlockPosition>) v : Collections.<BlockPosition>emptyList();
    }

    static int keepAliveId(PacketPlayOutKeepAlive p) { return (int) d(KA_ID, p); }

    static BlockPosition blockChangePos(PacketPlayOutBlockChange p) {
        Object v = get(BC_POS, p);
        return v instanceof BlockPosition ? (BlockPosition) v : null;
    }

    static PacketPlayOutMultiBlockChange.MultiBlockChangeInfo[] multiBlockInfos(PacketPlayOutMultiBlockChange p) {
        Object v = get(MBC_INFOS, p);
        return v instanceof PacketPlayOutMultiBlockChange.MultiBlockChangeInfo[]
                ? (PacketPlayOutMultiBlockChange.MultiBlockChangeInfo[]) v : null;
    }
}
