package dev.velocitystay;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

class VelocityStayServerTest {
    @TempDir
    Path dataDirectory;

    private ProxyServer proxy;
    private Logger logger;
    private Scheduler scheduler;
    private Scheduler.TaskBuilder taskBuilder;
    private AtomicReference<Runnable> scheduledRunnable;
    private VelocityStayServer plugin;

    @BeforeEach
    void setUp() {
        proxy = mock(ProxyServer.class);
        logger = mock(Logger.class);
        scheduler = mock(Scheduler.class);
        taskBuilder = mock(Scheduler.TaskBuilder.class);
        scheduledRunnable = new AtomicReference<>();

        when(proxy.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(any(), org.mockito.ArgumentMatchers.<Runnable>any()))
                .thenAnswer(invocation -> {
                    scheduledRunnable.set(invocation.getArgument(1));
                    return taskBuilder;
                });
        when(taskBuilder.delay(any(Duration.class))).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(mock(ScheduledTask.class));

        plugin = new VelocityStayServer(proxy, logger, dataDirectory);
        plugin.onProxyInitialize(new ProxyInitializeEvent());
    }

    @Test
    void restoresSavedServerOnInitialConnection() throws Exception {
        UUID playerId = UUID.randomUUID();
        RegisteredServer fallback = server("lobby", 25566);
        RegisteredServer previous = server("survival", 25567);
        Player player = player(playerId);

        LastServerStore store = new LastServerStore(dataDirectory.resolve(LastServerStore.FILE_NAME));
        store.remember(playerId, "survival");
        store.save();
        plugin.onProxyInitialize(new ProxyInitializeEvent());
        when(proxy.getServer("survival")).thenReturn(Optional.of(previous));

        PlayerChooseInitialServerEvent event = new PlayerChooseInitialServerEvent(player, fallback);
        plugin.onChooseInitialServer(event);

        assertSame(previous, event.getInitialServer().orElseThrow());
    }

    @Test
    void failedInitialRestoreKeepsVelocityFallbackResult() throws Exception {
        UUID playerId = UUID.randomUUID();
        RegisteredServer fallback = server("lobby", 25566);
        RegisteredServer previous = server("survival", 25567);
        Player player = player(playerId);

        LastServerStore store = new LastServerStore(dataDirectory.resolve(LastServerStore.FILE_NAME));
        store.remember(playerId, "survival");
        store.save();
        plugin.onProxyInitialize(new ProxyInitializeEvent());
        when(proxy.getServer("survival")).thenReturn(Optional.of(previous));

        plugin.onChooseInitialServer(new PlayerChooseInitialServerEvent(player, fallback));
        KickedFromServerEvent.ServerKickResult fallbackResult =
                KickedFromServerEvent.RedirectPlayer.create(fallback);
        KickedFromServerEvent kicked = new KickedFromServerEvent(
                player, previous, Component.text("Connection refused"), true, fallbackResult);

        EventTask task = plugin.onKickedFromServer(kicked);

        assertNull(task);
        assertSame(fallbackResult, kicked.getResult());
        verify(scheduler, never()).buildTask(any(), org.mockito.ArgumentMatchers.<Runnable>any());
    }

