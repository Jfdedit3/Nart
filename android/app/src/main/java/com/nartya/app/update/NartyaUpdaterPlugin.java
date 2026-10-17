package com.nartya.app.update;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * La confirmation système reste obligatoire. Avant de remettre le fichier au Package Installer,
 * on vérifie paquet, hausse de version, signature du build installé et SHA-256 s'il est fourni.
 */
@CapacitorPlugin(name = "NartyaUpdater")
public class NartyaUpdaterPlugin extends Plugin {
    private static final AtomicBoolean installing = new AtomicBoolean(false);
    private volatile Call activeCall;
    private volatile boolean cancelled;

    @PluginMethod
    public void cancel(PluginCall call) {
        cancelled = true;
        Call current = activeCall;
        if (current != null) current.cancel();
        call.resolve(new JSObject().put("cancelled", true));
    }

    private void progress(String state, long received, long total) {
        notifyListeners("progress", new JSObject().put("state", state)
            .put("received", received).put("total", total)
            .put("percent", total > 0 ? Math.min(100, received * 100 / total) : -1));
    }

    private static final long MAX_APK_BYTES = 250L * 1024L * 1024L;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build();

    @PluginMethod
    public void install(PluginCall call) {
        String url = call.getString("url", "");
        String expectedSha256 = call.getString("sha256", "");
        HttpUrl parsed = HttpUrl.parse(url);
        if (!isAllowedDownloadUrl(parsed)) {
            call.reject("URL de mise à jour non autorisée");
            return;
        }
        if (!expectedSha256.isEmpty() && !expectedSha256.matches("(?i)^[a-f0-9]{64}$")) {
            call.reject("Empreinte SHA-256 invalide");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && !getContext().getPackageManager().canRequestPackageInstalls()) {
            getActivity().runOnUiThread(() -> {
                Intent permission = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getContext().getPackageName())
                );
                getActivity().startActivity(permission);
                JSObject result = new JSObject();
                result.put("permissionRequired", true);
                call.resolve(result);
            });
            return;
        }

        if (!installing.compareAndSet(false, true)) {
            call.reject("Une mise à jour est déjà en cours");
            return;
        }
        cancelled = false;
        new Thread(() -> downloadAndInstall(call, parsed, expectedSha256), "nartya-apk-update").start();
    }

    private boolean isAllowedDownloadUrl(HttpUrl url) {
        if (url == null) return false;
        if ("https".equalsIgnoreCase(url.scheme())) return true;
        // Répétitions locales uniquement : jamais en release.
        return isDebuggable()
            && "http".equalsIgnoreCase(url.scheme())
            && ("127.0.0.1".equals(url.host()) || "localhost".equalsIgnoreCase(url.host()));
    }

    private boolean isDebuggable() {
        return (getContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private void downloadAndInstall(PluginCall call, HttpUrl url, String expectedSha256) {
        File updateDir = new File(getContext().getCacheDir(), "updates");
        File apk = null;
        try {
            if (!updateDir.exists() && !updateDir.mkdirs()) {
                throw new IllegalStateException("Stockage temporaire indisponible");
            }

            apk = File.createTempFile("nartya-update-", ".apk", updateDir);
            // Installer reads asynchronously: preserve recent successful APKs, remove old ones.
            File[] old = updateDir.listFiles();
            if (old != null) for (File file : old) {
                if (!file.equals(apk) && file.lastModified() < System.currentTimeMillis() - 86_400_000L) file.delete();
            }
            Request request = new Request.Builder().url(url).get().build();
            activeCall = client.newCall(request);
            progress("downloading", 0, -1);
            try (Response response = activeCall.execute()) {
                if (!isAllowedDownloadUrl(response.request().url())) throw new SecurityException("Redirection APK non autorisée");
                if (!response.isSuccessful()) throw new IllegalStateException("Téléchargement HTTP " + response.code());
                ResponseBody body = response.body();
                if (body == null) throw new IllegalStateException("Réponse APK vide");
                long announced = body.contentLength();
                if (announced > MAX_APK_BYTES) throw new IllegalStateException("APK trop volumineuse");

                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long written = 0;
                long lastProgress = 0;
                if (updateDir.getUsableSpace() < Math.max(announced, 0) + 100L * 1024 * 1024) throw new IllegalStateException("Espace insuffisant pour la mise à jour");
                byte[] buffer = new byte[64 * 1024];
                try (InputStream input = body.byteStream(); FileOutputStream output = new FileOutputStream(apk)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (cancelled) throw new java.io.IOException("Mise à jour annulée");
                        written += read;
                        if (written > MAX_APK_BYTES) throw new IllegalStateException("APK trop volumineuse");
                        digest.update(buffer, 0, read);
                        output.write(buffer, 0, read);
                        if (System.currentTimeMillis() - lastProgress > 250) {
                            lastProgress = System.currentTimeMillis();
                            progress("downloading", written, announced);
                        }
                    }
                    output.getFD().sync();
                }
                if (announced >= 0 && written != announced) throw new IllegalStateException("APK incomplète");
                if (written == 0) throw new IllegalStateException("APK vide");

                String actualSha256 = hex(digest.digest());
                if (!expectedSha256.isEmpty() && !actualSha256.equalsIgnoreCase(expectedSha256)) {
                    throw new SecurityException("Empreinte APK incorrecte");
                }
            }

            if (cancelled) throw new java.io.IOException("Mise à jour annulée");
            progress("verifying", 0, -1);
            PackageInfo candidate = validateCandidate(apk);
            launchInstaller(call, apk, candidate);
        } catch (Exception error) {
            // Un fichier partiel ou invalide ne doit jamais être réutilisé au prochain essai.
            //noinspection ResultOfMethodCallIgnored
            if (apk != null) apk.delete();
            progress(cancelled ? "cancelled" : "error", 0, -1);
            call.reject(error.getMessage() == null ? "Mise à jour impossible" : error.getMessage(), error);
        } finally {
            activeCall = null;
            installing.set(false);
        }
    }

    private PackageInfo validateCandidate(File apk) throws Exception {
        PackageManager manager = getContext().getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? PackageManager.GET_SIGNING_CERTIFICATES
            : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo installed = manager.getPackageInfo(getContext().getPackageName(), flags);
        if (candidate == null || !getContext().getPackageName().equals(candidate.packageName)) {
            throw new SecurityException("Cette APK n'est pas une mise à jour Nartya");
        }
        if (longVersion(candidate) <= longVersion(installed)) {
            throw new SecurityException("La version téléchargée n'est pas plus récente");
        }
        if (!signatureDigests(candidate).equals(signatureDigests(installed))) {
            throw new SecurityException("La signature de l'APK ne correspond pas à Nartya");
        }
        return candidate;
    }

    private void launchInstaller(PluginCall call, File apk, PackageInfo candidate) {
        Uri contentUri = FileProvider.getUriForFile(
            getContext(),
            getContext().getPackageName() + ".fileprovider",
            apk
        );
        getActivity().runOnUiThread(() -> {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(contentUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (cancelled || getActivity() == null || getActivity().isFinishing()) {
                apk.delete();
                progress("cancelled", 0, -1);
                call.reject("Installation annulée");
                return;
            }
            getActivity().startActivity(install);
            progress("ready", 0, -1);
            JSObject result = new JSObject();
            result.put("started", true);
            result.put("version", candidate.versionName);
            call.resolve(result);
        });
    }

    private static long longVersion(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    }

    private static Set<String> signatureDigests(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signatures = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        } else {
            signatures = info.signatures;
        }
        if (signatures == null || signatures.length == 0) throw new SecurityException("Signature APK absente");
        Set<String> digests = new HashSet<>();
        for (Signature signature : signatures) {
            digests.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        }
        return digests;
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return builder.toString();
    }
}
