package com.example.moggsync;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Окно настроек MoggSync. Открывается клавишей [ или командой .moggsynk gui */
public class MoggScreen extends Screen {
    private static final int W = 400, H = 286, ROW = 25;
    private static final int ACCENT = 0xFF7C5CFF, PANEL = 0xFF14161F, HEADER = 0xFF1B1E2B,
            BTN = 0xFF2B2F42, GREEN = 0xFF2FBF71, RED = 0xFFD64545, MUTED = 0xFF9AA0BA, TEXT = 0xFFC9CEE6;

    private final MoggSyncClient mod;
    private final MoggConfig cfg;
    private int tab = 0, left, top;
    private String shownChatId = "";
    private final List<Object[]> labels = new ArrayList<>();

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
            int bg = isHovered() ? brighten(color) : color;
            ctx.fill(x, y, x + w, y + h, bg);
            ctx.fill(x, y + h - 1, x + w, y + h, 0x55000000);
            ctx.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer, getMessage(),
                    x + w / 2, y + (h - 8) / 2, 0xFFFFFFFF);
        }
    }

    // ------------------------------------------------------------ построение

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        labels.clear();
        shownChatId = cfg.CHAT_ID;

        String[] names = {"Основное", "Лобби", "Telegram"};
        int tw = (W - 32 - 12) / 3;
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            addDrawableChild(new FlatButton(left + 16 + i * (tw + 6), top + 52, tw, 20, Text.literal(names[i]),
                    b -> { tab = idx; clearAndInit(); }, i == tab ? ACCENT : BTN));
        }

        int y = top + 84;
        switch (tab) {
            case 0 -> buildMain(y);
            case 1 -> buildLobby(y);
            default -> buildTelegram(y);
        }

        addDrawableChild(new FlatButton(left + W - 16 - 120, top + H - 34, 120, 22,
                Text.literal("Готово"), b -> close(), GREEN));
    }

    private int fieldX() { return left + W - 16 - 190; }

    private void label(String text, int y) { labels.add(new Object[]{text, y}); }

    private void field(int y, String label, String value, int max, boolean numeric, Consumer<String> onChange) {
        label(label, y);
        TextFieldWidget f = new TextFieldWidget(textRenderer, fieldX(), y, 190, 18, Text.literal(label));
        f.setMaxLength(max);
        if (numeric) f.setTextPredicate(s -> s.matches("\\d*"));
        f.setText(value == null ? "" : value);
        f.setChangedListener(onChange);
        addDrawableChild(f);
    }

    private void toggle(int y, String label, Supplier<Boolean> get, Consumer<Boolean> set) {
        label(label, y);
        final FlatButton[] ref = new FlatButton[1];
        boolean on = get.get();
        ref[0] = new FlatButton(fieldX(), y - 1, 190, 20, Text.literal(on ? "ВКЛ" : "ВЫКЛ"), b -> {
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

    private static int parse(String s, int def, int min) {
        try { return Math.max(min, Integer.parseInt(s)); } catch (NumberFormatException e) { return def; }
    }

    private void buildMain(int y) {
        toggle(y, "Мод включён", mod::isEnabled, mod::setEnabled); y += ROW;
        field(y, "Ник напарника", cfg.partnerName, 16, false, s -> cfg.partnerName = s.strip()); y += ROW;
        field(y, "Фраза приглашения", cfg.readyPhrase, 64, false, s -> cfg.readyPhrase = s); y += ROW;
        field(y, "Кд от (сек)", String.valueOf(cfg.timerMinSeconds), 5, true,
                s -> cfg.timerMinSeconds = parse(s, cfg.timerMinSeconds, 1)); y += ROW;
        field(y, "Кд до (сек)", String.valueOf(cfg.timerMaxSeconds), 5, true,
                s -> cfg.timerMaxSeconds = parse(s, cfg.timerMaxSeconds, 1)); y += ROW;
        toggle(y, "Авто-переподключение", () -> cfg.autoReconnect, v -> cfg.autoReconnect = v);
    }

    private void buildLobby(int y) {
        toggle(y, "Авто-вход через лобби", () -> cfg.autoLobby, v -> cfg.autoLobby = v); y += ROW;
        field(y, "Команда лобби", cfg.lobbyCommand, 40, false, s -> cfg.lobbyCommand = s.strip()); y += ROW;
        MoggConfig.MenuStep st = mod.lastStep();
        field(y, "Предмет в меню (имя)", st.item, 40, false, s -> mod.lastStep().item = s.strip()); y += ROW;
        field(y, "Слот в меню (запасной)", st.slot < 0 ? "" : String.valueOf(st.slot), 3, true,
                s -> mod.lastStep().slot = s.isEmpty() ? -1 : parse(s, -1, 0)); y += ROW;
        field(y, "Команда фарма", cfg.farmCommand, 40, false, s -> cfg.farmCommand = s.strip()); y += ROW;
        action(left + 16, y + 2, W - 32, "Проверить вход в лобби сейчас", () -> { close(); mod.startLobbyTest(); });
    }

    private void buildTelegram(int y) {
        toggle(y, "Отчёты в Telegram", () -> cfg.telegramEnabled, v -> cfg.telegramEnabled = v); y += ROW;
        field(y, "Токен бота", cfg.TELEGRAM_BOT_TOKEN, 100, false, s -> cfg.TELEGRAM_BOT_TOKEN = s.strip()); y += ROW;
        field(y, "Chat ID", cfg.CHAT_ID, 24, false, s -> { cfg.CHAT_ID = s.strip(); shownChatId = cfg.CHAT_ID; }); y += ROW;
        field(y, "Скрин каждые N мин", String.valueOf(cfg.autoScreenshotMinutes), 3, true,
                s -> cfg.autoScreenshotMinutes = parse(s, cfg.autoScreenshotMinutes, 0)); y += ROW;
        int bw = (W - 32 - 12) / 3;
        action(left + 16, y + 4, bw, "Найти Chat ID", mod::linkTelegram);
        action(left + 16 + bw + 6, y + 4, bw, "Тест", mod::telegramTest);
        action(left + 16 + 2 * (bw + 6), y + 4, bw, "Скриншот", () -> { close(); mod.requestShot(); });
    }

    // ------------------------------------------------------------ отрисовка

    @Override
    public void tick() {
        // после «Найти Chat ID» значение появляется в конфиге — обновляем поле
        if (tab == 2 && !cfg.CHAT_ID.equals(shownChatId)) clearAndInit();
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // фон рисуем сами в render(), стандартный размытый фон не нужен
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
        ctx.drawText(textRenderer, "парный ритуал • автоматизация", left + 26, top + 23, MUTED, false);

        boolean on = mod.isEnabled();
        String st = on ? "• ВКЛ" : "• ВЫКЛ";
        ctx.drawText(textRenderer, st, left + W - 16 - textRenderer.getWidth(st), top + 17, on ? GREEN : RED, true);

        for (Object[] l : labels)
            ctx.drawText(textRenderer, (String) l[0], left + 16, (Integer) l[1] + 5, TEXT, false);

        String info = mod.lastInfo();
        if (info != null && !info.isEmpty()) {
            if (info.length() > 40) info = info.substring(0, 40) + "…";
            ctx.drawText(textRenderer, info, left + 16, top + H - 26, MUTED, false);
        }

        super.render(ctx, mouseX, mouseY, delta);
    }
}
