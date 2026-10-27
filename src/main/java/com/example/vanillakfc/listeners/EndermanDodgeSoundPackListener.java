package com.example.vanillakfc.listeners;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Hosts a tiny resource pack (bundled under {@code src/main/resources/
 * resourcepack}) and pushes it to every joining player via Paper's
 * per-player resource pack API. It carries three custom sounds - the Enderman
 * dodge-teleport clip, the Enderman aggro clip and the End arrival clip, all
 * new namespaced sound events that override nothing - and one vanilla override: the shield's item model, which lowers the shield
 * in first person while it's held but not raised (the blocking pose lives in
 * a separate model that is left vanilla).
 *
 * <p>The pack is served by an embedded HTTP server (bound to all interfaces)
 * on the port from the {@code resource-pack-port} config option (default
 * {@value #DEFAULT_HTTP_PORT}), but the URL handed to each client uses the
 * {@code resource-pack-host} option, which defaults to {@code 127.0.0.1} -
 * only reachable from a client on the same machine the server runs on. Set
 * that option to a real reachable host/IP (and forward the configured port)
 * to serve remote players.
 */
public final class EndermanDodgeSoundPackListener implements Listener {

    public static final String CUSTOM_TELEPORT_SOUND_KEY = "vanillakfc:enderman_dodge_teleport";
    public static final String AGGRO_SOUND_KEY = "vanillakfc:enderman_aggro";
    public static final String END_DRAGON_ALIVE_SOUND_KEY = "vanillakfc:end_dragon_alive";

    private static final String RESOURCE_PACK_PREFIX = "resourcepack/";
    private static final int DEFAULT_HTTP_PORT = 25566;
    private static final String HTTP_PATH = "/resourcepack.zip";
    // Downloads served at once; more just queue, and the timeouts below keep a
    // stalled one from holding its thread for long.
    private static final int HTTP_THREADS = 8;
    private static final long REQUEST_TIMEOUT_SECONDS = 15;
    // The whole ~150 KB download, generous for a slow connection.
    private static final long RESPONSE_TIMEOUT_SECONDS = 120;

    private final UUID packId = UUID.randomUUID();
    private HttpServer httpServer;
    private ExecutorService httpExecutor;
    private byte[] packBytes;
    private byte[] packHash;
    private Logger logger;
    private String packHost;
    private int packPort;

    public void start(Plugin plugin, File pluginJarFile) {
        logger = plugin.getLogger();
        plugin.saveDefaultConfig();
        packHost = plugin.getConfig().getString("resource-pack-host", "127.0.0.1");
        packPort = plugin.getConfig().getInt("resource-pack-port", DEFAULT_HTTP_PORT);
        try {
            packBytes = buildPackZip(pluginJarFile);
            packHash = MessageDigest.getInstance("SHA-1").digest(packBytes);

            // The JDK server waits forever by default on a client that stops
            // sending its request or stops reading the response, and with no
            // executor every download runs on its one dispatcher thread - so a
            // single stalled connection (a dropped player, a port scanner)
            // silently stalled the pack for everyone until a restart. These
            // timeouts make it close such connections; they are JVM-wide and
            // read once, when the first JDK HttpServer is created, so a value
            // the admin already set with -D is left alone.
            setIfAbsent("sun.net.httpserver.maxReqTime", REQUEST_TIMEOUT_SECONDS);
            setIfAbsent("sun.net.httpserver.maxRspTime", RESPONSE_TIMEOUT_SECONDS);

            httpServer = HttpServer.create(new InetSocketAddress(packPort), 0);
            httpServer.createContext(HTTP_PATH, this::servePack);
            httpExecutor = Executors.newFixedThreadPool(HTTP_THREADS, runnable -> {
                Thread thread = new Thread(runnable, "VanillaKFC-ResourcePack-HTTP");
                thread.setDaemon(true);
                return thread;
            });
            httpServer.setExecutor(httpExecutor);
            httpServer.start();
            logger.info("Enderman dodge sound resource pack server started - serving " + buildPackUrl()
                    + " (listening on all interfaces, port " + packPort + ")");
        } catch (IOException | NoSuchAlgorithmException e) {
            logger.warning("Failed to start the Enderman dodge sound resource pack server: " + e.getMessage());
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
    }

    private static void setIfAbsent(String property, long seconds) {
        if (System.getProperty(property) == null) {
            System.setProperty(property, Long.toString(seconds));
        }
    }

    private void servePack(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/zip");
        exchange.sendResponseHeaders(200, packBytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(packBytes);
        }
    }

    private static byte[] buildPackZip(File pluginJarFile) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (JarFile jar = new JarFile(pluginJarFile);
             ZipOutputStream zip = new ZipOutputStream(buffer)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(RESOURCE_PACK_PREFIX)) continue;

                String relativePath = entry.getName().substring(RESOURCE_PACK_PREFIX.length());
                if (relativePath.isEmpty()) continue;

                zip.putNextEntry(new ZipEntry(relativePath));
                try (InputStream in = jar.getInputStream(entry)) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private String buildPackUrl() {
        return "http://" + packHost + ":" + packPort + HTTP_PATH;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (httpServer == null) return;
        Player player = event.getPlayer();
        player.addResourcePack(packId, buildPackUrl(), packHash,
                "Adds custom Enderman and End sounds and a lower first-person shield (optional)", false);
    }

    // Logs only when this pack (matched by packId, since a player may have
    // other server-pushed packs too) doesn't end up loaded - the client gives
    // no visible sign of it, so this is the only way to know a player isn't
    // getting the sound or the shield. Accepted, downloaded and loaded go
    // unlogged.
    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        if (!event.getID().equals(packId)) return;
        switch (event.getStatus()) {
            case ACCEPTED, DOWNLOADED, SUCCESSFULLY_LOADED -> { }
            default -> logger.info("Resource pack not loaded for " + event.getPlayer().getName() + ": " + event.getStatus());
        }
    }
}
