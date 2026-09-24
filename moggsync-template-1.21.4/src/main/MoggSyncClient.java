package com.example.moggsync;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MoggSyncClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("moggsync");
    private static final Pattern RILLIKI = Pattern.compile("(\\d+)\\s*риллик", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private enum Nav { IDLE, WAIT_OPEN, WAIT_MENU, WAIT_TRANSFER, WAIT_FARM }
    private enum Ritual { IDLE, DANCING }

    private MoggConfig cfg;
    private KeyBinding toggleKey;
    private boolean enabled;
    private boolean hintedPartner;

    // лобби
    private Nav nav = Nav.IDLE;
    private long navAt, menuSeenAt;
    private int lobbyAttempts;
    private int stepIndex;
    private int lastClickedSync = -1;

    // ритуал
    private Ritual ritual = Ritual.IDLE;
    private long nextSyncAt, pendingPhraseAt, lastSaidAt;
    private long danceStartAt, lastToggleAt;
    private boolean sneakOn;

    // отчёты
    private long screenshotAt, nextAutoShotAt;
    private String pendingCaption = "";
    private int ritualsDone;
    private long totalRilliki;

    // переподключение
    private ServerInfo lastServer;
    private boolean reconnectPending, reconnectAnnounced;
    private long reconnectAt;
    private int reconnectTries;

    private String pendingPhraseText = "";
    private long ritualReportAt, stepStartAt;
    private final List<String> rewardLines = new ArrayList<>();
    private String lastInfo = "";
    private boolean openGui;
    private KeyBinding guiKey;
    private static MoggSyncClient INSTANCE;
    private static final Pattern QUOTED = Pattern.compile("«([^»]+)»");

    private String lastMsg = "";
    private long lastMsgAt;
    private final Random rnd = new Random();

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        cfg = MoggConfig.load();
        enabled = cfg.enabledOnStart;

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.moggsync.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, "category.moggsync"));

        guiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.moggsync.gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, "category.moggsync"));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        // свои команды .moggsynk ... не уходят на сервер
        ClientSendMessageEvents.ALLOW_CHAT.register(this::onOutgoing);

        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { if (!overlay) onChat(msg.getString()); });
        ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) -> onChat(msg.getString()));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> releaseSneak());

        LOGGER.info("MoggSync loaded");
    }

    // ------------------------------------------------------------------ API для окна настроек

    public static MoggSyncClient get() { return INSTANCE; }
    public MoggConfig cfg() { return cfg; }
    public boolean isEnabled() { return enabled; }
    public String lastInfo() { return lastInfo; }

    public void setEnabled(boolean v) {
        enabled = v;
        if (v) armTimer(); else stopAll();
    }

    /** Вызывается при закрытии окна настроек. */
    void onConfigChanged() {
        if (cfg.timerMaxSeconds < cfg.timerMinSeconds) {
            int t = cfg.timerMaxSeconds; cfg.timerMaxSeconds = cfg.timerMinSeconds; cfg.timerMinSeconds = t;
        }
        if (enabled && nextSyncAt == 0 && !cfg.partnerName.isBlank()) armTimer();
        nextAutoShotAt = 0;
    }

    void startLobbyTest() {
        if (mc().getCurrentServerEntry() == null) { info("Работает только на сервере"); return; }
        lobbyAttempts = 0;
        nav = Nav.WAIT_OPEN;
        navAt = System.currentTimeMillis();
        info("Запускаю вход через лобби");
    }

    void telegramTest() {
        if (!TelegramReporter.ready(cfg)) { info("Telegram не настроен (нужны токен и chat_id)"); return; }
        TelegramReporter.sendMessage(cfg, "✅ MoggSync: тестовое сообщение (" + myName() + ")");
        info("Тест отправлен в Telegram");
    }

    void requestShot() {
        pendingCaption = "📸 Скриншот по запросу\n" + statusLine();
        screenshotAt = System.currentTimeMillis() + 400;
    }

    // ------------------------------------------------------------------ helpers

    private MinecraftClient mc() { return MinecraftClient.getInstance(); }

    private void info(String s) {
        lastInfo = s;
        ClientPlayerEntity p = mc().player;
        if (p != null) p.sendMessage(Text.literal("[MoggSync] " + s), false);
        LOGGER.info(s);
    }

    private String myName() {
        ClientPlayerEntity p = mc().player;
        return p == null ? "" : p.getName().getString();
    }

    private static boolean mentions(String raw, String name) {
        return name != null && !name.isBlank() && raw.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT));
    }

    private void say(String text) {
        ClientPlayerEntity p = mc().player;
        if (p == null || text == null || text.isBlank()) return;
        if (text.startsWith("/")) p.networkHandler.sendChatCommand(text.substring(1));
        else p.networkHandler.sendChatMessage(text);
    }

    private void sayPhrase() {
        lastSaidAt = System.currentTimeMillis();
        say(cfg.readyPhrase);
    }

    private void armTimer() {
        long ms = (cfg.timerMinSeconds + rnd.nextInt(Math.max(1, cfg.timerMaxSeconds - cfg.timerMinSeconds + 1))) * 1000L;
        nextSyncAt = System.currentTimeMillis() + ms;
        LOGGER.info("Next timer in {} s", ms / 1000);
    }

    private void releaseSneak() {
        sneakOn = false;
        MinecraftClient m = mc();
        if (m != null && m.options != null) m.options.sneakKey.setPressed(false);
    }

    private void stopAll() {
        releaseSneak();
        ritual = Ritual.IDLE;
        nav = Nav.IDLE;
        nextSyncAt = 0;
        pendingPhraseAt = 0;
        screenshotAt = 0;
    }

    private static boolean matchesAny(String lowerText, List<String> needles) {
        for (String n : needles) if (lowerText.contains(n.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    // ------------------------------------------------------------------ команды в чате

    private boolean onOutgoing(String message) {
        String t = message.strip();
        String tl = t.toLowerCase(Locale.ROOT);
        if (tl.startsWith(".farmcomand") || tl.startsWith(".farmcommand")) {   // короткий алиас
            int sp = t.indexOf(' ');
            handleCommand("farm " + (sp < 0 ? "" : t.substring(sp + 1).strip()));
            return false;
        }
        if (!tl.startsWith(".moggsynk")) return true;
        handleCommand(t.substring(".moggsynk".length()).strip());
        return false;   // на сервер не отправляем
    }

    private void handleCommand(String args) {
        String[] p = args.split("\\s+", 2);
        String sub = p[0].toLowerCase(Locale.ROOT);
        String rest = p.length > 1 ? p[1].strip() : "";
        long now = System.currentTimeMillis();

        switch (sub) {
            case "", "help" -> {
                info("Команды:");
                info(".moggsynk <ник>  — ник напарника");
                info(".moggsynk on / off  — включить / выключить");
                info(".moggsynk now  — начать синхронизацию прямо сейчас");
                info(".moggsynk tg <токен>  — подключить Telegram-бота");
                info(".moggsynk tglink  — найти chat_id (после /start боту)");
                info(".moggsynk tgtest / shot / status");
                info(".moggsynk phrase я тебя могну  — фраза, которой вы приглашаете напарника");
                info(".moggsynk gui  — окно настроек (или клавиша [)");
                info(".moggsynk autolobby on/off  — авто-вход через лобби");
                info(".moggsynk farm /home farm  — команда после входа (farm off — убрать)");
                info(".moggsynk slot 22  — слот для клика в меню сервера");
                info(".moggsynk item REALLYWORLD 2  — искать предмет по названию");
                info(".moggsynk lobby /reallyworld  — команда лобби (lobby off — через предмет)");
                info(".moggsynk hotbar 5  — слот хотбара с компасом (1-9)");
                info(".moggsynk lobbytest  — проверить вход через лобби сейчас");
                info(".moggsynk reconnect on/off  — авто-переподключение");
            }
            case "on" -> { enabled = true; armTimer(); info("ON"); }
            case "off" -> { enabled = false; stopAll(); info("OFF"); }
            case "now" -> {
                if (cfg.partnerName.isBlank()) { info("Сначала укажи ник напарника: .moggsynk <ник>"); return; }
                nextSyncAt = now;
                info("Запускаю синхронизацию");
            }
            case "status" -> {
                long left = nextSyncAt == 0 ? -1 : Math.max(0, (nextSyncAt - now) / 1000);
                info("Вкл: " + enabled + ", напарник: " + (cfg.partnerName.isBlank() ? "не задан" : cfg.partnerName)
                        + ", Telegram: " + (TelegramReporter.ready(cfg) ? "готов" : "не настроен")
                        + ", ритуалов: " + ritualsDone + ", рилликов: " + totalRilliki
                        + ", до следующего: " + (left < 0 ? "-" : left + " c"));
            }
            case "farm" -> {
                if (rest.isBlank()) info("Команда фарма: " + (cfg.farmCommand.isBlank() ? "не задана" : cfg.farmCommand));
                else if (rest.equalsIgnoreCase("off")) { cfg.farmCommand = ""; MoggConfig.save(cfg); info("Команда фарма отключена"); }
                else { cfg.farmCommand = rest; MoggConfig.save(cfg); info("Команда фарма: " + rest); }
            }
            case "slot" -> {
                try {
                    int n = Integer.parseInt(rest);
                    MoggConfig.MenuStep st = lastStep();
                    st.slot = n; st.item = "";              // клик строго по номеру слота
                    MoggConfig.save(cfg);
                    info("Клик по слоту " + n + " в меню \"" + st.title + "\"");
                } catch (NumberFormatException e) { info("Использование: .moggsynk slot 22"); }
            }
            case "item" -> {
                if (rest.isBlank()) { info("Использование: .moggsynk item REALLYWORLD 2"); return; }
                lastStep().item = rest;
                MoggConfig.save(cfg);
                info("Ищу в меню предмет: " + rest);
            }
            case "lobby" -> {
                if (rest.isBlank()) info("Команда лобби: " + (cfg.lobbyCommand.isBlank() ? "предмет в хотбаре" : cfg.lobbyCommand));
                else if (rest.equalsIgnoreCase("off")) { cfg.lobbyCommand = ""; MoggConfig.save(cfg); info("Лобби через предмет в хотбаре (слот " + (cfg.lobbyHotbarSlot + 1) + ")"); }
                else { cfg.lobbyCommand = rest; MoggConfig.save(cfg); info("Команда лобби: " + rest); }
            }
            case "hotbar" -> {
                try {
                    int n = Integer.parseInt(rest);
                    if (n < 1 || n > 9) throw new NumberFormatException();
                    cfg.lobbyHotbarSlot = n - 1;
                    MoggConfig.save(cfg);
                    info("Слот хотбара: " + n);
                } catch (NumberFormatException e) { info("Использование: .moggsynk hotbar 5  (1-9)"); }
            }
            case "reconnect" -> {
                cfg.autoReconnect = !rest.equalsIgnoreCase("off");
                MoggConfig.save(cfg);
                info("Авто-переподключение: " + (cfg.autoReconnect ? "ON" : "OFF"));
            }
            case "lobbytest" -> startLobbyTest();
            case "gui" -> openGui = true;
            case "autolobby" -> {
                cfg.autoLobby = !rest.equalsIgnoreCase("off");
                MoggConfig.save(cfg);
                info("Авто-вход через лобби: " + (cfg.autoLobby ? "ON" : "OFF"));
            }
            case "phrase" -> {
                if (rest.isBlank()) info("Фраза приглашения: " + cfg.readyPhrase);
                else { cfg.readyPhrase = rest; MoggConfig.save(cfg); info("Фраза приглашения: " + rest); }
            }
            case "invite" -> {
                if (rest.isBlank()) info("Команда приглашения: " + (cfg.inviteCommand.isBlank() ? "не задана" : cfg.inviteCommand));
                else if (rest.equalsIgnoreCase("off")) { cfg.inviteCommand = ""; MoggConfig.save(cfg); info("Команда приглашения убрана"); }
                else { cfg.inviteCommand = rest; MoggConfig.save(cfg); info("Команда приглашения: " + rest); }
            }
            case "tg" -> {
                if (rest.isBlank()) { info("Использование: .moggsynk tg ТОКЕН_ОТ_BOTFATHER"); return; }
                cfg.TELEGRAM_BOT_TOKEN = rest;
                cfg.telegramEnabled = true;
                MoggConfig.save(cfg);
                info("Токен сохранён. Теперь напиши своему боту /start в Telegram, потом введи .moggsynk tglink");
                linkTelegram();
            }
            case "tglink" -> linkTelegram();
            case "tgtest" -> {
                if (!TelegramReporter.ready(cfg)) { info("Telegram не настроен (нужны токен и chat_id)"); return; }
                TelegramReporter.sendMessage(cfg, "✅ MoggSync: тестовое сообщение (" + myName() + ")");
                info("Тест отправлен");
            }
            case "shot" -> {
                pendingCaption = "📸 Скриншот по запросу\n" + statusLine();
                screenshotAt = now + 100;
            }
            default -> {
                if (!p[0].matches("[A-Za-z0-9_]{3,16}")) { info("Не похоже на ник. Напиши .moggsynk help"); return; }
                cfg.partnerName = p[0];
                MoggConfig.save(cfg);
                if (nextSyncAt == 0 && enabled) armTimer();
                info("Напарник: " + cfg.partnerName);
            }
        }
    }

    MoggConfig.MenuStep lastStep() {
        if (cfg.menuSteps == null) cfg.menuSteps = new ArrayList<>();
        else cfg.menuSteps = new ArrayList<>(cfg.menuSteps);
        if (cfg.menuSteps.isEmpty()) cfg.menuSteps.add(new MoggConfig.MenuStep("Выбор сервера", "REALLYWORLD 2", 22));
        return cfg.menuSteps.get(cfg.menuSteps.size() - 1);
    }

    void linkTelegram() {
        TelegramReporter.linkChat(cfg, (chatId, err) -> mc().execute(() -> {
            if (chatId != null) {
                cfg.CHAT_ID = chatId;
                MoggConfig.save(cfg);
                info("Telegram подключён ✅");
                TelegramReporter.sendMessage(cfg, "✅ MoggSync подключён к этому чату (" + myName() + ")");
            } else {
                info(err);
            }
        }));
    }

    private String statusLine() {
        return "Ритуалов: " + ritualsDone + ", рилликов: " + totalRilliki;
    }

    // ------------------------------------------------------------------ соединение

    private void onJoin(MinecraftClient client) {
        lastServer = client.getCurrentServerEntry();      // null в одиночной игре
        reconnectPending = false;
        reconnectAnnounced = false;
        reconnectTries = 0;
        if (!enabled) return;
        releaseSneak();
        ritual = Ritual.IDLE;
        pendingPhraseAt = 0;
        long now = System.currentTimeMillis();

        var server = client.getCurrentServerEntry();   // null в одиночной игре
        boolean lobbyHere = cfg.autoLobby && server != null
                && (cfg.serverAddressContains.isBlank()
                || server.address.toLowerCase(Locale.ROOT).contains(cfg.serverAddressContains.toLowerCase(Locale.ROOT)));
        if (!lobbyHere) {
            nav = Nav.IDLE;
            armTimer();
            if (server != null && !cfg.autoLobby)
                info("Авто-вход через лобби выключен. Включить: .moggsynk autolobby on (или окно настроек, клавиша [)");
            return;
        }

        if (nav == Nav.WAIT_TRANSFER && now < navAt) {
            lobbyLog("Лобби: перешёл на сервер, дальше через " + cfg.postTransferDelayMs / 1000 + " с");
            nav = Nav.WAIT_FARM;
            navAt = now + cfg.postTransferDelayMs;
        } else {
            lobbyLog("Лобби: вход в игру, начну через " + cfg.lobbyJoinDelayMs / 1000 + " с");
            lobbyAttempts = 0;
            nav = Nav.WAIT_OPEN;
            navAt = now + cfg.lobbyJoinDelayMs;
        }
    }

    private void onDisconnect() {
        releaseSneak();
        ritual = Ritual.IDLE;
        pendingPhraseAt = 0;
        if (nav != Nav.WAIT_TRANSFER) nav = Nav.IDLE;
        if (enabled && cfg.autoReconnect && lastServer != null) {
            reconnectPending = true;
            reconnectAt = System.currentTimeMillis() + cfg.reconnectDelaySeconds * 1000L;
        }
    }

    /** Если нас выкинуло (рестарт, кик, обрыв) — заходим снова. Ручной выход из мира не считается. */
    private void tickReconnect(MinecraftClient mc) {
        if (!reconnectPending) return;
        if (!enabled) { reconnectPending = false; return; }
        long now = System.currentTimeMillis();
        if (now < reconnectAt) return;
        if (mc.world != null) { reconnectPending = false; return; }

        Screen s = mc.currentScreen;
        if (s instanceof DisconnectedScreen) {
            if (!reconnectAnnounced) {
                reconnectAnnounced = true;
                TelegramReporter.sendMessage(cfg, "⚠️ MoggSync: соединение потеряно, переподключаюсь...");
            }
            if (cfg.maxReconnectAttempts > 0 && ++reconnectTries > cfg.maxReconnectAttempts) {
                reconnectPending = false;
                TelegramReporter.sendMessage(cfg, "❌ MoggSync: не удалось переподключиться за " + cfg.maxReconnectAttempts + " попыток.");
                return;
            }
            LOGGER.info("Reconnecting to {}", lastServer.address);
            ConnectScreen.connect(new TitleScreen(), mc, ServerAddress.parse(lastServer.address), lastServer, false, null);
            reconnectAt = now + cfg.reconnectDelaySeconds * 1000L;
        } else if (s instanceof ConnectScreen) {
            reconnectAt = now + 2000;                      // подключаемся, ждём
        } else {
            reconnectPending = false;                      // игрок вышел сам
        }
    }

    // ------------------------------------------------------------------ tick

    private void onTick(MinecraftClient mc) {
        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            if (enabled) armTimer(); else stopAll();
            if (mc.player != null)
                mc.player.sendMessage(Text.literal("[MoggSync] " + (enabled ? "ON" : "OFF")), true);
        }
        while (guiKey.wasPressed()) openGui = true;
        if (openGui && mc.currentScreen == null && mc.player != null) {
            openGui = false;
            mc.setScreen(new MoggScreen());
        }
        tickReconnect(mc);
        if (!enabled || mc.player == null || mc.world == null) return;

        long now = System.currentTimeMillis();

        if (cfg.partnerName.isBlank() && !hintedPartner) {
            hintedPartner = true;
            info("Укажи ник напарника: .moggsynk <ник>  (справка: .moggsynk help)");
        }

        tickRitualReport(now);
        tickAutoShot(now);
        tickScreenshot(mc, now);
        tickNav(mc, now);
        if (nav != Nav.IDLE) return;

        tickTimers(now);
        tickDance(mc, now);
    }

    // ------------------------------------------------------------------ лобби

    private void lobbyLog(String text) {
        if (cfg.debugLogMenu) info(text); else LOGGER.info(text);
    }

    private void tickNav(MinecraftClient mc, long now) {
        switch (nav) {
            case IDLE -> {}
            case WAIT_OPEN -> {
                if (now < navAt) return;
                if (++lobbyAttempts > cfg.maxLobbyAttempts) {
                    nav = Nav.IDLE;
                    info("Лобби: не удалось войти за " + cfg.maxLobbyAttempts + " попыток");
                    TelegramReporter.sendMessage(cfg, "⚠️ MoggSync (" + myName() + "): не удалось войти на сервер через лобби.");
                    return;
                }
                stepIndex = 0;
                lastClickedSync = -1;
                menuSeenAt = 0;
                if (!cfg.lobbyCommand.isBlank()) {
                    lobbyLog("Лобби: команда " + cfg.lobbyCommand + " (попытка " + lobbyAttempts + ")");
                    say(cfg.lobbyCommand);
                } else {
                    int hs = Math.max(0, Math.min(8, cfg.lobbyHotbarSlot));
                    lobbyLog("Лобби: ПКМ по слоту хотбара " + (hs + 1) + " (попытка " + lobbyAttempts + ")");
                    mc.player.getInventory().selectedSlot = hs;
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                }
                nav = Nav.WAIT_MENU;
                navAt = now + cfg.menuTimeoutMs;
                stepStartAt = now;
            }
            case WAIT_MENU -> tickMenu(mc, now);
            case WAIT_TRANSFER -> {
                if (now >= navAt) {
                    lobbyLog("Лобби: нового входа не заметил, считаю переход выполненным");
                    nav = Nav.WAIT_FARM;
                    navAt = now + cfg.postTransferDelayMs;
                }
            }
            case WAIT_FARM -> {
                if (now < navAt) return;
                if (!cfg.farmCommand.isBlank()) {
                    lobbyLog("Лобби: команда фарма " + cfg.farmCommand);
                    say(cfg.farmCommand);
                }
                lobbyLog("Лобби: готово");
                nav = Nav.IDLE;
                armTimer();
            }
        }
    }

    private void tickMenu(MinecraftClient mc, long now) {
        if (cfg.menuSteps == null || stepIndex >= cfg.menuSteps.size()) {
            nav = Nav.WAIT_TRANSFER;
            navAt = now + cfg.transferTimeoutMs;
            return;
        }
        MoggConfig.MenuStep step = cfg.menuSteps.get(stepIndex);

        Screen scr = mc.currentScreen;
        if (scr instanceof HandledScreen<?> hs && !(scr instanceof InventoryScreen)
                && hs.getScreenHandler().syncId != lastClickedSync) {
            ScreenHandler h = hs.getScreenHandler();
            String title = hs.getTitle().getString();
            boolean titleOk = step.title == null || step.title.isBlank()
                    || title.toLowerCase(Locale.ROOT).contains(step.title.toLowerCase(Locale.ROOT));
            // заголовок мог не совпасть (шрифты сервера) — тогда ориентируемся на предмет или ждём 3 с
            if (titleOk || findByName(h, step) >= 0 || now - stepStartAt > 3000) {
                if (menuSeenAt == 0) {
                    menuSeenAt = now;
                    lobbyLog("Лобби: открыто меню «" + title + "»");
                    dumpMenu(h);
                }
                if (now - menuSeenAt < cfg.menuClickDelayMs) return;

                int slot = findSlot(h, step);
                if (slot < 0) {
                    lobbyLog("Лобби: в меню нет предмета «" + step.item + "», слот " + step.slot + " пуст. Повтор");
                    mc.player.closeHandledScreen();
                    nav = Nav.WAIT_OPEN;
                    navAt = now + 2000;
                    return;
                }
                lobbyLog("Лобби: клик по слоту " + slot + " (" + h.slots.get(slot).getStack().getName().getString() + ")");
                mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
                lastClickedSync = h.syncId;
                stepIndex++;
                menuSeenAt = 0;
                stepStartAt = now;
                if (stepIndex >= cfg.menuSteps.size()) {
                    nav = Nav.WAIT_TRANSFER;
                    navAt = now + cfg.transferTimeoutMs;
                } else {
                    navAt = now + cfg.menuTimeoutMs;
                }
                return;
            }
        }
        if (now >= navAt) {
            lobbyLog("Лобби: нужное меню не открылось, повтор");
            nav = Nav.WAIT_OPEN;
            navAt = now + 1500;
        }
    }

    private static int findByName(ScreenHandler h, MoggConfig.MenuStep s) {
        if (s.item == null || s.item.isBlank()) return -1;
        int limit = h.slots.size() > 36 ? h.slots.size() - 36 : h.slots.size();   // только само меню, без инвентаря
        String needle = s.item.toLowerCase(Locale.ROOT);
        for (int i = 0; i < limit; i++) {
            var st = h.slots.get(i).getStack();
            if (!st.isEmpty() && st.getName().getString().toLowerCase(Locale.ROOT).contains(needle)) return i;
        }
        return -1;
    }

    private static int findSlot(ScreenHandler h, MoggConfig.MenuStep s) {
        int byName = findByName(h, s);
        if (byName >= 0) return byName;
        if (s.slot >= 0 && s.slot < h.slots.size() && !h.slots.get(s.slot).getStack().isEmpty()) return s.slot;
        return -1;
    }

    private void dumpMenu(ScreenHandler h) {
        int count = 0;
        for (int i = 0; i < h.slots.size(); i++) {
            var st = h.slots.get(i).getStack();
            if (!st.isEmpty()) { LOGGER.info("[menu] slot {} = {}", i, st.getName().getString()); count++; }
        }
        lobbyLog("Лобби: предметов в меню (с инвентарём): " + count + ", список в logs/latest.log");
    }

    // ------------------------------------------------------------------ таймер / синхронизация

    private void tickTimers(long now) {
        if (pendingPhraseAt > 0 && now >= pendingPhraseAt) {
            pendingPhraseAt = 0;
            String t = pendingPhraseText;
            pendingPhraseText = "";
            lastSaidAt = now;
            say(t.isBlank() ? cfg.readyPhrase : t);        // ответ на приглашение
        }
        if (ritual == Ritual.DANCING || nextSyncAt == 0 || now < nextSyncAt) return;
        if (cfg.partnerName.isBlank()) return;
        sendInvite(now);
    }

    /** Мой таймер сработал первым — пишем фразу в чат, сервер покажет напарнику плашку-приглашение. */
    private void sendInvite(long now) {
        String text = cfg.inviteCommand.isBlank() ? cfg.readyPhrase : cfg.inviteCommand.replace("%p", cfg.partnerName);
        lastSaidAt = now;
        say(text);
        nextSyncAt = now + cfg.retrySeconds * 1000L;       // если ритуал не начался — пригласим снова
    }

    // ------------------------------------------------------------------ чат

    private void onChat(String raw) {
        if (!enabled || raw == null) return;
        long now = System.currentTimeMillis();
        if (raw.equals(lastMsg) && now - lastMsgAt < 500) return;
        lastMsg = raw; lastMsgAt = now;
        String low = raw.toLowerCase(Locale.ROOT);

        String me = myName();
        boolean ours = mentions(raw, me) || mentions(raw, cfg.partnerName);

        // 1) строка награды про нас или напарника (у каждого игрока своя строка «получает награду»)
        boolean rewardLine = (ours && matchesAny(low, cfg.rewardTriggers))
                || ((ritual == Ritual.DANCING || ritualReportAt != 0) && matchesAny(low, cfg.rewardTriggersPersonal));
        if (rewardLine) {
            if (ritualReportAt == 0) {                      // первая строка награды: ритуал завершён
                releaseSneak();
                ritual = Ritual.IDLE;
                ritualsDone++;
                rewardLines.clear();
                ritualReportAt = now + 1500;                // ждём вторую строку (награда напарника), потом скриншот
                armTimer();                                 // кд 5–8 минут
                LOGGER.info("Ritual #{} reward received", ritualsDone);
            }
            rewardLines.add(raw.strip());
            return;
        }

        // 2) сервер объявил танец («Игроки A × B станцевали секретный танец») — начинаем
        if (matchesAny(low, cfg.danceTriggers)) {
            if (ritual == Ritual.IDLE && nav == Nav.IDLE && ritualReportAt == 0 && ours) {
                ritual = Ritual.DANCING;
                danceStartAt = now;
                lastToggleAt = 0;
                nextSyncAt = 0;
                pendingPhraseAt = 0;
                LOGGER.info("Dance started");
            }
            return;
        }

        // 3) напарник пригласил нас на парный ритуал — читаем фразу из «...» и отвечаем через 1–2 с
        if (ritual == Ritual.IDLE && nav == Nav.IDLE && matchesAny(low, cfg.inviteTriggers)) {
            if (cfg.partnerName.isBlank()) { info("Пришло приглашение, но ник напарника не задан: .moggsynk <ник>"); return; }
            if (!mentions(raw, cfg.partnerName)) return;   // приглашение от чужого игрока
            Matcher q = QUOTED.matcher(raw);
            pendingPhraseText = q.find() ? q.group(1).strip() : cfg.readyPhrase;
            int span = Math.max(1, cfg.replyMaxMs - cfg.replyMinMs + 1);
            pendingPhraseAt = now + cfg.replyMinMs + rnd.nextInt(span);
            armTimer();
            LOGGER.info("Invite from partner, will answer '{}'", pendingPhraseText);
        }
    }

    private void tickRitualReport(long now) {
        if (ritualReportAt == 0 || now < ritualReportAt) return;
        ritualReportAt = 0;
        String me = myName();
        String mine = null;
        for (String l : rewardLines) if (mentions(l, me)) { mine = l; break; }
        if (mine == null && !rewardLines.isEmpty()) mine = rewardLines.get(0);
        int gained = 0;
        if (mine != null) {
            Matcher m = RILLIKI.matcher(mine);
            if (m.find()) { try { gained = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {} }
        }
        totalRilliki += gained;
        pendingCaption = "✅ Ритуал #" + ritualsDone + " выполнен\nАккаунт: " + me
                + "\n" + String.join("\n", rewardLines)
                + (gained > 0 ? "\n+" + gained + " рилликов (всего " + totalRilliki + ")" : "")
                + "\nВремя: " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        screenshotAt = now;
        LOGGER.info("Ritual #{} report (+{})", ritualsDone, gained);
    }

    // ------------------------------------------------------------------ танец

    private void tickDance(MinecraftClient mc, long now) {
        if (ritual != Ritual.DANCING) return;

        if (now - danceStartAt > cfg.danceTimeoutSeconds * 1000L) {
            releaseSneak();
            ritual = Ritual.IDLE;
            armTimer();
            LOGGER.warn("Dance timeout ({} s), aborted", cfg.danceTimeoutSeconds);
            TelegramReporter.sendMessage(cfg, "⏱ MoggSync (" + myName() + "): таймаут танца "
                    + cfg.danceTimeoutSeconds + " c, ритуал прерван, таймер сброшен.");
            return;
        }
        if (now - lastToggleAt >= cfg.danceToggleMs) {
            sneakOn = !sneakOn;
            mc.options.sneakKey.setPressed(sneakOn);
            lastToggleAt = now;
        }
    }

    // ------------------------------------------------------------------ скриншоты в Telegram

    private void tickAutoShot(long now) {
        if (cfg.autoScreenshotMinutes <= 0 || !TelegramReporter.ready(cfg)) return;
        if (nextAutoShotAt == 0) { nextAutoShotAt = now + cfg.autoScreenshotMinutes * 60_000L; return; }
        if (now >= nextAutoShotAt && screenshotAt == 0) {
            nextAutoShotAt = now + cfg.autoScreenshotMinutes * 60_000L;
            pendingCaption = "📸 Статус (" + myName() + ")\n" + statusLine();
            screenshotAt = now + 100;
        }
    }

    private void tickScreenshot(MinecraftClient mc, long now) {
        if (screenshotAt == 0 || now < screenshotAt) return;
        screenshotAt = 0;
        String caption = pendingCaption;
        try (NativeImage img = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer())) {
            Path tmp = Files.createTempFile("moggsync", ".png");
            try {
                img.writeTo(tmp);                                   // публичный метод, пишет PNG
                TelegramReporter.sendPhoto(cfg, Files.readAllBytes(tmp), caption);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            LOGGER.error("Screenshot failed: {}", e.toString());
            TelegramReporter.sendMessage(cfg, caption + "\n(скриншот не удался)");
        }
    }
}
