package net.h2020.watchdog.alerts;

import org.bukkit.ChatColor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Append-only plugins/Hypixel2020/watchdog.log, written from a single background thread. */
public final class WatchdogLog {
    private final File file;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Watchdog-Log");
        t.setDaemon(true);
        return t;
    });
    private PrintWriter out;

    public WatchdogLog(File dataFolder) {
        this.file = new File(dataFolder, "watchdog.log");
    }

    public void log(String kind, String message) {
        final String line = "[" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()) + "] [" + kind + "] "
                + ChatColor.stripColor(message);
        try {
            writer.execute(() -> {
                try {
                    if (out == null) {
                        File dir = file.getParentFile();
                        if (dir != null && !dir.exists()) dir.mkdirs();
                        out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8));
                    }
                    out.println(line);
                    out.flush();
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {} // executor shut down
    }

    public void close() {
        writer.shutdown();
        try { writer.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        if (out != null) out.close();
    }
}