    @Test
    void backendShutdownKeepsPlayerConnectedAndRedirectsAfterDelay() {
        RegisteredServer fallback = server("lobby", 25566);
        RegisteredServer survival = server("survival", 25567);
        Player player = establishedPlayer(UUID.randomUUID(), survival);
        when(proxy.getServer("survival")).thenReturn(Optional.of(survival));
        KickedFromServerEvent kicked = new KickedFromServerEvent(
                player,
                survival,
                Component.text("Server closed"),
                false,
                KickedFromServerEvent.RedirectPlayer.create(fallback));

        EventTask task = plugin.onKickedFromServer(kicked);

        assertNotNull(task);
        CompletableFuture<Void> resumed = execute(task);
        Runnable reconnect = scheduledRunnable.get();
        assertNotNull(reconnect);
        reconnect.run();
        resumed.join();

        KickedFromServerEvent.RedirectPlayer redirect =
                assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, kicked.getResult());
        assertSame(survival, redirect.getServer());
        ArgumentCaptor<Duration> delay = ArgumentCaptor.forClass(Duration.class);
        verify(taskBuilder).delay(delay.capture());
        assertTrue(delay.getValue().equals(Duration.ofSeconds(5)));
        verify(player).sendMessage(any(Component.class));
    }

    @Test
    void intentionalKickFromOnlineServerUsesVelocityDefault() {
        RegisteredServer fallback = server("lobby", 25566);
        RegisteredServer survival = server("survival", 25567);
        Player player = establishedPlayer(UUID.randomUUID(), survival);
        when(survival.ping()).thenReturn(CompletableFuture.completedFuture(mock(ServerPing.class)));
        KickedFromServerEvent.ServerKickResult fallbackResult =
                KickedFromServerEvent.RedirectPlayer.create(fallback);
        KickedFromServerEvent kicked = new KickedFromServerEvent(
                player, survival, Component.text("You are banned"), false, fallbackResult);

        EventTask task = plugin.onKickedFromServer(kicked);

        assertNotNull(task);
        execute(task).join();
        assertSame(fallbackResult, kicked.getResult());
        verify(scheduler, never()).buildTask(any(), org.mockito.ArgumentMatchers.<Runnable>any());
        verify(player, never()).sendMessage(any(Component.class));
    }

    @Test
    void unknownKickFromOfflineServerStartsRetrying() {
        RegisteredServer fallback = server("lobby", 25566);
        RegisteredServer survival = server("survival", 25567);
        Player player = establishedPlayer(UUID.randomUUID(), survival);
        CompletableFuture<ServerPing> failedPing = new CompletableFuture<>();
        failedPing.completeExceptionally(new IllegalStateException("offline"));
        when(survival.ping()).thenReturn(failedPing);
        when(proxy.getServer("survival")).thenReturn(Optional.of(survival));
        KickedFromServerEvent kicked = new KickedFromServerEvent(
                player,
                survival,
                Component.text("Unexpected backend error"),
                false,
                KickedFromServerEvent.RedirectPlayer.create(fallback));

        EventTask task = plugin.onKickedFromServer(kicked);

        assertNotNull(task);
        CompletableFuture<Void> resumed = execute(task);
        scheduledRunnable.get().run();
        resumed.join();
        KickedFromServerEvent.RedirectPlayer redirect =
                assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, kicked.getResult());
        assertSame(survival, redirect.getServer());
    }

    @Test
    void onlineServerRejectionEndsAnExistingRetrySession() {
        RegisteredServer fallback = server("lobby", 25566);
        RegisteredServer survival = server("survival", 25567);
        Player player = establishedPlayer(UUID.randomUUID(), survival);
        when(proxy.getServer("survival")).thenReturn(Optional.of(survival));

        KickedFromServerEvent outage = new KickedFromServerEvent(
                player,
                survival,
                Component.text("Server closed"),
                false,
                KickedFromServerEvent.RedirectPlayer.create(fallback));
        EventTask firstTask = plugin.onKickedFromServer(outage);
        CompletableFuture<Void> firstResume = execute(firstTask);
        scheduledRunnable.get().run();
        firstResume.join();

        when(survival.ping()).thenReturn(CompletableFuture.completedFuture(mock(ServerPing.class)));
        KickedFromServerEvent.ServerKickResult fallbackResult =
                KickedFromServerEvent.RedirectPlayer.create(fallback);
        KickedFromServerEvent rejected = new KickedFromServerEvent(
                player,
                survival,
                Component.text("You are not whitelisted"),
                false,
                fallbackResult);

        EventTask rejectionTask = plugin.onKickedFromServer(rejected);

        assertNotNull(rejectionTask);
        execute(rejectionTask).join();
        assertSame(fallbackResult, rejected.getResult());
        verify(scheduler, times(1)).buildTask(any(), org.mockito.ArgumentMatchers.<Runnable>any());
        verify(player, times(2)).sendMessage(any(Component.class));

        when(player.getCurrentServer()).thenReturn(Optional.empty());
        assertNull(plugin.onKickedFromServer(rejected));
    }

    @Test
    void proxyShutdownImmediatelyReleasesPendingReconnectEvent() {
        RegisteredServer fallback = server("lobby", 25566);
        RegisteredServer survival = server("survival", 25567);
        Player player = establishedPlayer(UUID.randomUUID(), survival);
        KickedFromServerEvent kicked = new KickedFromServerEvent(
                player,
                survival,
                Component.text("Server closed"),
                false,
                KickedFromServerEvent.RedirectPlayer.create(fallback));

        CompletableFuture<Void> resumed = execute(plugin.onKickedFromServer(kicked));
        assertTrue(!resumed.isDone());

        plugin.onProxyShutdown(new ProxyShutdownEvent());

        resumed.join();
        assertTrue(resumed.isDone());
    }

    private Player player(UUID playerId) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getUsername()).thenReturn("TestPlayer");
        when(player.isActive()).thenReturn(true);
        when(player.getCurrentServer()).thenReturn(Optional.empty());
        return player;
    }

    private Player establishedPlayer(UUID playerId, RegisteredServer server) {
        Player player = player(playerId);
        ServerConnection connection = mock(ServerConnection.class);
        when(connection.getServer()).thenReturn(server);
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));
        return player;
    }

    private RegisteredServer server(String name, int port) {
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(
                name, new InetSocketAddress("127.0.0.1", port)));
        return server;
    }

    private CompletableFuture<Void> execute(EventTask task) {
        CompletableFuture<Void> resumed = new CompletableFuture<>();
        task.execute(new Continuation() {
            @Override
            public void resume() {
                resumed.complete(null);
            }

            @Override
            public void resumeWithException(Throwable exception) {
                resumed.completeExceptionally(exception);
            }
        });
        return resumed;
    }
}
