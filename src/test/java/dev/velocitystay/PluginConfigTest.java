package dev.velocitystay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginConfigTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsAndLoadsDefaultConfiguration() throws IOException {
        PluginConfig config = PluginConfig.load(temporaryDirectory);

        assertTrue(Files.isRegularFile(temporaryDirectory.resolve(PluginConfig.FILE_NAME)));
        assertTrue(config.restoreLastServer());
        assertTrue(config.retryEnabled());
        assertEquals(5, config.retryDelaySeconds());
        assertEquals(0, config.maxRetryAttempts());
        assertTrue(config.retryReasonKeywords().contains("server closed"));
    }

    @Test
    void readsValidValuesAndFallsBackForInvalidNumbers() throws IOException {
        Files.writeString(
                temporaryDirectory.resolve(PluginConfig.FILE_NAME),
                String.join(
                        "\n",
                        "restore-last-server=false",
                        "retry.enabled=false",
                        "retry.delay-seconds=0",
                        "retry.max-attempts=4",
                        "retry.outage-check-timeout-millis=1500",
                        "retry.reason-keywords=Custom reason, *",
                        "messages.waiting=Waiting for {server}"),
                StandardCharsets.UTF_8);

        PluginConfig config = PluginConfig.load(temporaryDirectory);

        assertFalse(config.restoreLastServer());
        assertFalse(config.retryEnabled());
        assertEquals(5, config.retryDelaySeconds());
        assertEquals(4, config.maxRetryAttempts());
        assertEquals(1_500, config.outageCheckTimeoutMillis());
        assertEquals("custom reason", config.retryReasonKeywords().get(0));
        assertEquals("*", config.retryReasonKeywords().get(1));
        assertEquals("Waiting for {server}", config.waitingMessage());
    }
}
