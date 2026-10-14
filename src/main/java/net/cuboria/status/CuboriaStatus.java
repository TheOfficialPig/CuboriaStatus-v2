package net.cuboria.status;

import com.google.inject.Inject;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Plugin(id = "cuboriastatus", name = "CuboriaStatus", version = "0.2.1", authors = {"Cuboria"})
public final class CuboriaStatus {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory = Path.of("plugins", "cuboriastatus");
    private final Properties config = new Properties();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Gson gson = new Gson();
    private final AtomicBoolean updating = new AtomicBoolean(false);
    private ScheduledTask task;

    @Inject
    public CuboriaStatus(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @com.velocitypowered.api.event.Subscribe
    public void onInit(com.velocitypowered.api.event.proxy.ProxyInitializeEvent event) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve("config.properties");
            if (Files.notExists(file)) {
                try (InputStream in = getClass().getResourceAsStream("/config.properties")) {
                    if (in == null) throw new IOException("Bundled default config missing");
                    Files.copy(in, file);
                }
            }
            try (InputStream in = Files.newInputStream(file)) { config.load(in); }
            int interval = Math.max(15, Integer.parseInt(config.getProperty("update.seconds", "30")));
            task = proxy.getScheduler().buildTask(this, this::tick).delay(5, TimeUnit.SECONDS)
                    .repeat(interval, TimeUnit.SECONDS).schedule();
            logger.info("CuboriaStatus started. Edit plugins/cuboriastatus/config.properties for Discord settings.");
        } catch (Exception ex) {
            logger.error("Unable to initialize CuboriaStatus", ex);
        }
    }

    @com.velocitypowered.api.event.Subscribe
    public void onShutdown(com.velocitypowered.api.event.proxy.ProxyShutdownEvent event) {
        if (task != null) task.cancel();
    }

    private record ServerStat(String name, int players, boolean online, long pingMs) {}

    private void tick() {
        if (!updating.compareAndSet(false, true)) return;
        String token = config.getProperty("discord.token", "").trim();
        String channel = config.getProperty("discord.channel-id", "").trim();
        if (token.isBlank() || token.startsWith("PASTE_") || !channel.matches("[0-9]{15,22}")) {
            logger.warn("CuboriaStatus: configure a valid bot token and channel ID first.");
            updating.set(false);
            return;
        }
        List<String> ids = Arrays.stream(config.getProperty("servers", "hub,oneblock,arcade").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<CompletableFuture<ServerStat>> checks = new ArrayList<>();
        for (String id : ids) {
            String title = config.getProperty("server." + id, id);
            var registered = proxy.getServer(id);
            if (registered.isEmpty()) {
                checks.add(CompletableFuture.completedFuture(new ServerStat(title, 0, false, -1)));
                continue;
            }
            var backend = registered.get();
            int count = backend.getPlayersConnected().size();
            // Ping is asynchronous; a timeout produces an offline indicator.
            long start = System.nanoTime();
            checks.add(backend.ping().orTimeout(5, TimeUnit.SECONDS)
                    .handle((response, error) -> new ServerStat(title, count,
                            error == null && response != null,
                            error == null && response != null
                                    ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) : -1)));
        }
        CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> checks.stream().map(CompletableFuture::join).toList())
                .thenApply(stats -> render(stats, proxy.getPlayerCount()))
                .thenCompose(png -> publish(token, channel, png))
                .whenComplete((ignored, err) -> {
                    if (err != null) logger.error("CuboriaStatus dashboard update failed", err);
                    updating.set(false);
                });
    }

    private byte[] render(List<ServerStat> servers, int total) {
        final int w = 1000, h = 355 + servers.size() * 83;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Color bg = new Color(11, 16, 25), card = new Color(21, 30, 44);
        Color white = new Color(243, 247, 252), muted = new Color(144, 164, 186);
        Color blue = new Color(55, 191, 227), green = new Color(74, 222, 128), red = new Color(248, 113, 113);
        g.setColor(bg); g.fillRect(0, 0, w, h);
        g.setColor(blue); g.fillRoundRect(37, 35, 9, 61, 5, 5);
        g.setColor(white); g.setFont(new Font("SansSerif", Font.BOLD, 29));
        g.drawString(config.getProperty("dashboard.title", "CUBORIA NETWORK"), 66, 61);
        g.setFont(new Font("SansSerif", Font.PLAIN, 16)); g.setColor(muted);
        g.drawString(config.getProperty("dashboard.subtitle", "Live Minecraft Server Status"), 67, 89);
        boolean allOnline = servers.stream().allMatch(ServerStat::online);
        String networkStatus = allOnline ? "ALL SYSTEMS ONLINE" : "CHECK SERVER STATUS";
        g.setFont(new Font("SansSerif", Font.BOLD, 16)); g.setColor(allOnline ? green : red);
        right(g, networkStatus, w - 43, 68);
        line(g, 115, w);

        g.setFont(new Font("SansSerif", Font.BOLD, 14)); g.setColor(muted);
        g.drawString("TOTAL PLAYERS", 45, 159);
        g.drawString("SERVERS ONLINE", 530, 159);
        g.setFont(new Font("SansSerif", Font.BOLD, 42)); g.setColor(white);
        g.drawString(Integer.toString(total), 44, 210);
        long online = servers.stream().filter(ServerStat::online).count();
        g.drawString(online + " / " + servers.size(), 529, 210);
        line(g, 240, w);
        g.setFont(new Font("SansSerif", Font.BOLD, 17)); g.setColor(muted);
        g.drawString("SERVERS", 45, 277);
        right(g, "PLAYERS    /    PROXY PING", w - 45, 277);

        int y = 295;
        for (ServerStat s : servers) {
            g.setColor(card); g.fillRoundRect(38, y, w - 76, 72, 16, 16);
            g.setColor(s.online ? green : red); g.fillOval(59, y + 26, 14, 14);
            g.setColor(white); g.setFont(new Font("SansSerif", Font.BOLD, 20));
            g.drawString(s.name, 89, y + 31);
            g.setColor(s.online ? green : red); g.setFont(new Font("SansSerif", Font.PLAIN, 13));
            g.drawString(s.online ? "Online" : "Unreachable", 89, y + 53);
            g.setColor(white); g.setFont(new Font("SansSerif", Font.BOLD, 19));
            right(g, s.players + " players", w - 294, y + 31);
            g.setColor(s.online ? (s.pingMs < 80 ? green : new Color(251, 191, 36)) : red);
            right(g, s.online ? s.pingMs + " ms" : "Offline", w - 63, y + 37);
            y += 83;
        }
        line(g, h - 51, w);
        g.setFont(new Font("SansSerif", Font.BOLD, 16)); g.setColor(white);
        g.drawString(config.getProperty("network.address", "play.cuborianetwork.net"), 45, h - 20);
        g.setFont(new Font("SansSerif", Font.PLAIN, 13)); g.setColor(muted);
        right(g, "Updated " + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                + " • proxy-to-backend latency", w - 45, h - 22);
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(img, "png", out)) throw new IOException("No PNG image writer available");
            return out.toByteArray();
        } catch (IOException e) { throw new CompletionException(e); }
    }

    private static void right(Graphics2D g, String text, int right, int baseline) {
        g.drawString(text, right - g.getFontMetrics().stringWidth(text), baseline);
    }

    private static void line(Graphics2D g, int y, int w) {
        g.setColor(new Color(39, 50, 68)); g.drawLine(38, y, w - 38, y);
    }

    private CompletableFuture<Void> publish(String token, String channel, byte[] png) {
        Path idFile = directory.resolve("message-id.txt");
        String id = "";
        try { if (Files.exists(idFile)) id = Files.readString(idFile).trim(); }
        catch (IOException e) { logger.warn("Could not read message ID", e); }
        final boolean editing = id.matches("[0-9]{15,22}");
        String endpoint = "https://discord.com/api/v10/channels/" + channel + "/messages" + (editing ? "/" + id : "");
        JsonObject payload = new JsonObject();
        payload.addProperty("content", "");
        JsonArray attachments = new JsonArray();
        JsonObject attachment = new JsonObject(); attachment.addProperty("id", "0");
        attachment.addProperty("filename", "cuboria-status.png"); attachments.add(attachment);
        payload.add("attachments", attachments);
        String boundary = "cuboria" + UUID.randomUUID().toString().replace("-", "");
        byte[] body;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n" + gson.toJson(payload) + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"cuboria-status.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(png); out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            body = out.toByteArray();
        } catch (IOException e) { return CompletableFuture.failedFuture(e); }
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bot " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method(editing ? "PATCH" : "POST", HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 404 && editing) {
                        // Message was deleted or channel changed. A later scheduled tick will create a new one.
                        try { Files.deleteIfExists(idFile); }
                        catch (IOException ex) { logger.warn("Unable to reset deleted Discord message ID", ex); }
                        logger.warn("Discord status message not found; will recreate on next update.");
                        return;
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        String details = response.body();
                        if (details.length() > 500) details = details.substring(0, 500);
                        throw new CompletionException(new IOException("Discord HTTP " + response.statusCode() + ": " + details));
                    }
                    if (!editing) {
                        String newId = JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsString();
                        try { Files.writeString(idFile, newId); }
                        catch (IOException e) { logger.error("Failed to save Discord message ID; duplicates possible on restart", e); }
                    }
                });
    }
}
