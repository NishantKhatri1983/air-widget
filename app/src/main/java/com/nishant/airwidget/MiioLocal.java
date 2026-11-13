package com.nishant.airwidget;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Local miIO protocol (UDP 54321, AES-128-CBC keyed from the 16-byte device token). */
final class MiioLocal {
    private static int nextId = 1;

    /** Sends one RPC and returns the "result" array. UDP can drop a packet, so one retry on timeout. */
    static synchronized JSONArray call(String ip, String tokenHex, String method, JSONArray params, int timeoutMs)
            throws IOException {
        try {
            return callOnce(ip, tokenHex, method, params, timeoutMs);
        } catch (SocketTimeoutException e) {
            return callOnce(ip, tokenHex, method, params, timeoutMs);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("miIO timeout")) {
                return callOnce(ip, tokenHex, method, params, timeoutMs);
            }
            throw e;
        }
    }

    private static JSONArray callOnce(String ip, String tokenHex, String method, JSONArray params, int timeoutMs)
            throws IOException {
        if (tokenHex == null || tokenHex.length() != 32) throw new IOException("no usable local token");
        byte[] token = Crypto.hex(tokenHex);
        byte[] key = Crypto.md5(token);
        byte[] iv = Crypto.md5(key, token);

        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(timeoutMs);
            InetAddress addr = InetAddress.getByName(ip);

            // 1. hello -> device id + its clock
            byte[] hello = new byte[32];
            Arrays.fill(hello, (byte) 0xFF);
            hello[0] = 0x21; hello[1] = 0x31; hello[2] = 0x00; hello[3] = 0x20;
            s.send(new DatagramPacket(hello, hello.length, addr, 54321));
            byte[] buf = new byte[4096];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            s.receive(in);
            if (in.getLength() < 32) throw new IOException("short hello reply");
            long helloAt = System.currentTimeMillis();
            byte[] devId = Arrays.copyOfRange(buf, 8, 12);
            long stamp = ((buf[12] & 0xffL) << 24) | ((buf[13] & 0xffL) << 16) | ((buf[14] & 0xffL) << 8) | (buf[15] & 0xffL);

            // 2. request
            JSONObject req = new JSONObject();
            int id = nextId++;
            if (nextId > 9000) nextId = 1;
            try {
                req.put("id", id).put("method", method).put("params", params);
            } catch (JSONException e) {
                throw new IOException(e);
            }
            byte[] payload = aes(Cipher.ENCRYPT_MODE, key, iv, req.toString().getBytes(StandardCharsets.UTF_8));
            long st = stamp + (System.currentTimeMillis() - helloAt) / 1000 + 1;
            byte[] msg = new byte[32 + payload.length];
            msg[0] = 0x21; msg[1] = 0x31;
            msg[2] = (byte) (msg.length >> 8); msg[3] = (byte) msg.length;
            System.arraycopy(devId, 0, msg, 8, 4);
            msg[12] = (byte) (st >>> 24); msg[13] = (byte) (st >>> 16); msg[14] = (byte) (st >>> 8); msg[15] = (byte) st;
            System.arraycopy(token, 0, msg, 16, 16);
            System.arraycopy(payload, 0, msg, 32, payload.length);
            byte[] sum = Crypto.md5(msg);
            System.arraycopy(sum, 0, msg, 16, 16);
            s.send(new DatagramPacket(msg, msg.length, addr, 54321));

            // 3. reply (ignore stray packets)
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                try {
                    s.receive(in);
                } catch (SocketTimeoutException e) {
                    throw new IOException("miIO timeout");
                }
                if (in.getLength() > 32) break;
                if (System.currentTimeMillis() > deadline) throw new IOException("miIO timeout");
            }
            byte[] enc = Arrays.copyOfRange(buf, 32, in.getLength());
            byte[] plain = aes(Cipher.DECRYPT_MODE, key, iv, enc);
            String text = new String(plain, StandardCharsets.UTF_8).trim();
            int z = text.indexOf('\0');
            if (z >= 0) text = text.substring(0, z);
            try {
                JSONObject resp = new JSONObject(text);
                if (resp.has("error")) throw new IOException("miIO error " + resp.getJSONObject("error"));
                return resp.getJSONArray("result");
            } catch (JSONException e) {
                throw new IOException("bad miIO reply: " + XiaomiCloud.abbreviate(text));
            }
        }
    }

    private static byte[] aes(int mode, byte[] key, byte[] iv, byte[] data) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c.doFinal(data);
        } catch (Exception e) {
            throw new IOException("crypto: " + e);
        }
    }
}
