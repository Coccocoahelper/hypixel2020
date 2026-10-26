package net.h2020.watchdog.punish;

import java.util.Random;

/** Hypixel (summer 2020) temporary ban disconnect screen. */
public final class BanScreen {
    private BanScreen() {}

    private static final Random RANDOM = new Random();

    public static String screen(long remainingMs, String banId) {
        return "§cYou are temporarily banned for §f" + duration(remainingMs) + " §cfrom this server!\n"
                + "\n"
                + "§7Reason: §fCheating through the use of unfair game advantages.\n"
                + "§7Find out more: §b§nhttps://www.hypixel.net/appeal\n"
                + "\n"
                + "§7Ban ID: §f#" + banId + "\n"
                + "§7Sharing your Ban ID may affect the processing of your appeal!";
    }

    /** "29d 23h 59m 59s" */
    public static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        long d = s / 86400; s %= 86400;
        long h = s / 3600; s %= 3600;
        long m = s / 60; s %= 60;
        return d + "d " + h + "h " + m + "m " + s + "s";
    }

    /** 8 uppercase hex chars, e.g. 8A3F21C9. */
    public static String newBanId() {
        return String.format("%08X", RANDOM.nextInt() & 0xFFFFFFFFL);
    }
}
