package com.nishant.airwidget;

import java.security.MessageDigest;

final class Crypto {
    static byte[] md5(byte[]... parts) { return digest("MD5", parts); }
    static byte[] sha1(byte[]... parts) { return digest("SHA-1", parts); }
    static byte[] sha256(byte[]... parts) { return digest("SHA-256", parts); }

    private static byte[] digest(String alg, byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance(alg);
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** RC4 with the first 1024 keystream bytes discarded (what Xiaomi's API uses). Symmetric. */
    static byte[] rc4(byte[] key, byte[] data) {
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) s[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + (key[i % key.length] & 0xff)) & 0xff;
            int t = s[i]; s[i] = s[j]; s[j] = t;
        }
        int a = 0, b = 0;
        for (int n = 0; n < 1024; n++) {
            a = (a + 1) & 0xff;
            b = (b + s[a]) & 0xff;
            int t = s[a]; s[a] = s[b]; s[b] = t;
        }
        byte[] out = new byte[data.length];
        for (int n = 0; n < data.length; n++) {
            a = (a + 1) & 0xff;
            b = (b + s[a]) & 0xff;
            int t = s[a]; s[a] = s[b]; s[b] = t;
            out[n] = (byte) (data[n] ^ s[(s[a] + s[b]) & 0xff]);
        }
        return out;
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }

    static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
