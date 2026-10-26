package net.h2020.watchdog.checks.fight;

import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.PacketCheck;
import net.h2020.watchdog.framework.PacketRouter;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.Reflect;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PacketPlayInArmAnimation;
import net.minecraft.server.v1_8_R3.PacketPlayInFlying;
import net.minecraft.server.v1_8_R3.PacketPlayInUseEntity;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Fight check group (NCP FightListener): packet part (Speed, NoSwing, SelfHit, ClickPattern, Aim GCD, Critical
 * sampling, GodMode) and the damage-event part (Reach, Direction, Angle, Critical, Aim centre, FastHeal).
 */
public final class FightChecks implements Listener, PacketCheck {
    private final WatchdogServiceImpl service;
    private final Reach reach;
    private final Direction direction;
    private final Angle angle;
    private final Critical critical;
    private final SelfHit selfHit;
    private final GodMode godMode;
    private final FastHeal fastHeal;
    private final Speed speed;
    private final NoSwing noSwing;
    private final Aim aim;
    private final ClickPattern clickPattern;

    private FightChecks(WatchdogServiceImpl s) {
        this.service = s;
        reach = new Reach(s);
        direction = new Direction(s);
        angle = new Angle(s);
        critical = new Critical(s);
        selfHit = new SelfHit(s);
        godMode = new GodMode(s);
        fastHeal = new FastHeal(s);
        speed = new Speed(s);
        noSwing = new NoSwing(s);
        aim = new Aim(s);
        clickPattern = new ClickPattern(s);
    }

    public static void register(WatchdogServiceImpl service, PacketRouter router, JavaPlugin plugin) {
        FightChecks f = new FightChecks(service);
        router.add(f);
        service.registerListener(f);
    }

    // ------------------------------------------------------------------ packets

    @Override
    public boolean onPacket(Player p, PlayerData d, Packet<?> packet, long now) {
        if (packet instanceof PacketPlayInArmAnimation) {
            noSwing.onSwing(d);
            if (!d.swingConsumed && !d.dig.digging) clickPattern.onClick(p, d, now);
            return true;
        }
        if (packet instanceof PacketPlayInUseEntity) {
            PacketPlayInUseEntity u = (PacketPlayInUseEntity) packet;
            if (u.a() != PacketPlayInUseEntity.EnumEntityUseAction.ATTACK) return true;
            int target = Reflect.useEntityTarget(u);
            d.attacksThisTick++;
            d.lastAttackMs = now;
            d.fight.lastTarget = target;
            if (target == p.getEntityId()) return !selfHit.check(p, "attacked own entity id");
            noSwing.onAttack(d);
            critical.sample(d, target, now);
            godMode.onAttacked(target, now);
            return !speed.check(p, d, now);
        }
        return true;
    }

    @Override
    public boolean onFlying(Player p, PlayerData d, PacketPlayInFlying f, long now) {
        noSwing.onTickEnd(p, d, now);
        aim.onRotation(p, d, f.h(), now);
        return true;
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player) || e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        Player attacker = (Player) e.getDamager();
        Entity victim = e.getEntity();
        PlayerData d = service.data(attacker);
        long now = System.currentTimeMillis();
        boolean cancel = false;
        synchronized (d) {
            if (attacker.equals(victim)) {
                cancel = selfHit.check(attacker, "melee self damage");
            } else if (attacker.getGameMode() != GameMode.SPECTATOR) {
                if (now < d.fight.noSwingCancelUntil) cancel = true;
                if (reach.check(attacker, d, victim, now)) cancel = true;
                if (direction.check(attacker, d, victim, now)) cancel = true;
                if (angle.check(attacker, d, victim, now)) cancel = true;
                if (critical.check(attacker, d, victim.getEntityId())) cancel = true;
                aim.onAttack(attacker, d, victim, now);
            }
        }
        if (cancel) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player) || e.getRegainReason() != EntityRegainHealthEvent.RegainReason.SATIATED) return;
        Player p = (Player) e.getEntity();
        PlayerData d = service.data(p);
        boolean cancel;
        synchronized (d) { cancel = fastHeal.check(p, d, System.currentTimeMillis()); }
        if (cancel) e.setCancelled(true);
    }
}
