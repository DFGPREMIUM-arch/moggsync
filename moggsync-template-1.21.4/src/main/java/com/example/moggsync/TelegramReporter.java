package com.example.moggsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BiConsumer;

public final class TelegramReporter {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private TelegramReporter() {}

    public static boolean ready(MoggConfig c) {
        return c.telegramEnabled && !c.TELEGRAM_BOT_TOKEN.isBlank() && !c.CHAT_ID.isBlank();
    }

    // ------------------------------------------------------------------ sendPhoto (всегда HTML)

    public static void sendPhoto(MoggConfig c, byte[] png, String htmlCaption) {
        if (!ready(c)) return;
        try {
            String b = "----Mogg" + UUID.randomUUID().toString().replace("-","");
            ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + 2048);
            field(out,b,"chat_id",c.CHAT_ID);
            field(out,b,"parse_mode","HTML");
            String cap = htmlCaption.length()>1023 ? htmlCaption.substring(0,1023) : htmlCaption;
            field(out,b,"caption",cap);
            write(out,"--"+b+"\r\n");
            write(out,"Content-Disposition: form-data; name=\"photo\"; filename=\"screenshot.png\"\r\n");
            write(out,"Content-Type: image/png\r\n\r\n");
            out.write(png);
            write(out,"\r\n--"+b+"--\r\n");
            dispatch(HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot"+c.TELEGRAM_BOT_TOKEN+"/sendPhoto"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type","multipart/form-data; boundary="+b)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build());
        } catch (Exception e) {
            MoggSyncClient.LOGGER.error("Telegram photo failed: {}", e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ sendMessage (plain)

    public static void sendMessage(MoggConfig c, String text) { sendMessage(c, text, false); }

    public static void sendMessage(MoggConfig c, String text, boolean silent) {
        if (!ready(c)) return;
        post(c, "/sendMessage",
             "chat_id=" + enc(c.CHAT_ID)
             + "&text=" + enc(text.length()>4000?text.substring(0,4000):text)
             + (silent ? "&disable_notification=true" : ""));
    }

    // ------------------------------------------------------------------ sendHtml (HTML)

    public static void sendHtml(MoggConfig c, String html) { sendHtml(c, html, false); }

    public static void sendHtml(MoggConfig c, String html, boolean silent) {
        if (!ready(c)) return;
        post(c, "/sendMessage",
             "chat_id=" + enc(c.CHAT_ID)
             + "&parse_mode=HTML"
             + "&text=" + enc(html.length()>4000?html.substring(0,4000):html)
             + (silent ? "&disable_notification=true" : ""));
    }

    // ------------------------------------------------------------------ linkChat

    public static void linkChat(MoggConfig c, BiConsumer<String,String> cb) {
        if (c.TELEGRAM_BOT_TOKEN.isBlank()) { cb.accept(null,"Сначала: .moggsynk tg ТОКЕН"); return; }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/bot"+c.TELEGRAM_BOT_TOKEN+"/getUpdates"))
                .timeout(Duration.ofSeconds(20)).GET().build();
        HTTP.sendAsync(req,HttpResponse.BodyHandlers.ofString())
            .thenAccept(r -> {
                if (r.statusCode()==401||r.statusCode()==404) { cb.accept(null,"Токен не подходит"); return; }
                if (r.statusCode()!=200) { cb.accept(null,"Telegram ответил "+r.statusCode()); return; }
                try {
                    JsonArray arr = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("result");
                    for (int i=arr.size()-1;i>=0;i--) {
                        JsonObject u=arr.get(i).getAsJsonObject();
                        if (u.has("message")&&u.getAsJsonObject("message").has("chat")) {
                            cb.accept(u.getAsJsonObject("message").getAsJsonObject("chat").get("id").getAsString(),null);
                            return;
                        }
                    }
                    cb.accept(null,"Бот пока не получил сообщений. Напиши ему /start в Telegram и введи .moggsynk tglink");
                } catch (Exception e) { cb.accept(null,"Не удалось разобрать ответ Telegram"); }
            })
            .exceptionally(t->{ cb.accept(null,"Нет связи ("+t.getClass().getSimpleName()+")"); return null; });
    }

    // ------------------------------------------------------------------ internals

    private static void post(MoggConfig c, String method, String body) {
        dispatch(HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/bot"+c.TELEGRAM_BOT_TOKEN+method))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private static void dispatch(HttpRequest req) {
        HTTP.sendAsync(req,HttpResponse.BodyHandlers.ofString())
            .thenAccept(r->{ if(r.statusCode()!=200) MoggSyncClient.LOGGER.warn("Telegram HTTP {}: {}",r.statusCode(),r.body()); })
            .exceptionally(t->{ MoggSyncClient.LOGGER.warn("Telegram failed: {}",t.getClass().getSimpleName()); return null; });
    }

    private static String enc(String s) { return URLEncoder.encode(s,StandardCharsets.UTF_8); }

    private static void field(ByteArrayOutputStream o, String b, String n, String v) throws Exception {
        write(o,"--"+b+"\r\nContent-Disposition: form-data; name=\""+n+"\"\r\n\r\n"+v+"\r\n");
    }

    private static void write(ByteArrayOutputStream o, String s) throws Exception {
        o.write(s.getBytes(StandardCharsets.UTF_8));
    }
}
