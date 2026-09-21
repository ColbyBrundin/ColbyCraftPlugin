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
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Hosts a tiny resource pack (bundled under {@code src/main/resources/
 * resourcepack}) that defines exactly one custom sound - the Enderman
 * dodge-teleport clip - and pushes it to every joining player via Paper's
 * per-player resource pack API. It's purely additive (it only ever defines
 * a new namespaced sound event), so it never overrides anything in a
 * player's own installed packs and is safe to layer alongside them.
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

    private static final String RESOURCE_PACK_PREFIX = "resourcepack/";
    private static final int DEFAULT_HTTP_PORT = 25566;
    private static final String HTTP_PATH = "/resourcepack.zip";

    private final UUID packId = UUID.randomUUID();
    private HttpServer httpServer;
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

            httpServer = HttpServer.create(new InetSocketAddress(packPort), 0);
            httpServer.createContext(HTTP_PATH, this::servePack);
            httpServer.setExecutor(null);
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
                "Adds a custom Enderman dodge-teleport sound (optional)", false);
    }

    // Diagnostic: logs whether the client actually accepted/loaded this
    // specific pack (matched by packId, since a player may have other
    // server-pushed packs too) - the client gives no other visible feedback
    // when a custom sound silently fails to register.
    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        if (!event.getID().equals(packId)) return;
        logger.info("Enderman dodge sound pack status for " + event.getPlayer().getName() + ": " + event.getStatus());
    }
}
