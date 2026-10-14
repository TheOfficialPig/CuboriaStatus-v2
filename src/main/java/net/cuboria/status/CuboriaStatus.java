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

@Plugin(id = "cuboriastatus", name = "CuboriaStatus", version = "0.3.0", authors = {"Cuboria"})
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
        List<String> ids = Arrays.stream(config.getProperty("servers", "oneblock,arcade").split(","))
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
        final int w = 1200;
        final int h = 480 + servers.size() * 68;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        Color bg = hex("#06101F"), panel = hex("#08162A"), row = hex("#08182E");
        Color white = hex("#F7FAFF"), muted = hex("#9AAECA"), blue = hex("#31BEF5");
        Color green = hex("#17E69A"), red = hex("#F15C76");

        g.setPaint(new GradientPaint(0, 0, hex("#04101F"), w, h, hex("#07142B")));
        g.fillRect(0, 0, w, h);
        // Soft blue exterior glow and main dashboard frame.
        for (int i = 8; i >= 1; i--) {
            g.setColor(new Color(0, 105, 255, 5));
            g.setStroke(new BasicStroke(i * 2));
            g.drawRoundRect(31, 26, w - 62, h - 52, 27, 27);
        }
        fillRound(g, 36, 30, w - 72, h - 60, 26, bg);
        g.setColor(hex("#0873E4")); g.setStroke(new BasicStroke(1.6f));
        g.drawRoundRect(36, 30, w - 72, h - 60, 26, 26);

        // Dark voxel landscape is decoration only; the live figures are always drawn in Java.
        try (InputStream is = getClass().getResourceAsStream("/header-landscape.png")) {
            if (is != null) {
                BufferedImage landscape = ImageIO.read(is);
                if (landscape != null) g.drawImage(landscape, 580, 34, 570, 141, null);
            }
        } catch (IOException ignored) { }

        // Genuine Cuboria icon copied from user-supplied branding.
        fillRound(g, 66, 58, 98, 98, 19, hex("#091C38"));
        try (InputStream is = getClass().getResourceAsStream("/cuboria-logo.png")) {
            if (is != null) {
                BufferedImage logo = ImageIO.read(is);
                if (logo != null) g.drawImage(logo, 73, 64, 84, 84, null);
            }
        } catch (IOException ignored) { }
        g.setStroke(new BasicStroke(2)); g.setColor(blue);
        g.drawRoundRect(66, 58, 98, 98, 19, 19);
        g.setFont(new Font("SansSerif", Font.BOLD, 37));
        g.setColor(white); g.drawString("CUBORIA", 188, 100);
        g.setColor(hex("#39AFFF")); g.drawString("NETWORK", 392, 100);
        g.setColor(muted); g.setFont(new Font("SansSerif", Font.PLAIN, 20));
        g.drawString(config.getProperty("dashboard.subtitle", "Live Minecraft Server Status"), 190, 131);

        long up = servers.stream().filter(ServerStat::online).count();
        boolean allOnline = !servers.isEmpty() && up == servers.size();
        String overall = allOnline ? "ONLINE" : (up == 0 ? "OFFLINE" : "DEGRADED");
        Color status = allOnline ? green : (up == 0 ? red : hex("#FFB547"));
        fillRound(g, 974, 79, 153, 46, 23, hex("#072721"));
        g.setColor(status); g.setStroke(new BasicStroke(2));
        g.drawRoundRect(974, 79, 153, 46, 23, 23);
        g.fillOval(992, 95, 13, 13);
        g.setFont(new Font("SansSerif", Font.BOLD, 18)); g.drawString(overall, 1016, 108);

        // Summary card
        fillRound(g, 60, 186, 1080, 118, 16, panel);
        thinOutline(g, 60, 186, 1080, 118, 16);
        g.setColor(hex("#243957")); g.drawLine(603, 209, 603, 280);
        fillRound(g, 83, 208, 75, 75, 15, hex("#102B58"));
        people(g, 121, 245, blue);
        g.setFont(new Font("SansSerif", Font.PLAIN, 18)); g.setColor(muted);
        g.drawString("Total Players", 177, 237);
        g.setFont(new Font("SansSerif", Font.BOLD, 34)); g.setColor(white);
        String onlineText = Integer.toString(total);
        g.drawString(onlineText, 176, 274);
        int max = maxPlayers("network.max-players", 10000);
        g.setFont(new Font("SansSerif", Font.BOLD, 29)); g.setColor(hex("#93ADD8"));
        g.drawString("/ " + max, 189 + textWidth(g, onlineText, new Font("SansSerif", Font.BOLD, 34)), 273);
        fillRound(g, 635, 208, 75, 75, 15, hex("#06332F"));
        g.setColor(status); g.setStroke(new BasicStroke(4));
        g.drawOval(658, 230, 30, 30);
        if (allOnline) { g.drawLine(665, 245, 673, 251); g.drawLine(673, 251, 685, 237); }
        g.setColor(muted); g.setFont(new Font("SansSerif", Font.PLAIN, 18));
        g.drawString("Network Status", 729, 237);
        g.setColor(white); g.setFont(new Font("SansSerif", Font.BOLD, 30));
        g.drawString(allOnline ? "Operational" : (up == 0 ? "Unavailable" : "Partial Outage"), 729, 272);

        // Compact two-server section, automatically sized if more backends are configured.
        final int boxY = 320;
        final int boxH = 57 + servers.size() * 68;
        fillRound(g, 60, boxY, 1080, boxH, 16, panel);
        thinOutline(g, 60, boxY, 1080, boxH, 16);
        g.setColor(white); g.setFont(new Font("SansSerif", Font.BOLD, 24));
        g.drawString("Servers", 82, boxY + 35);
        g.setColor(muted); g.setFont(new Font("SansSerif", Font.BOLD, 12));
        right(g, "PLAYERS          PROXY PING", 1115, boxY + 34);
        for (int i = 0; i < servers.size(); i++) {
            ServerStat s = servers.get(i);
            int y = boxY + 47 + i * 68;
            fillRound(g, 78, y, 1044, 60, 12, row);
            thinOutline(g, 78, y, 1044, 60, 12);
            g.setColor(s.online ? green : red);
            g.fillOval(101, y + 23, 14, 14);
            g.setFont(new Font("SansSerif", Font.BOLD, 21)); g.setColor(white);
            String name = s.name();
            if (name.equalsIgnoreCase("OneBlock")) drawBlock(g, 129, y + 15);
            else if (name.equalsIgnoreCase("Arcade")) drawSword(g, 129, y + 12);
            else drawServer(g, 129, y + 15);
            g.drawString(name, 171, y + 37);
            g.setFont(new Font("SansSerif", Font.BOLD, 20));
            g.setColor(white);
            String playerCount = s.players + " / " + maxPlayers("server." + serverIdForName(s.name) + ".max-players", max);
            right(g, playerCount, 907, y + 37);
            g.setColor(s.online ? (s.pingMs < 80 ? green : hex("#F9C75E")) : red);
            right(g, s.online ? (s.pingMs + " ms") : "Offline", 1097, y + 37);
        }
        int footerY = boxY + boxH + 19;
        g.setColor(hex("#2C4263")); g.setStroke(new BasicStroke(1));
        g.drawLine(62, footerY, 1138, footerY);
        g.setFont(new Font("SansSerif", Font.BOLD, 12)); g.setColor(muted);
        g.drawString("SERVER ADDRESS", 112, footerY + 29);
        g.setFont(new Font("SansSerif", Font.BOLD, 21)); g.setColor(white);
        g.drawString(config.getProperty("network.address", "play.cuborianetwork.net"), 112, footerY + 54);
        g.setColor(blue); g.setStroke(new BasicStroke(4));
        g.drawRoundRect(79, footerY + 29, 23, 17, 7, 7);
        fillRound(g, 929, footerY + 22, 194, 37, 18, hex("#0D2140"));
        g.setColor(hex("#2574C8")); g.setStroke(new BasicStroke(1));
        g.drawRoundRect(929, footerY + 22, 194, 37, 18, 18);
        g.setFont(new Font("SansSerif", Font.PLAIN, 15)); g.setColor(white);
        g.drawString("Updates every " + Math.max(15, Integer.parseInt(config.getProperty("update.seconds", "30"))) + "s", 946, footerY + 46);
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(img, "png", out)) throw new IOException("No PNG writer available");
            return out.toByteArray();
        } catch (IOException ex) { throw new CompletionException(ex); }
    }

    private int maxPlayers(String key, int fallback) {
        try { return Math.max(1, Integer.parseInt(config.getProperty(key, String.valueOf(fallback)))); }
        catch (NumberFormatException ex) { return fallback; }
    }

    private String serverIdForName(String display) {
        for (String id : config.getProperty("servers", "oneblock,arcade").split(",")) {
            id = id.trim();
            if (config.getProperty("server." + id, id).equals(display)) return id;
        }
        return display.toLowerCase(java.util.Locale.ROOT);
    }

    private static Color hex(String h) { return Color.decode(h); }
    private static void fillRound(Graphics2D g, int x, int y, int w, int h, int r, Color color) {
        g.setColor(color); g.fillRoundRect(x, y, w, h, r, r);
    }
    private static void thinOutline(Graphics2D g, int x, int y, int w, int h, int r) {
        g.setColor(new Color(30, 75, 139, 160)); g.setStroke(new BasicStroke(1));
        g.drawRoundRect(x, y, w, h, r, r);
    }
    private static void right(Graphics2D g, String text, int x, int baseline) {
        g.drawString(text, x - g.getFontMetrics().stringWidth(text), baseline);
    }
    private static int textWidth(Graphics2D g, String text, Font font) {
        return g.getFontMetrics(font).stringWidth(text);
    }
    private static void people(Graphics2D g, int x, int y, Color color) {
        g.setColor(color);
        g.fillOval(x - 8, y - 20, 16, 16); g.fillOval(x - 28, y - 15, 12, 12); g.fillOval(x + 16, y - 15, 12, 12);
        g.fillRoundRect(x - 13, y - 2, 26, 22, 8, 8);
        g.fillRoundRect(x - 32, y + 1, 16, 17, 6, 6); g.fillRoundRect(x + 16, y + 1, 16, 17, 6, 6);
    }
    private static void drawBlock(Graphics2D g, int x, int y) {
        g.setColor(hex("#7AC850")); g.fillRect(x, y, 30, 12);
        g.setColor(hex("#8A5E36")); g.fillRect(x, y + 12, 30, 22);
        g.setColor(hex("#6B4629")); g.fillRect(x + 18, y + 12, 12, 22);
        g.setColor(hex("#A7E06C")); g.fillRect(x + 4, y + 3, 10, 4);
    }
    private static void drawSword(Graphics2D g, int x, int y) {
        g.setColor(hex("#5FE8FA")); g.fillRect(x + 17, y, 9, 22);
        g.setColor(hex("#1CA5DC")); g.fillRect(x + 10, y + 20, 22, 7);
        g.setColor(hex("#B4CDFB")); g.fillRect(x + 19, y + 26, 6, 11);
        g.setColor(hex("#26669E")); g.fillRect(x + 18, y + 2, 3, 17);
    }
    private static void drawServer(Graphics2D g, int x, int y) {
        g.setColor(hex("#31BEF5")); g.fillRoundRect(x, y, 30, 26, 6, 6);
        g.setColor(hex("#0B1B31")); g.fillRect(x + 4, y + 7, 22, 4);
        g.fillRect(x + 4, y + 17, 22, 4);
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
