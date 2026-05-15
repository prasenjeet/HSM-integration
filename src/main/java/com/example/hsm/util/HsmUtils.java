package com.example.hsm.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stateless utility helpers for the HSM integration layer.
 */
public final class HsmUtils {

    private static final HexFormat HEX = HexFormat.of();

    private HsmUtils() {}

    // -------------------------------------------------------------------------
    // Hex encoding
    // -------------------------------------------------------------------------

    /** Encodes {@code bytes} as a lowercase hex string. */
    public static String toHex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    /** Decodes a hex string to bytes. */
    public static byte[] fromHex(String hex) {
        return HEX.parseHex(hex);
    }

    // -------------------------------------------------------------------------
    // Digest helpers
    // -------------------------------------------------------------------------

    /** Returns the SHA-256 digest of {@code data}. */
    public static byte[] sha256(byte[] data) {
        return digest("SHA-256", data);
    }

    /** Returns the SHA-384 digest of {@code data}. */
    public static byte[] sha384(byte[] data) {
        return digest("SHA-384", data);
    }

    /** Returns the SHA-512 digest of {@code data}. */
    public static byte[] sha512(byte[] data) {
        return digest("SHA-512", data);
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            // All JDK implementations are required to support SHA-256/384/512
            throw new IllegalStateException("Required digest algorithm unavailable: " + algorithm, e);
        }
    }

    // -------------------------------------------------------------------------
    // Pretty-print helpers
    // -------------------------------------------------------------------------

    /**
     * Formats a byte array as a hex dump with an optional label.
     * Useful for logging key material in non-production environments.
     */
    public static String hexDump(String label, byte[] bytes) {
        return String.format("%s (%d bytes): %s", label, bytes.length, toHex(bytes));
    }

    /** Returns a truncated hex preview (first {@code maxBytes} bytes followed by "…"). */
    public static String truncatedHex(byte[] bytes, int maxBytes) {
        if (bytes.length <= maxBytes) return toHex(bytes);
        byte[] preview = new byte[maxBytes];
        System.arraycopy(bytes, 0, preview, 0, maxBytes);
        return toHex(preview) + "…(" + bytes.length + " bytes total)";
    }

    // -------------------------------------------------------------------------
    // Timing-safe comparison
    // -------------------------------------------------------------------------

    /**
     * Compares two byte arrays in constant time to prevent timing-oracle attacks.
     * Returns true only when arrays have the same length and identical content.
     */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= (a[i] ^ b[i]);
        }
        return diff == 0;
    }
}
