package com.example.moggsync;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Окно настроек MoggSync. Открывается клавишей [ или командой .moggsynk gui */
public class MoggScreen extends Screen {
    private static final int W = 420, H = 300, ROW = 22;
    private static final int ACCENT = 0xFF7C5CFF, PANEL = 0xFF14161F, HEADER = 0xFF1B1E2B,
            BTN = 0xFF2B2F42, GREEN = 0xFF2FBF71, RED = 0xFFD64545, MUTED = 0xFF9AA0BA, TEXT = 0xFFC9CEE6;
    private static final String[] TABS = {"Основное", "Ритуал", "Лобби", "Сервер", "Telegram"};

    private record Txt(String text, int y, int color) {}

    private final MoggSyncClient mod;
    private final MoggConfig cfg;
    private int tab = 0, left, top;
    private String shownChatId = "";
    private final List<Txt> texts = new ArrayList<>();

    public MoggScreen() {
        super(Text.literal("MoggSync"));
        this.mod = MoggSyncClient.get();
        this.cfg = mod.cfg();
    }

    // ------------------------------------------------------------ кнопка в плоском стиле

    private static final class FlatButton extends ButtonWidget {
        private int color;

        FlatButton(int x, int y, int w, int h, Text label, ButtonWidget.PressAction action, int color) {
            super(x, y, w, h, label, action, ButtonWidget.DEFAULT_NARRATION_SUPPLIER);
            this.color = color;
        }

        void setColor(int c) { this.color = c; }

        private static int brighten(int c) {
            int r = Math.min(255, ((c >> 16) & 255) + 24);
            int g = Math.min(255, ((c >> 8) & 255) + 24);
            int b = Math.min(255, (c & 255) + 24);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        @Override
        public void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            ctx.fill(x, y, x + w, y + h, isHovered() ? brighten(color) : color);
            ctx.fill(x, y + h - 1, x + w, y + h, 0x55000000);
            ctx.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer, getMessage(),
                    x + w / 2, y + (h - 8) / 2, 0xFFFFFFFF);
        }
    }

    // ------------------------------------------------------------ построение

    private Text masterText() { return Text.literal(mod.isEnabled() ? "МОД: ВКЛ" : "МОД: ВЫКЛ"); }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        texts.clear();
        shownChatId = cfg.CHAT_ID;

        // главный выключатель: полностью останавливает мод (для PvP и т.п.)
        final FlatButton[] master = new FlatButton[1];
        master[0] = new FlatButton(left + W - 16 - 104, top + 9, 104, 24, masterText(), b -> {
            mod.setEnabled(!mod.isEnabled());
            master[0].setMessage(masterText());
            master[0].setColor(mod.isEnabled() ? GREEN : RED);
        }, mod.isEnabled() ? GREEN : RED);
        addDrawableChild(master[0]);

        int tw = (W - 32 - (TABS.length - 1) * 4) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            addDrawableChild(new FlatButton(left + 16 + i * (tw + 4), top + 50, tw, 20, Text.literal(TABS[i]),
                    b -> { tab = idx; clearAndInit(); }, i == tab ? ACCENT : BTN));
        }

        int y = top + 80;
        switch (tab) {
            case 0 -> buildMain(y);
            case 1 -> buildRitual(y);
            case 2 -> buildLobby(y);
            case 3 -> buildServer(y);
            default -> buildTelegram(y);
        }

        addDrawableChild(new FlatButton(left + W - 16 - 120, top + H - 34, 120, 22,
                Text.literal("Готово"), b -> close(), GREEN));
    }

    private int fieldX() { return left + W - 16 - 190; }

    private void label(String text, int y) { texts.add(new Txt(text, y + 5, TEXT)); }
    private void note(String text, int y) { texts.add(new Txt(text, y, MUTED)); }

    private void field(int y, String label, String value, int max, boolean numeric, Consumer<String> onChange) {
        label(label, y);
        TextFieldWidget f = new TextFieldWidget(textRenderer, fieldX(), y, 190, 18, Text.literal(label));
        f.setMaxLength(max);
        if (numeric) f.setTextPredicate(s -> s.matches("\\d*"));
        f.setText(value == null ? "" : value);
        f.setChangedListener(onChange);
        addDrawableChild(f);
    }

    /** Числовое поле: пустое значение игнорируется, меньше min не пропускается. */
    private void num(int y, String label, int value, int min, IntConsumer set) {
        field(y, label, String.valueOf(value), 6, true, s -> {
            if (s.isEmpty()) return;
            try { set.accept(Math.max(min, Integer.parseInt(s))); } catch (NumberFormatException ignored) {}
        });
    }

    private void toggle(int y, String label, Supplier<Boolean> get, Consumer<Boolean> set) {
        label(label, y);
        final FlatButton[] ref = new FlatButton[1];
        boolean on = get.get();
        ref[0] = new FlatButton(fieldX(), y, 190, 18, Text.literal(on ? "ВКЛ" : "ВЫКЛ"), b -> {
            boolean v = !get.get();
            set.accept(v);
            ref[0].setMessage(Text.literal(v ? "ВКЛ" : "ВЫКЛ"));
            ref[0].setColor(v ? GREEN : RED);
        }, on ? GREEN : RED);
        addDrawableChild(ref[0]);
    }

    private FlatButton action(int x, int y, int w, String text, Runnable r) {
        return addDrawableChild(new FlatButton(x, y, w, 20, Text.literal(text), b -> r.run(), BTN));
    }

    // ------------------------------------------------------------ вкладки

    private void buildMain(int y) {
        toggle(y, "Включать при запуске игры", () -> cfg.enabledOnStart, v -> cfg.enabledOnStart = v); y += ROW;
        field(y, "Только на аккаунтах", cfg.activeAccounts, 80, false, s -> cfg.activeAccounts = s.strip()); y += ROW;
        field(y, "Ник напарника", cfg.partnerName, 16, false, s -> cfg.partnerName = s.strip()); y += ROW;
        field(y, "Фраза приглашения", cfg.readyPhrase, 64, false, s -> cfg.readyPhrase = s); y += ROW;
        toggle(y, "Авто-переподключение", () -> cfg.autoReconnect, v -> cfg.autoReconnect = v); y += ROW;
        num(y, "Переподключение через, с", cfg.reconnectDelaySeconds, 1, v -> cfg.reconnectDelaySeconds = v); y += ROW;
        toggle(y, "Подробные сообщения в чат", () -> cfg.debugLogMenu, v -> cfg.debugLogMenu = v); y += ROW;
        note("Аккаунты: Ник1, Ник2. Пусто = мод работает на любом. На другом аккаунте", y + 4);
        note("(например для PvP) мод полностью молчит. Кнопка МОД вверху — выкл. сразу.", y + 15);
    }

    private void buildRitual(int y) {
        num(y, "Кд от, сек", cfg.timerMinSeconds, 1, v -> cfg.timerMinSeconds = v); y += ROW;
        num(y, "Кд до, сек", cfg.timerMaxSeconds, 1, v -> cfg.timerMaxSeconds = v); y += ROW;
        num(y, "Повтор приглашения, сек", cfg.retrySeconds, 5, v -> cfg.retrySeconds = v); y += ROW;
        num(y, "Ответ напарнику от, мс", cfg.replyMinMs, 0, v -> cfg.replyMinMs = v); y += ROW;
        num(y, "Ответ напарнику до, мс", cfg.replyMaxMs, 0, v -> cfg.replyMaxMs = v); y += ROW;
        num(y, "Шифт каждые, мс", cfg.danceToggleMs, 50, v -> cfg.danceToggleMs = v); y += ROW;
        num(y, "Таймаут танца, сек", cfg.danceTimeoutSeconds, 5, v -> cfg.danceTimeoutSeconds = v); y += ROW;
        note("Кд — случайная пауза между ритуалами. Ответ — задержка перед фразой.", y + 4);
    }

    private void buildLobby(int y) {
        toggle(y, "Авто-вход через лобби", () -> cfg.autoLobby, v -> cfg.autoLobby = v); y += ROW;
        field(y, "Команда лобби", cfg.lobbyCommand, 40, false, s -> cfg.lobbyCommand = s.strip()); y += ROW;
        num(y, "Слот хотбара (1-9)", cfg.lobbyHotbarSlot + 1, 1, v -> cfg.lobbyHotbarSlot = Math.min(9, v) - 1); y += ROW;
        MoggConfig.MenuStep st = mod.lastStep();
        field(y, "Предмет в меню (имя)", st.item, 40, false, s -> mod.lastStep().item = s.strip()); y += ROW;
        field(y, "Слот в меню (запасной)", st.slot < 0 ? "" : String.valueOf(st.slot), 3, true,
                s -> mod.lastStep().slot = s.isEmpty() ? -1 : Integer.parseInt(s)); y += ROW;
        num(y, "Пауза перед кликом, мс", cfg.menuClickDelayMs, 0, v -> cfg.menuClickDelayMs = v); y += ROW;
        field(y, "Команда фарма", cfg.farmCommand, 40, false, s -> cfg.farmCommand = s.strip()); y += ROW;
        note("Имя предмета важнее слота. Слоты считаются с 0. Хотбар — если команды нет.", y + 4);
        action(left + 16, top + H - 33, 150, "Проверить вход", () -> { close(); mod.startLobbyTest(); });
    }

    private void buildServer(int y) {
        field(y, "Заголовок меню", mod.lastStep().title, 40, false, s -> mod.lastStep().title = s.strip()); y += ROW;
        field(y, "Адрес сервера содержит", cfg.serverAddressContains, 40, false, s -> cfg.serverAddressContains = s.strip()); y += ROW;
        num(y, "Ждать после входа, мс", cfg.lobbyJoinDelayMs, 0, v -> cfg.lobbyJoinDelayMs = v); y += ROW;
        num(y, "Попыток входа", cfg.maxLobbyAttempts, 1, v -> cfg.maxLobbyAttempts = v); y += ROW;
        num(y, "Ждать перехода, мс", cfg.transferTimeoutMs, 1000, v -> cfg.transferTimeoutMs = v); y += ROW;
        num(y, "Пауза после перехода, мс", cfg.postTransferDelayMs, 0, v -> cfg.postTransferDelayMs = v); y += ROW;
        num(y, "Повтор при «недоступен», мс", cfg.unavailableRetryMs, 1000, v -> cfg.unavailableRetryMs = v); y += ROW;
        num(y, "Макс. повторов «недоступен»", cfg.unavailableMaxRetries, 1, v -> cfg.unavailableMaxRetries = v); y += ROW;
        note("Адрес: пусто = любой мультиплеер-сервер (в одиночной игре лобби не работает).", y + 4);
    }

    private static String norm(String m) { return m == null ? "OFF" : m.toUpperCase(Locale.ROOT); }

    private Text modeText() {
        return Text.literal(switch (norm(cfg.tgChatMode)) {
            case "ALL" -> "ВЕСЬ ЧАТ";
            case "MENTIONS" -> "ТОЛЬКО ПРО МЕНЯ";
            default -> "ВЫКЛ";
        });
    }

    private int modeColor() {
        return switch (norm(cfg.tgChatMode)) { case "ALL" -> GREEN; case "MENTIONS" -> ACCENT; default -> RED; };
    }

    private void chatMode(int y) {
        label("Чат игры в Telegram", y);
        final FlatButton[] ref = new FlatButton[1];
        ref[0] = new FlatButton(fieldX(), y, 190, 18, modeText(), b -> {
            cfg.tgChatMode = switch (norm(cfg.tgChatMode)) {
                case "OFF" -> "MENTIONS";
                case "MENTIONS" -> "ALL";
                default -> "OFF";
            };
            ref[0].setMessage(modeText());
            ref[0].setColor(modeColor());
        }, modeColor());
        addDrawableChild(ref[0]);
    }

    private void buildTelegram(int y) {
        toggle(y, "Отчёты в Telegram", () -> cfg.telegramEnabled, v -> cfg.telegramEnabled = v); y += ROW;
        field(y, "Токен бота", cfg.TELEGRAM_BOT_TOKEN, 100, false, s -> cfg.TELEGRAM_BOT_TOKEN = s.strip()); y += ROW;
        field(y, "Chat ID", cfg.CHAT_ID, 24, false, s -> { cfg.CHAT_ID = s.strip(); shownChatId = cfg.CHAT_ID; }); y += ROW;
        num(y, "Скрин каждые, мин (0=выкл)", cfg.autoScreenshotMinutes, 0, v -> cfg.autoScreenshotMinutes = v); y += ROW;
        chatMode(y); y += ROW;
        toggle(y, "Управление из Telegram", () -> cfg.tgControl, v -> cfg.tgControl = v); y += ROW;
        int bw = (W - 32 - 12) / 3;
        action(left + 16, y + 2, bw, "Найти Chat ID", mod::linkTelegram);
        action(left + 16 + bw + 6, y + 2, bw, "Тест", mod::telegramTest);
        action(left + 16 + 2 * (bw + 6), y + 2, bw, "Скриншот", () -> { close(); mod.requestShot(); });
        note("Управление: текст боту уходит в чат игры, !home = /home, /help — команды.", y + 26);
        note("На каждом ПК нужен свой бот, иначе два клиента мешают друг другу.", y + 37);
    }

    // ------------------------------------------------------------ отрисовка

    @Override
    public void tick() {
        // после «Найти Chat ID» значение появляется в конфиге — обновляем поле
        if (tab == 4 && !cfg.CHAT_ID.equals(shownChatId)) clearAndInit();
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // фон рисуем сами в render()
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void close() {
        mod.onConfigChanged();
        MoggConfig.save(cfg);
        super.close();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xA0000000);
        ctx.fill(left - 1, top - 1, left + W + 1, top + H + 1, ACCENT);
        ctx.fill(left, top, left + W, top + H, PANEL);
        ctx.fill(left, top, left + W, top + 42, HEADER);
        ctx.fill(left, top + 42, left + W, top + 43, ACCENT);
        ctx.fill(left + 16, top + 11, left + 19, top + 31, ACCENT);

        ctx.drawText(textRenderer, "MoggSync", left + 26, top + 11, 0xFFFFFFFF, true);
        String status = mod.timerText();
        if (status.length() > 44) status = status.substring(0, 44) + "…";
        ctx.drawText(textRenderer, status, left + 26, top + 23, MUTED, false);

        for (Txt t : texts) ctx.drawText(textRenderer, t.text(), left + 16, t.y(), t.color(), false);

        String info = mod.lastInfo();
        if (info != null && !info.isEmpty()) {
            if (info.length() > 40) info = info.substring(0, 40) + "…";
            ctx.drawText(textRenderer, info, left + 16, top + H - 12, MUTED, false);
        }

        super.render(ctx, mouseX, mouseY, delta);
    }
}
