package net.h2020.core.lobby.npc;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.h2020.core.CoreUtil;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.MinecraftServer;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityDestroy;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityHeadRotation;
import net.minecraft.server.v1_8_R3.PacketPlayOutNamedEntitySpawn;
import net.minecraft.server.v1_8_R3.PacketPlayOutPlayerInfo;
import net.minecraft.server.v1_8_R3.PlayerInteractManager;
import net.minecraft.server.v1_8_R3.WorldServer;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Packet-only player NPC (no AI, never added to the world). Shown with the 1.8 sequence: tab ADD (skin), spawn,
 * head rotation, then tab REMOVE a moment later so it never appears in the tab list.
 */
public final class PacketNpc {
    private final EntityPlayer entity;
    private final String profileName;
    private final Location location;

    public PacketNpc(String profileName, Location loc, String skinValue, String skinSignature) {
        this.profileName = profileName.length() > 16 ? profileName.substring(0, 16) : profileName;
        this.location = loc.clone();
        MinecraftServer server = ((CraftServer) org.bukkit.Bukkit.getServer()).getServer();
        WorldServer ws = ((CraftWorld) loc.getWorld()).getHandle();
        UUID id = new UUID(UUID.randomUUID().getMostSignificantBits() & ~0xF000L | 0x2000L, UUID.randomUUID().getLeastSignificantBits());
        GameProfile gp = new GameProfile(id, this.profileName);
        if (skinValue != null && !skinValue.isEmpty()) gp.getProperties().put("textures", new Property("textures", skinValue, skinSignature));
        entity = new EntityPlayer(server, ws, gp, new PlayerInteractManager(ws));
        entity.setLocation(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        entity.getDataWatcher().watch(10, (byte) 0x7F); // all skin layers
    }

    public int entityId() { return entity.getId(); }
    public String profileName() { return profileName; }
    public Location location() { return location.clone(); }

    public void show(Player p) {
        CoreUtil.send(p, new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, entity));
        CoreUtil.send(p, new PacketPlayOutNamedEntitySpawn(entity));
        CoreUtil.send(p, new PacketPlayOutEntityHeadRotation(entity, (byte) (location.getYaw() * 256f / 360f)));
    }

    /** Second half of show(): drop the tab entry once the client has the skin. */
    public void removeFromTab(Player p) {
        CoreUtil.send(p, new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, entity));
    }

    public void hide(Player p) {
        CoreUtil.send(p, new PacketPlayOutEntityDestroy(entity.getId()));
        removeFromTab(p);
    }
}
