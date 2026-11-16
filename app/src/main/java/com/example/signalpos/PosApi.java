package com.example.signalpos;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpCookie;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

/** JSON-only transport. No browser, HTML, JavaScript, or database credentials. */
final class PosApi {
    private final SharedPreferences preferences;
    final String base;
    PosApi(Context context, String base) {
        this.base = base;
        preferences = context.getSharedPreferences("native-session", Context.MODE_PRIVATE);
    }
    JSONObject request(String path, JSONObject body) throws Exception {
        return request(path, body == null ? "GET" : "POST", body);
    }
    JSONObject request(String path, String method, JSONObject body) throws Exception {
        byte[] bytes = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        return request(path, method, "application/json", bytes);
    }
    JSONObject uploadThumbnail(String productId, String type, InputStream image) throws Exception {
        if (!productId.matches("[0-9a-fA-F-]{36}") || !java.util.Arrays.asList("image/jpeg", "image/png", "image/webp").contains(type))
            throw new Exception("Choose a JPG, PNG, or WebP image");
        String boundary = java.util.UUID.randomUUID().toString();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"productId\"\r\n\r\n" + productId
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"product-image\"\r\n"
                + "Content-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] buffer = new byte[8192];
        int size;
        while ((size = image.read(buffer)) != -1) {
            if (body.size() + size > 6 * 1024 * 1024) throw new Exception("Image exceeds the tablet upload limit");
            body.write(buffer, 0, size);
        }
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return request("/api/manage/thumbnail", "POST", "multipart/form-data; boundary=" + boundary, body.toByteArray());
    }
    private JSONObject request(String path, String method, String contentType, byte[] body) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URI(base + path).toURL().openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false); connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        String cookie = preferences.getString(base, "");
        if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }
            int status = connection.getResponseCode();
            for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                if ("Set-Cookie".equalsIgnoreCase(entry.getKey())) {
                    for (String header : entry.getValue()) {
                        for (HttpCookie parsed : HttpCookie.parse(header)) {
                            if ("signal_pos_session".equals(parsed.getName())) {
                                // Runs on the I/O executor; persist the session before reporting login success.
                                if (!preferences.edit().putString(base, parsed.getMaxAge() == 0 ? "" : parsed.getName() + "=" + parsed.getValue()).commit())
                                    throw new Exception("Unable to save the sign-in session on this tablet");
                            }
                        }
                    }
                }
            }
            InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) throw new Exception("Server returned HTTP " + status);
            String text;
            try (InputStream input = stream; java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int size;
                while ((size = input.read(buffer)) != -1) {
                    if (bytes.size() + size > 12000000) throw new Exception("Server response is too large");
                    bytes.write(buffer, 0, size);
                }
                text = bytes.toString("UTF-8");
            }
            JSONObject result;
            try { result = new JSONObject(text); }
            catch (Exception error) { throw new Exception("The address did not return a Signal POS API response"); }
            if (status < 200 || status >= 300) throw new Exception(result.optString("error", "Request failed: " + status));
            return result;
        } finally { connection.disconnect(); }
    }
}
