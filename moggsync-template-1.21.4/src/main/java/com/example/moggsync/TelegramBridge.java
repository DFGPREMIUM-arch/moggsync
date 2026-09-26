package com.example.moggsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Принимает сообщения из Telegram (long polling в отдельном потоке).
 * Берутся ТОЛЬКО сообщения из чата CHAT_ID. Сами команды выполняются в основном потоке игры (MoggSyncClient).
 */
public final class TelegramBridge {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final MoggSyncClient mod;
    private final Queue<String> incoming = new ConcurrentLinkedQueue<>();
    private volatile boolean running = true;
    private long offset = 0;
    private boolean first = true;          // первый запрос: пропускаем старые сообщения, чтобы не выполнить их задним числом

    TelegramBridge(MoggSyncClient mod) { this.mod = mod; }

    void start() {
        Thread t = new Thread(this::loop, "moggsync-telegram");
        t.setDaemon(true);
        t.start();
    }

    /** Следующее входящее сообщение или null. Вызывать из основного потока. */
    String poll() { return incoming.poll(); }

    private void loop() {
        while (running) {
            try {
                MoggConfig c = mod.cfg();
                if (!c.tgControl || !TelegramReporter.ready(c) || !mod.accountAllowed()) {
                    first = true;
                    Thread.sleep(2000);
                    continue;
                }
                pollOnce(c);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // в тексте исключения может быть URL с токеном — логируем только тип
                MoggSyncClient.LOGGER.warn("Telegram poll failed: {}", e.getClass().getSimpleName());
                sleepQuiet(8000);
            }
        }
    }

    private void pollOnce(MoggConfig c) throws Exception {
        String url = "https://api.telegram.org/bot" + c.TELEGRAM_BOT_TOKEN + "/getUpdates?timeout=" + (first ? 0 : 25)
                + "&offset=" + (first ? -1 : offset) + "&allowed_updates=%5B%22message%22%5D";
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(40)).GET().build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

        if (r.statusCode() == 409) {           // тот же бот уже опрашивает другой ПК
            MoggSyncClient.LOGGER.warn("Telegram 409: этот бот уже используется другим клиентом. Нужен отдельный бот на каждый ПК.");
            sleepQuiet(15000);
            return;
        }
        if (r.statusCode() != 200) { sleepQuiet(8000); return; }

        JsonArray arr = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("result");
        for (JsonElement el : arr) {
            JsonObject u = el.getAsJsonObject();
            offset = Math.max(offset, u.get("update_id").getAsLong() + 1);
            if (first) continue;
            if (!u.has("message")) continue;
            JsonObject m = u.getAsJsonObject("message");
            if (!m.has("text") || !m.has("chat")) continue;
            String chat = m.getAsJsonObject("chat").get("id").getAsString();
            if (!chat.equals(c.CHAT_ID.strip())) continue;        // чужим людям бот не подчиняется
            if (incoming.size() < 50) incoming.add(m.get("text").getAsString());
        }
        first = false;
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
