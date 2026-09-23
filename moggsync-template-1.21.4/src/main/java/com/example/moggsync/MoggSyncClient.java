package com.example.moggsync;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class MoggSyncClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("moggsync");

    private enum Nav { IDLE, WAIT_OPEN, WAIT_MENU, WAIT_TRANSFER, WAIT_FARM }
    private enum Ritual { IDLE, DANCING }

    private MoggConfig cfg;
    private KeyBinding toggleKey;
    private boolean enabled;

    private Nav nav = Nav.IDLE;
    private long navAt;
    private long menuSeenAt;
    private int lobbyAttempts;

    private Ritual ritual = Ritual.IDLE;
    private long nextSyncAt;
    private long pendingPhraseAt;
    private long danceStartAt;
    private long lastToggleAt;
    private boolean sneakOn;
    private long screenshotAt;
    private String pendingCaption = "";

    private int ritualsDone;
    private String lastMsg = "";
    private long lastMsgAt;
    private final Random rnd = new Random();

    @Override
    public void onInitializeClient() {
        cfg = MoggConfig.load();
        enabled = cfg.enabledOnStart;

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.moggsync.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, "category.moggsync"));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { if (!overlay) onChat(msg.getString()); });
        ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) -> onChat(msg.getString()));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> releaseSneak());

        LOGGER.info("MoggSync loaded, role={}", cfg.role);
    }

    private MinecraftClient mc() { return MinecraftClient.getInstance(); }

    private void say(String text) {
        ClientPlayerEntity p = mc().player;
        if (p == null || text == null || text.isBlank()) return;
        if (text.startsWith("/")) p.networkHandler.sendChatCommand(text.substring(1));
        else p.networkHandler.sendChatMessage(text);
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

    private void onJoin() {
        if (!enabled) return;
        releaseSneak();
        ritual = Ritual.IDLE;
        pendingPhraseAt = 0;
        long now = System.currentTimeMillis();

        if (!cfg.autoLobby) { nav = Nav.IDLE; armTimer(); return; }

        if (nav == Nav.WAIT_TRANSFER) {
            nav = Nav.WAIT_FARM;
            navAt = now + cfg.postTransferDelayMs;
        } else {
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
    }

    private void onTick(MinecraftClient mc) {
        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            if (enabled) armTimer(); else stopAll();
            if (mc.player != null)
                mc.player.sendMessage(Text.literal("[MoggSync] " + (enabled ? "ON" : "OFF")), true);
        }
        if (!enabled || mc.player == null || mc.world == null) return;

        long now = System.currentTimeMillis();
        tickScreenshot(mc, now);
        tickNav(mc, now);
        if (nav != Nav.IDLE) return;

        tickTimers(now);
        tickDance(mc, now);
    }

    private void tickNav(MinecraftClient mc, long now) {
        switch (nav) {
            case IDLE -> {}
            case WAIT_OPEN -> {
                if (now < navAt) return;
                if (++lobbyAttempts > cfg.maxLobbyAttempts) {
                    nav = Nav.IDLE;
                    TelegramReporter.sendMessage(cfg, "⚠️ MoggSync (" + cfg.role + "): не удалось войти на сервер через лобби.");
                    return;
                }
                if (!cfg.lobbyCommand.isBlank()) {
                    say(cfg.lobbyCommand);
                    nav = Nav.WAIT_TRANSFER;
                    navAt = now + cfg.transferTimeoutMs;
                } else {
                    mc.player.getInventory().selectedSlot = Math.max(0, Math.min(8, cfg.lobbyHotbarSlot));
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    nav = Nav.WAIT_MENU;
                    navAt = now + cfg.menuTimeoutMs;
                    menuSeenAt = 0;
                }
            }
            case WAIT_MENU -> {
                if (mc.currentScreen instanceof HandledScreen<?> hs
                        && (cfg.menuTitleContains.isBlank()
                        || hs.getTitle().getString().toLowerCase(Locale.ROOT)
                        .contains(cfg.menuTitleContains.toLowerCase(Locale.ROOT)))) {

                    ScreenHandler h = hs.getScreenHandler();
                    if (menuSeenAt == 0) {
                        menuSeenAt = now;
                        if (cfg.debugLogMenu) {
                            for (int i = 0; i < h.slots.size(); i++) {
                                var st = h.slots.get(i).getStack();
                                if (!st.isEmpty()) LOGGER.info("[menu] slot {} = {}", i, st.getName().getString());
                            }
                        }
                    }
                    if (now - menuSeenAt >= cfg.menuClickDelayMs) {
                        if (cfg.menuSlot >= 0 && cfg.menuSlot < h.slots.size()
                                && !h.slots.get(cfg.menuSlot).getStack().isEmpty()) {
                            mc.interactionManager.clickSlot(h.syncId, cfg.menuSlot, 0, SlotActionType.PICKUP, mc.player);
                            nav = Nav.WAIT_TRANSFER;
                            navAt = now + cfg.transferTimeoutMs;
                        } else {
                            LOGGER.warn("Menu slot {} empty/out of range", cfg.menuSlot);
                            mc.player.closeHandledScreen();
                            nav = Nav.WAIT_OPEN;
                            navAt = now + 2000;
                        }
                    }
                } else if (now >= navAt) {
                    nav = Nav.WAIT_OPEN;
                    navAt = now + 1500;
                }
            }
            case WAIT_TRANSFER -> {
                if (now >= navAt) {
                    nav = Nav.WAIT_FARM;
                    navAt = now + cfg.postTransferDelayMs;
                }
            }
            case WAIT_FARM -> {
                if (now < navAt) return;
                if (!cfg.farmCommand.isBlank()) say(cfg.farmCommand);
                nav = Nav.IDLE;
                armTimer();
            }
        }
    }

    private void tickTimers(long now) {
        if (pendingPhraseAt > 0 && now >= pendingPhraseAt) {
            pendingPhraseAt = 0;
            say(cfg.readyPhrase);
        }
        if (ritual == Ritual.DANCING || nextSyncAt == 0 || now < nextSyncAt) return;

        if (cfg.role == MoggConfig.Role.MASTER) {
            say(cfg.syncTrigger);
            pendingPhraseAt = now + cfg.phraseDelayMs;
            nextSyncAt = now + cfg.retrySeconds * 1000L;
        } else {
            LOGGER.warn("Slave timer expired: master silent, re-arming");
            armTimer();
        }
    }

    private void onChat(String raw) {
        if (!enabled || raw == null) return;
        long now = System.currentTimeMillis();
        if (raw.equals(lastMsg) && now - lastMsgAt < 500) return;
        lastMsg = raw; lastMsgAt = now;
        String low = raw.toLowerCase(Locale.ROOT);

        if (cfg.role == MoggConfig.Role.SLAVE && nav == Nav.IDLE
                && low.contains(cfg.syncTrigger.toLowerCase(Locale.ROOT))
                && (cfg.masterName.isBlank() || raw.contains(cfg.masterName))) {
            armTimer();
            int span = Math.max(1, cfg.slaveReplyMaxMs - cfg.slaveReplyMinMs + 1);
            pendingPhraseAt = now + cfg.slaveReplyMinMs + rnd.nextInt(span);
            return;
        }

        if (ritual == Ritual.IDLE && nav == Nav.IDLE && matchesAny(low, cfg.danceTriggers)) {
            ritual = Ritual.DANCING;
            danceStartAt = now;
            lastToggleAt = 0;
            nextSyncAt = 0;
            pendingPhraseAt = 0;
            LOGGER.info("Dance started");
            return;
        }

        if (ritual == Ritual.DANCING && matchesAny(low, cfg.rewardTriggers)) {
            releaseSneak();
            ritual = Ritual.IDLE;
            ritualsDone++;
            armTimer();
            pendingCaption = String.format("✅ Ритуал #%d выполнен%nРоль: %s%nРиллики/награда: %s%nВремя: %s",
                    ritualsDone, cfg.role, raw.strip(),
                    LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
            screenshotAt = now + 700;
            LOGGER.info("Ritual #{} done", ritualsDone);
        }
    }

    private void tickDance(MinecraftClient mc, long now) {
        if (ritual != Ritual.DANCING) return;

        if (now - danceStartAt > cfg.danceTimeoutSeconds * 1000L) {
            releaseSneak();
            ritual = Ritual.IDLE;
            armTimer();
            LOGGER.warn("Dance timeout ({} s), aborted", cfg.danceTimeoutSeconds);
            TelegramReporter.sendMessage(cfg, "⏱ MoggSync (" + cfg.role + "): таймаут танца "
                    + cfg.danceTimeoutSeconds + " c, ритуал прерван, таймер сброшен.");
            return;
        }
        if (now - lastToggleAt >= cfg.danceToggleMs) {
            sneakOn = !sneakOn;
            mc.options.sneakKey.setPressed(sneakOn);
            lastToggleAt = now;
        }
    }

    private void tickScreenshot(MinecraftClient mc, long now) {
        if (screenshotAt == 0 || now < screenshotAt) return;
        screenshotAt = 0;
        String caption = pendingCaption;
        try (NativeImage img = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer())) {
            byte[] png = img.getBytes();
            TelegramReporter.sendPhoto(cfg, png, caption);
        } catch (Exception e) {
            LOGGER.error("Screenshot failed: {}", e.toString());
            TelegramReporter.sendMessage(cfg, caption + "\n(скриншот не удался)");
        }
    }
}
