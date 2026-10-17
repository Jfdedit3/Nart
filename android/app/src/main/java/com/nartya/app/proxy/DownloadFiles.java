package com.nartya.app.proxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Integrity checks shared by the downloader and JVM regression tests. */
final class DownloadFiles {
    private static final Pattern RANGE = Pattern.compile("^bytes (\\d+)-(\\d+)/(\\d+)$");
    static boolean validRange(String value, long start, long end) {
        if (value == null) return false;
        Matcher match = RANGE.matcher(value);
        if (!match.matches()) return false;
        try {
            long actualStart = Long.parseLong(match.group(1));
            long actualEnd = Long.parseLong(match.group(2));
            long total = Long.parseLong(match.group(3));
            return actualStart == start && actualEnd >= start && actualEnd < total
                && (end < 0 ? actualEnd == total - 1 : actualEnd == end);
        } catch (NumberFormatException error) { return false; }
    }
    static void requireLength(long actual, long expected) throws IOException {
        if (actual <= 0 || (expected >= 0 && actual != expected)) throw new IOException("Téléchargement incomplet");
    }
    static void commit(File partial, File destination) throws IOException {
        if (!partial.isFile() || partial.length() == 0) throw new IOException("Fichier téléchargé vide");
        try (FileOutputStream output = new FileOutputStream(partial, true)) { output.getFD().sync(); }
        if (!partial.renameTo(destination)) throw new IOException("Impossible de finaliser le fichier téléchargé");
    }
    static String identity(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte part : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", part & 0xff));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String readText(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            if (file.length() > 4096) return "";
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] bytes = new byte[1024]; int n;
            while ((n = input.read(bytes)) != -1) out.write(bytes, 0, n);
            return out.toString("UTF-8");
        } catch (IOException error) { return ""; }
    }
    static void writeText(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }
}
