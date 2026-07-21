package dev.velocitystay;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

@Plugin(
        id = "velocity-stay-server",
        name = "Velocity Stay Server",
        version = "1.0.0",
        description = "Restores last servers and keeps players connected during backend restarts")
public final class VelocityStayServer {
    private static final Duration DATA_SAVE_DELAY = Duration.ofSeconds(1);
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final LastServerStore lastServerStore;
    private final ConcurrentMap<UUID, String> initialRestores = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, RetrySession> retrySessions = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<Void>> pendingEventTasks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean saveScheduled = new AtomicBoolean();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    private volatile PluginConfig config = PluginConfig.defaults();

    @Inject
    public VelocityStayServer(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.lastServerStore = new LastServerStore(dataDirectory.resolve(LastServerStore.FILE_NAME));
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            config = PluginConfig.load(dataDirectory);
        } catch (IOException exception) {
            logger.error("Could not load config.properties; using built-in defaults", exception);
        }

        try {
            lastServerStore.load();
            logger.info("Loaded {} last-server entries", lastServerStore.size());
        } catch (IOException exception) {
            logger.error("Could not load last-server data", exception);
        }
    }

    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        if (!config.restoreLastServer() || shuttingDown.get()) {
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();
        Optional<RegisteredServer> previousServer = lastServerStore
                .get(playerId)
                .flatMap(proxy::getServer);
        if (previousServer.isEmpty()) {
            return;
        }

        RegisteredServer server = previousServer.get();
        initialRestores.put(playerId, server.getServerInfo().getName());
        event.setInitialServer(server);
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        String serverName = event.getServer().getServerInfo().getName();
        initialRestores.remove(playerId);

        RetrySession session = retrySessions.remove(playerId);
        if (session != null && session.matches(serverName)) {
            sendConfiguredMessage(event.getPlayer(), config.reconnectedMessage(), session, session.attempts());
            logger.info(
                    "Reconnected player {} to {} after {} attempt(s)",
                    event.getPlayer().getUsername(),
                    serverName,
                    session.attempts());
        }

        if (lastServerStore.remember(playerId, serverName)) {
            scheduleDataSave();
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        initialRestores.remove(playerId);
        retrySessions.remove(playerId);
    }

    @Subscribe
    public EventTask onKickedFromServer(KickedFromServerEvent event) {
        if (!config.retryEnabled() || shuttingDown.get()) {
            return null;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String targetName = event.getServer().getServerInfo().getName();

        String initialTarget = initialRestores.get(playerId);
        if (initialTarget != null && namesEqual(initialTarget, targetName)) {
            initialRestores.remove(playerId, initialTarget);
            return null;
        }

        RetrySession existing = retrySessions.get(playerId);
        if (existing != null) {
            if (existing.matches(targetName)) {
                return continueRetry(event, existing);
            }
            retrySessions.remove(playerId, existing);
            return null;
        }

        if (!wasEstablishedConnection(event) || event.kickedDuringServerConnect()) {
            return null;
        }

        String reason = event.getServerKickReason()
                .map(PLAIN_TEXT::serialize)
                .orElse("")
                .trim();
        if (reason.isEmpty() || matchesOutageKeyword(reason)) {
            return beginRetry(event);
        }

        return checkServerAvailability(event);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        shuttingDown.set(true);
        initialRestores.clear();
        retrySessions.clear();
        pendingEventTasks.forEach(completion -> completion.complete(null));
        saveDataNow();
    }

    private EventTask checkServerAvailability(KickedFromServerEvent event) {
        CompletableFuture<Void> completion = newEventCompletion();
        try {
            event.getServer()
                    .ping()
                    .orTimeout(config.outageCheckTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .whenComplete((ping, error) -> {
                        if (error == null) {
                            completion.complete(null);
                        } else {
                            beginRetry(event, completion);
                        }
                    });
        } catch (RuntimeException exception) {
            beginRetry(event, completion);
        }
        return EventTask.resumeWhenComplete(completion);
    }

    private EventTask continueRetry(KickedFromServerEvent event, RetrySession session) {
        String reason = event.getServerKickReason()
                .map(PLAIN_TEXT::serialize)
                .orElse("")
                .trim();
        if (reason.isEmpty() || matchesOutageKeyword(reason)) {
            return scheduleRetry(event, session);
        }

        CompletableFuture<Void> completion = newEventCompletion();
        try {
            event.getServer()
                    .ping()
                    .orTimeout(config.outageCheckTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .whenComplete((ping, error) -> {
                        if (error == null) {
                            stopRetrying(event.getPlayer(), session, completion);
                        } else {
                            scheduleRetry(event, session, completion);
                        }
                    });
        } catch (RuntimeException exception) {
            scheduleRetry(event, session, completion);
        }
        return EventTask.resumeWhenComplete(completion);
    }

    private EventTask beginRetry(KickedFromServerEvent event) {
        CompletableFuture<Void> completion = newEventCompletion();
        beginRetry(event, completion);
        return EventTask.resumeWhenComplete(completion);
    }

    private void beginRetry(KickedFromServerEvent event, CompletableFuture<Void> completion) {
        Player player = event.getPlayer();
        if (!player.isActive() || shuttingDown.get()) {
            completion.complete(null);
            return;
        }

        String serverName = event.getServer().getServerInfo().getName();
        RetrySession candidate = new RetrySession(serverName);
        RetrySession session = retrySessions.putIfAbsent(player.getUniqueId(), candidate);
        if (session == null) {
            session = candidate;
            sendConfiguredMessage(player, config.waitingMessage(), session, 1);
            logger.info(
                    "Keeping player {} connected while {} is unavailable",
                    player.getUsername(),
                    serverName);
        } else if (!session.matches(serverName)) {
            completion.complete(null);
            return;
        }

        scheduleRetry(event, session, completion);
    }

    private EventTask scheduleRetry(KickedFromServerEvent event, RetrySession session) {
        CompletableFuture<Void> completion = newEventCompletion();
        scheduleRetry(event, session, completion);
        return EventTask.resumeWhenComplete(completion);
    }

    private void scheduleRetry(
            KickedFromServerEvent event,
            RetrySession session,
            CompletableFuture<Void> completion) {
        int maximum = config.maxRetryAttempts();
        if (maximum > 0 && session.attempts() >= maximum) {
            stopRetrying(event.getPlayer(), session, completion);
            return;
        }
        session.nextAttempt();

        try {
            proxy.getScheduler()
                    .buildTask(this, () -> finishRetryDelay(event, session, completion))
                    .delay(Duration.ofSeconds(config.retryDelaySeconds()))
                    .schedule();
        } catch (RuntimeException exception) {
            retrySessions.remove(event.getPlayer().getUniqueId(), session);
            logger.error("Could not schedule a backend reconnect attempt", exception);
            completion.complete(null);
        }
    }

    private void finishRetryDelay(
            KickedFromServerEvent event,
            RetrySession session,
            CompletableFuture<Void> completion) {
        try {
            Player player = event.getPlayer();
            UUID playerId = player.getUniqueId();
            if (shuttingDown.get() || !player.isActive() || retrySessions.get(playerId) != session) {
                return;
            }

            Optional<RegisteredServer> currentRegistration = proxy.getServer(session.serverName());
            if (currentRegistration.isEmpty()) {
                retrySessions.remove(playerId, session);
                sendConfiguredMessage(player, config.gaveUpMessage(), session, session.attempts());
                return;
            }

            event.setResult(KickedFromServerEvent.RedirectPlayer.create(
                    currentRegistration.get(), Component.empty()));
        } finally {
            completion.complete(null);
        }
    }

    private void stopRetrying(Player player, RetrySession session, CompletableFuture<Void> completion) {
        if (retrySessions.remove(player.getUniqueId(), session) && player.isActive()) {
            sendConfiguredMessage(player, config.gaveUpMessage(), session, session.attempts());
            logger.warn(
                    "Stopped reconnecting player {} to {} after {} attempt(s)",
                    player.getUsername(),
                    session.serverName(),
                    session.attempts());
        }
        completion.complete(null);
    }

    private CompletableFuture<Void> newEventCompletion() {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        pendingEventTasks.add(completion);
        completion.whenComplete((ignored, error) -> pendingEventTasks.remove(completion));
        if (shuttingDown.get()) {
            completion.complete(null);
        }
        return completion;
    }

    private boolean wasEstablishedConnection(KickedFromServerEvent event) {
        return event.getPlayer()
                .getCurrentServer()
                .map(connection -> connection.getServer().getServerInfo().getName())
                .map(name -> namesEqual(name, event.getServer().getServerInfo().getName()))
                .orElse(false);
    }

    private boolean matchesOutageKeyword(String reason) {
        String lowerReason = reason.toLowerCase(Locale.ROOT);
        for (String keyword : config.retryReasonKeywords()) {
            if ("*".equals(keyword) || lowerReason.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private void sendConfiguredMessage(Player player, String template, RetrySession session, int attempt) {
        if (template == null || template.isBlank()) {
            return;
        }
        String rendered = template
                .replace("{server}", session.serverName())
                .replace("{seconds}", Integer.toString(config.retryDelaySeconds()))
                .replace("{attempt}", Integer.toString(attempt));
        player.sendMessage(Component.text(rendered));
    }

    private void scheduleDataSave() {
        if (!saveScheduled.compareAndSet(false, true) || shuttingDown.get()) {
            return;
        }
        try {
            proxy.getScheduler()
                    .buildTask(this, () -> {
                        saveScheduled.set(false);
                        saveDataNow();
                    })
                    .delay(DATA_SAVE_DELAY)
                    .schedule();
        } catch (RuntimeException exception) {
            saveScheduled.set(false);
            logger.error("Could not schedule last-server data save", exception);
        }
    }

    private void saveDataNow() {
        try {
            lastServerStore.save();
        } catch (IOException exception) {
            logger.error("Could not save last-server data", exception);
        }
    }

    private static boolean namesEqual(String first, String second) {
        return first.equalsIgnoreCase(second);
    }

    private static final class RetrySession {
        private final String serverName;
        private final AtomicInteger attempts = new AtomicInteger();

        private RetrySession(String serverName) {
            this.serverName = serverName;
        }

        private String serverName() {
            return serverName;
        }

        private int nextAttempt() {
            return attempts.incrementAndGet();
        }

        private int attempts() {
            return attempts.get();
        }

        private boolean matches(String otherServerName) {
            return namesEqual(serverName, otherServerName);
        }
    }
}
