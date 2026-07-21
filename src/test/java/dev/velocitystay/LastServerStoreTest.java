package dev.velocitystay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LastServerStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsAndReloadsLastServers() throws IOException {
        Path file = temporaryDirectory.resolve(LastServerStore.FILE_NAME);
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        LastServerStore writer = new LastServerStore(file);

        assertTrue(writer.remember(firstPlayer, "survival"));
        assertFalse(writer.remember(firstPlayer, "survival"));
        assertTrue(writer.remember(secondPlayer, "creative"));
        writer.save();

        LastServerStore reader = new LastServerStore(file);
        reader.load();

        assertEquals("survival", reader.get(firstPlayer).orElseThrow());
        assertEquals("creative", reader.get(secondPlayer).orElseThrow());
        assertEquals(2, reader.size());
    }

    @Test
    void ignoresMalformedEntriesWithoutLosingValidData() throws IOException {
        UUID playerId = UUID.randomUUID();
        Path file = temporaryDirectory.resolve(LastServerStore.FILE_NAME);
        Files.writeString(
                file,
                "not-a-uuid=broken\n" + playerId + "=lobby\n" + UUID.randomUUID() + "=  \n",
                StandardCharsets.UTF_8);

        LastServerStore store = new LastServerStore(file);
        store.load();

        assertEquals("lobby", store.get(playerId).orElseThrow());
        assertEquals(1, store.size());
    }
}
