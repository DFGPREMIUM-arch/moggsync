package com.example.moggsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class MoggConfig {
    public enum Role { MASTER, SLAVE }

    public Role role = Role.MASTER;
    public boolean enabledOnStart = true;

    public int timerMinSeconds = 300;
    public int timerMaxSeconds = 480;
    public String syncTrigger = ".moggsynk";
    public String masterName = "";
    public String readyPhrase = "я тебя могу";
    public int phraseDelayMs = 700;
    public int slaveReplyMinMs = 1000;
    public int slaveReplyMaxMs = 2000;
    public int retrySeconds = 90;

    public List<String> danceTriggers = List.of("станцевал секретный танец", "mog ritual");
    public List<String> rewardTriggers = List.of("получает награду", "вы получили");
    public int danceToggleMs = 250;
    public int danceTimeoutSeconds = 30;

    public boolean autoLobby = true;
    public String lobbyCommand = "";
    public int lobbyHotbarSlot = 0;
    public String menuTitleContains = "";
    public int menuSlot = 10;
    public boolean debugLogMenu = true;
    public int lobbyJoinDelayMs = 3000;
    public int menuClickDelayMs = 500;
    public int menuTimeoutMs = 5000;
    public int transferTimeoutMs = 15000;
    public int postTransferDelayMs = 4000;
    public int maxLobbyAttempts = 5;
    public String farmCommand = "";

    public boolean telegramEnabled = true;
    public String TELEGRAM_BOT_TOKEN = "";
    public String CHAT_ID = "";

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
                    if (c != null) { save(c); return c; }
                }
            }
        } catch (Exception e) {
            MoggSyncClient.LOGGER.error("Config load failed: {}", e.toString());
        }
        MoggConfig c = new MoggConfig();
        save(c);
        return c;
    }

    public static void save(MoggConfig c) {
        try { Files.writeString(path(), GSON.toJson(c)); }
        catch (Exception e) { MoggSyncClient.LOGGER.error("Config save failed: {}", e.toString()); }
    }
}
