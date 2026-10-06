package com.dripps.voxyserver.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

public class VoxyServerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "config.json";

    public int lodStreamRadius = 256;
    public int maxSectionsPerTickPerPlayer = 100;
    public int sectionsPerPacket = 50;
    public boolean generateOnChunkLoad = true;
    public int tickInterval = 5;
    public int workerThreads = 3;
    public boolean dirtyTrackingEnabled = true;
    public int dirtyTrackingInterval = 40;
    public boolean debugTrackingEnabled = false;
    public int debugTrackingInterval = 200;

    public static VoxyServerConfig load(Path dataFolder, Logger logger) {
        Path configPath = dataFolder.resolve(FILE_NAME);
        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                VoxyServerConfig config = GSON.fromJson(json, VoxyServerConfig.class);
                if (config != null) {
                    config.save(dataFolder, logger);
                    return config;
                }
            } catch (Exception e) {
                logger.warning("Failed to load config, using defaults: " + e.getMessage());
            }
        }
        VoxyServerConfig config = new VoxyServerConfig();
        config.save(dataFolder, logger);
        return config;
    }

    public void save(Path dataFolder, Logger logger) {
        Path configPath = dataFolder.resolve(FILE_NAME);
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(this));
        } catch (IOException e) {
            logger.warning("Failed to save config: " + e.getMessage());
        }
    }
}
