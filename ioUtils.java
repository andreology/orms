package com.acme.pdf.util;

import java.io.*;

public final class IoUtils {
    private IoUtils() {}

    public static String slurp(InputStream in) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    public static String sanitizeKey(String raw) {
        String k = raw == null ? "" : raw.trim().replaceAll("[^A-Za-z0-9_]+", "_");
        if (k.isEmpty()) k = "field_" + System.nanoTime();
        if (Character.isDigit(k.charAt(0))) k = "_" + k;
        return k;
    }
}
