package com.example.moggsync;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

public final class TelegramReporter {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private TelegramReporter() {}

    private static boolean ready(MoggConfig c) {
        return c.telegramEnabled && !c.TELEGRAM_BOT_TOKEN.isBlank() && !c.CHAT_ID.isBlank();
    }

    public static void sendPhoto(MoggConfig c, byte[] png, String caption) {
        if (!ready(c)) return;
        try {
            String boundary = "----Mogg" + UUID.randomUUID().toString().replace("-", "");
            ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + 1024);

            field(out, boundary, "chat_id", c.CHAT_ID);
            field(out, boundary, "caption", caption.length() > 1000 ? caption.substring(0, 1000) : caption);

            write(out, "--" + boundary + "\r\n");
            write(out, "Content-Disposition: form-data; name=\"photo\"; filename=\"screenshot.png\"\r\n");
            write(out, "Content-Type: image/png\r\n\r\n");
            out.write(png);
            write(out, "\r\n--" + boundary + "--\r\n");

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + c.TELEGRAM_BOT_TOKEN + "/sendPhoto"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                    .build();
            dispatch(req);
        } catch (Exception e) {
            MoggSyncClient.LOGGER.error("Telegram photo build failed: {}", e.getClass().getSimpleName());
        }
    }

    public static void sendMessage(MoggConfig c, String text) {
        if (!ready(c)) return;
        String body = "chat_id=" + URLEncoder.encode(c.CHAT_ID, StandardCharsets.UTF_8)
                + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/bot" + c.TELEGRAM_BOT_TOKEN + "/sendMessage"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        dispatch(req);
    }

    private static void dispatch(HttpRequest req) {
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(r -> {
                    if (r.statusCode() != 200)
                        MoggSyncClient.LOGGER.warn("Telegram HTTP {}: {}", r.statusCode(), r.body());
                })
                .exceptionally(t -> {
                    MoggSyncClient.LOGGER.warn("Telegram request failed: {}", t.getClass().getSimpleName());
                    return null;
                });
    }

    private static void field(ByteArrayOutputStream o, String b, String name, String value) throws Exception {
        write(o, "--" + b + "\r\n");
        write(o, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        write(o, value + "\r\n");
    }

    private static void write(ByteArrayOutputStream o, String s) throws Exception {
        o.write(s.getBytes(StandardCharsets.UTF_8));
    }
}
