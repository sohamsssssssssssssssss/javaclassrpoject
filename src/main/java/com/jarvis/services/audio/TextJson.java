package com.jarvis.services.audio;

import java.util.Objects;

/** Minimal extraction of string/array fields from Vosk result JSON, without adding a JSON dependency. */
final class TextJson {

    private TextJson() {
    }

    /** Value of the first {@code "key":"value"} occurrence, or empty when absent. */
    static String extractText(String json, String key) {
        Objects.requireNonNull(json, "json");
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) {
            return "";
        }
        int colon = json.indexOf(':', k + needle.length());
        int q1 = json.indexOf('"', colon + 1);
        int q2 = json.indexOf('"', q1 + 1);
        if (colon < 0 || q1 < 0 || q2 < 0) {
            return "";
        }
        return json.substring(q1 + 1, q2);
    }

    /** Value of the {@code "text"} field, or empty when absent. */
    static String extractText(String json) {
        return extractText(json, "text");
    }

    /** Parsed {@code "key":[...]} double array, or null when absent. */
    static double[] extractArray(String json, String key) {
        Objects.requireNonNull(json, "json");
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) {
            return null;
        }
        int open = json.indexOf('[', k);
        int close = json.indexOf(']', open);
        if (open < 0 || close < 0) {
            return null;
        }
        String[] parts = json.substring(open + 1, close).split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String token = parts[i].trim();
            if (token.isEmpty()) {
                return null;
            }
            try {
                out[i] = Double.parseDouble(token);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }
}
