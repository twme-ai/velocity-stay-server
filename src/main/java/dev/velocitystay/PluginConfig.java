package dev.velocitystay;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

final class PluginConfig {
    static final String FILE_NAME = "config.properties";

    private static final boolean DEFAULT_RESTORE_LAST_SERVER = true;
    private static final boolean DEFAULT_RETRY_ENABLED = true;
    private static final int DEFAULT_RETRY_DELAY_SECONDS = 5;
    private static final int DEFAULT_MAX_RETRY_ATTEMPTS = 0;
    private static final int DEFAULT_OUTAGE_CHECK_TIMEOUT_MILLIS = 2_000;
    private static final String DEFAULT_REASON_KEYWORDS =
            "server closed,restart,restarting,server is starting,server still starting,"
                    + "shutdown,shutting down,server_shutdown";
    private static final String DEFAULT_WAITING_MESSAGE =
            "Server {server} is restarting. Retrying every {seconds} seconds...";
    private static final String DEFAULT_RECONNECTED_MESSAGE = "Reconnected to {server}.";
    private static final String DEFAULT_GAVE_UP_MESSAGE =
            "Could not reconnect to {server}; using Velocity's fallback.";

    private final boolean restoreLastServer;
    private final boolean retryEnabled;
    private final int retryDelaySeconds;
    private final int maxRetryAttempts;
    private final int outageCheckTimeoutMillis;
    private final List<String> retryReasonKeywords;
    private final String waitingMessage;
    private final String reconnectedMessage;
    private final String gaveUpMessage;

    private PluginConfig(
            boolean restoreLastServer,
            boolean retryEnabled,
            int retryDelaySeconds,
            int maxRetryAttempts,
            int outageCheckTimeoutMillis,
            List<String> retryReasonKeywords,
            String waitingMessage,
            String reconnectedMessage,
            String gaveUpMessage) {
        this.restoreLastServer = restoreLastServer;
        this.retryEnabled = retryEnabled;
        this.retryDelaySeconds = retryDelaySeconds;
        this.maxRetryAttempts = maxRetryAttempts;
        this.outageCheckTimeoutMillis = outageCheckTimeoutMillis;
        this.retryReasonKeywords = retryReasonKeywords;
        this.waitingMessage = waitingMessage;
        this.reconnectedMessage = reconnectedMessage;
        this.gaveUpMessage = gaveUpMessage;
    }

    static PluginConfig defaults() {
        return from(new Properties());
    }

    static PluginConfig load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path configFile = dataDirectory.resolve(FILE_NAME);
        createDefaultFile(configFile);

        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return from(properties);
    }

    private static void createDefaultFile(Path configFile) throws IOException {
        if (Files.exists(configFile)) {
            return;
        }

        Properties defaults = new Properties();
        defaults.setProperty("restore-last-server", Boolean.toString(DEFAULT_RESTORE_LAST_SERVER));
        defaults.setProperty("retry.enabled", Boolean.toString(DEFAULT_RETRY_ENABLED));
        defaults.setProperty("retry.delay-seconds", Integer.toString(DEFAULT_RETRY_DELAY_SECONDS));
        defaults.setProperty("retry.max-attempts", Integer.toString(DEFAULT_MAX_RETRY_ATTEMPTS));
        defaults.setProperty(
                "retry.outage-check-timeout-millis",
                Integer.toString(DEFAULT_OUTAGE_CHECK_TIMEOUT_MILLIS));
        defaults.setProperty("retry.reason-keywords", DEFAULT_REASON_KEYWORDS);
        defaults.setProperty("messages.waiting", DEFAULT_WAITING_MESSAGE);
        defaults.setProperty("messages.reconnected", DEFAULT_RECONNECTED_MESSAGE);
        defaults.setProperty("messages.gave-up", DEFAULT_GAVE_UP_MESSAGE);

        try (BufferedWriter writer = Files.newBufferedWriter(
                configFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            defaults.store(writer, "Velocity Stay Server configuration");
        } catch (FileAlreadyExistsException ignored) {
            // Another initialization path created it first.
        }
    }

    private static PluginConfig from(Properties properties) {
        return new PluginConfig(
                booleanValue(properties, "restore-last-server", DEFAULT_RESTORE_LAST_SERVER),
                booleanValue(properties, "retry.enabled", DEFAULT_RETRY_ENABLED),
                integerValue(properties, "retry.delay-seconds", DEFAULT_RETRY_DELAY_SECONDS, 1, 3_600),
                integerValue(properties, "retry.max-attempts", DEFAULT_MAX_RETRY_ATTEMPTS, 0, Integer.MAX_VALUE),
                integerValue(
                        properties,
                        "retry.outage-check-timeout-millis",
                        DEFAULT_OUTAGE_CHECK_TIMEOUT_MILLIS,
                        250,
                        30_000),
                keywordList(properties.getProperty("retry.reason-keywords", DEFAULT_REASON_KEYWORDS)),
                properties.getProperty("messages.waiting", DEFAULT_WAITING_MESSAGE),
                properties.getProperty("messages.reconnected", DEFAULT_RECONNECTED_MESSAGE),
                properties.getProperty("messages.gave-up", DEFAULT_GAVE_UP_MESSAGE));
    }

    private static boolean booleanValue(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(value.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(value.trim())) {
            return false;
        }
        return fallback;
    }

    private static int integerValue(Properties properties, String key, int fallback, int min, int max) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= min && parsed <= max ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static List<String> keywordList(String csv) {
        List<String> keywords = new ArrayList<>();
        for (String item : csv.split(",")) {
            String keyword = item.trim().toLowerCase(Locale.ROOT);
            if (!keyword.isEmpty()) {
                keywords.add(keyword);
            }
        }
        return Collections.unmodifiableList(keywords);
    }

    boolean restoreLastServer() {
        return restoreLastServer;
    }

    boolean retryEnabled() {
        return retryEnabled;
    }

    int retryDelaySeconds() {
        return retryDelaySeconds;
    }

    int maxRetryAttempts() {
        return maxRetryAttempts;
    }

    int outageCheckTimeoutMillis() {
        return outageCheckTimeoutMillis;
    }

    List<String> retryReasonKeywords() {
        return retryReasonKeywords;
    }

    String waitingMessage() {
        return waitingMessage;
    }

    String reconnectedMessage() {
        return reconnectedMessage;
    }

    String gaveUpMessage() {
        return gaveUpMessage;
    }
}
