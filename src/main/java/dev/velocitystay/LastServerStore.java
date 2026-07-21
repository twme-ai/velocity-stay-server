package dev.velocitystay;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class LastServerStore {
    static final String FILE_NAME = "last-servers.properties";

    private final Path file;
    private final Map<UUID, String> lastServers = new ConcurrentHashMap<>();

    LastServerStore(Path file) {
        this.file = file;
    }

    void load() throws IOException {
        lastServers.clear();
        if (!Files.exists(file)) {
            return;
        }

        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        for (String rawUuid : properties.stringPropertyNames()) {
            String serverName = properties.getProperty(rawUuid, "").trim();
            if (serverName.isEmpty()) {
                continue;
            }
            try {
                lastServers.put(UUID.fromString(rawUuid), serverName);
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed entries without making the rest of the data unusable.
            }
        }
    }

    Optional<String> get(UUID playerId) {
        return Optional.ofNullable(lastServers.get(playerId));
    }

    boolean remember(UUID playerId, String serverName) {
        String normalized = serverName.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        return !normalized.equals(lastServers.put(playerId, normalized));
    }

    int size() {
        return lastServers.size();
    }

    synchronized void save() throws IOException {
        Path parent = file.getParent();
        Files.createDirectories(parent);

        Properties properties = new Properties();
        for (Map.Entry<UUID, String> entry : new HashMap<>(lastServers).entrySet()) {
            properties.setProperty(entry.getKey().toString(), entry.getValue());
        }

        Path temporary = Files.createTempFile(parent, file.getFileName().toString() + ".", ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "Velocity Stay Server last-server data");
            }
            moveIntoPlace(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void moveIntoPlace(Path temporary) throws IOException {
        try {
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
