package com.example.moggsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MoggConfig {
    public int configVersion = 0;                      // служебное: для автообновления старых настроек
    public boolean enabledOnStart = true;
    public String activeAccounts = "";                 // ники через запятую, на которых мод работает. Пусто = на любом аккаунте

    // --- Напарник (задаётся в игре: .moggsynk Ник) ---
    public String partnerName = "";

    // --- Таймер (кд после ритуала) ---
    public int timerMinSeconds = 300;   // 5 мин
    public int timerMaxSeconds = 480;   // 8 мин
    public String readyPhrase = "я тебя могну";        // что пишет инициатор в чат, чтобы пригласить напарника
    public String inviteCommand = "";                  // не обязательно: если пусто, приглашаем фразой readyPhrase. Иначе команда, %p = ник напарника
    public List<String> inviteTriggers = List.of("приглашает вас на парный ритуал");
    public int replyMinMs = 1000;       // пауза перед ответом напарнику
    public int replyMaxMs = 2000;
    public int retrySeconds = 90;       // если ритуал не начался — повторим фразу

    // --- Танец ---
    public List<String> danceTriggers = List.of("секретный танец");
    public List<String> rewardTriggers = List.of("получает награду");        // в сообщении должен быть ваш ник или ник напарника
    public List<String> rewardTriggersPersonal = List.of("вы получили");      // личные сообщения, ник не нужен
    public int danceToggleMs = 250;
    public int danceTimeoutSeconds = 30;

    // --- Лобби (MoreCube -> ReallyWorld 2) ---
    public boolean autoLobby = true;
    public String serverAddressContains = "";          // пусто = на любом мультиплеерном сервере (в одиночной игре лобби пропускается)
    public String lobbyCommand = "/reallyworld";       // открывает меню "Выбор сервера". Пусто = открыть меню предметом из хотбара
    public int lobbyHotbarSlot = 4;                    // 5-й слот хотбара (0..8), нужен только если lobbyCommand пуст
    // Шаги кликов по меню. Ищем предмет по части названия, если не нашли - берём slot.
    // Вариант через предмет в хотбаре: lobbyCommand = "", шаги: {"Выбор режим","REALLYWORLD",21}, {"Выбор сервера","REALLYWORLD 2",22}
    public List<MenuStep> menuSteps = List.of(new MenuStep("Выбор сервера", "REALLYWORLD 2", 22));
    // Если после команды лобби сервер отвечает этим текстом — мы уже на игровом сервере, лобби не нужно
    public List<String> notLobbyTriggers = List.of("команда не найдена", "unknown or incomplete command");
    // Сервер перезагружается: после клика приходит «недоступен» — ждём и кликаем снова
    public List<String> unavailableTriggers = List.of("сервер сейчас недоступен", "server is currently unavailable");
    public int unavailableRetryMs = 8000;              // пауза между повторами
    public int unavailableMaxRetries = 60;             // ~8 минут при паузе 8 с
    public boolean debugLogMenu = true;                // пишет в logs/latest.log все предметы меню и их слоты
    public int lobbyJoinDelayMs = 3000;
    public int menuClickDelayMs = 2000;              // пауза после открытия меню (там анимация), потом клик
    public int menuTimeoutMs = 5000;
    public int transferTimeoutMs = 15000;
    public int postTransferDelayMs = 4000;
    public int maxLobbyAttempts = 5;
    public String farmCommand = "";                    // команда после входа на сервер (например /home farm), пусто = ничего

    // --- Авто-переподключение (после рестарта/кика) ---
    public boolean autoReconnect = true;
    public int reconnectDelaySeconds = 10;
    public int maxReconnectAttempts = 0;               // 0 = пытаться бесконечно

    public static class MenuStep {
        public String title = "";
        public String item = "";
        public int slot = -1;
        public MenuStep() {}
        public MenuStep(String title, String item, int slot) { this.title = title; this.item = item; this.slot = slot; }
    }

    // --- Telegram (можно задать в игре: .moggsynk tg ТОКЕН) ---
    public boolean telegramEnabled = true;
    public String TELEGRAM_BOT_TOKEN = "";
    public String CHAT_ID = "";
    public String tgChatMode = "MENTIONS";              // какой игровой чат слать в Telegram: OFF | MENTIONS (про меня/напарника) | ALL
    public boolean tgControl = false;                  // управление игрой из Telegram (нужен свой бот на каждом ПК)
    public int autoScreenshotMinutes = 30;   // 0 = выключить

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("moggsync.json");
    }

    public static MoggConfig load() {
        Path p = path();
        try {
            if (Files.exists(p)) {
                try (var r = Files.newBufferedReader(p)) {
                    MoggConfig c = GSON.fromJson(r, MoggConfig.class);
                    if (c != null) {
                        if (c.configVersion < CURRENT_VERSION) migrate(c);
                        save(c);
                        return c;
                    }
                }
            }
        } catch (Exception e) {
            MoggSyncClient.LOGGER.error("Config load failed: {}", e.toString());
        }
        MoggConfig c = new MoggConfig();
        c.configVersion = CURRENT_VERSION;
        save(c);
        return c;
    }

    private static final int CURRENT_VERSION = 4;

    /** Старые конфиги: сервер пишет «станцевали» (мн. число), а фраза приглашения — «я тебя могну». */
    private static void migrate(MoggConfig c) {
        if (c.configVersion < 3) {
            c.danceTriggers = new ArrayList<>(List.of("секретный танец"));
            if (c.readyPhrase == null || c.readyPhrase.isBlank() || c.readyPhrase.equals("я тебя могу"))
                c.readyPhrase = "я тебя могну";
        }
        if (c.configVersion < 4 && c.menuClickDelayMs <= 500) c.menuClickDelayMs = 2000;
        c.configVersion = CURRENT_VERSION;
    }

    public static void save(MoggConfig c) {
        try { Files.writeString(path(), GSON.toJson(c)); }
        catch (Exception e) { MoggSyncClient.LOGGER.error("Config save failed: {}", e.toString()); }
    }
}
