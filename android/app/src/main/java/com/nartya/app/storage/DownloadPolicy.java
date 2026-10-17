package com.nartya.app.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import java.io.File;
import java.io.IOException;

/** Device policy is native so background transfers obey the same limits as the UI. */
public final class DownloadPolicy {
    public static final long RESERVE_BYTES = 100L * 1024 * 1024;
    private final Context context;
    private final File root;
    private final SharedPreferences settings;
    private long lastCheck = -1000;
    private long cachedUsed;
    public static final class WifiRequired extends IOException {
        public WifiRequired() { super("Wi-Fi requis"); }
    }
    public DownloadPolicy(Context context, File root) {
        this.context = context;
        this.root = root;
        settings = context.getSharedPreferences("download-policy", Context.MODE_PRIVATE);
    }
    public boolean wifiOnly() { return settings.getBoolean("wifiOnly", false); }
    public long quotaBytes() { return settings.getLong("quotaBytes", 0); }
    public synchronized void configure(boolean wifiOnly, long quotaBytes) {
        settings.edit().putBoolean("wifiOnly", wifiOnly).putLong("quotaBytes", Math.max(0, quotaBytes)).apply();
        lastCheck = -1000;
    }
    public boolean hasWifi() {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = manager.getNetworkCapabilities(manager.getActiveNetwork());
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }
    public synchronized void check(long additionalBytes) throws IOException {
        if (wifiOnly() && !hasWifi()) throw new WifiRequired();
        long now = android.os.SystemClock.elapsedRealtime();
        if (additionalBytes == 0 && now - lastCheck < 1000) return;
        cachedUsed = size(root);
        lastCheck = now;
        if (root.getUsableSpace() < RESERVE_BYTES + additionalBytes) throw new IOException("Espace disque insuffisant (100 Mo de réserve)");
        if (quotaBytes() > 0 && cachedUsed + additionalBytes > quotaBytes()) throw new IOException("Quota de stockage atteint");
    }
    public synchronized long usedBytes() { cachedUsed = size(root); return cachedUsed; }
    public long freeBytes() { return root.getUsableSpace(); }
    private static long size(File file) {
        if (file.isFile()) return file.length();
        long total = 0; File[] children = file.listFiles();
        if (children != null) for (File child : children) total += size(child);
        return total;
    }
}
