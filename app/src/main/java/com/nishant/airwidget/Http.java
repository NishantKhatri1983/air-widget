package com.nishant.airwidget;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Tiny HttpURLConnection wrapper (no redirects followed, identity encoding, cookies by hand). */
final class Http {
    static final class Resp {
        int code;
        String body;
        Map<String, List<String>> headers;

        /** Value of a Set-Cookie cookie by name, or null. */
        String cookie(String name) {
            if (headers == null) return null;
            List<String> sc = headers.get("Set-Cookie");
            if (sc == null) sc = headers.get("set-cookie");
            if (sc == null) return null;
            for (String line : sc) {
                String first = line.split(";", 2)[0].trim();
                if (first.startsWith(name + "=")) return first.substring(name.length() + 1);
            }
            return null;
        }
    }

    static Resp request(String method, String url, Map<String, String> headers, byte[] body,
                        int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
            }
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = c.getOutputStream()) { os.write(body); }
            }
            Resp r = new Resp();
            r.code = c.getResponseCode();
            r.headers = c.getHeaderFields();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
            return r;
        } finally {
            c.disconnect();
        }
    }

    static String get(String url, int timeoutMs) throws IOException {
        Resp r = request("GET", url, null, null, timeoutMs);
        if (r.code != 200) throw new IOException("HTTP " + r.code + " for " + url);
        return r.body;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }
}
