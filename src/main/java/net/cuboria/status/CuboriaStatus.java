package net.cuboria.status;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Plugin(id = "cuboriastatus", name = "CuboriaStatus", version = "0.5.0", authors = {"Cuboria"})
public final class CuboriaStatus {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory = Path.of("plugins", "cuboriastatus");
    private final Properties config = new Properties();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Gson gson = new Gson();
    private final AtomicBoolean updating = new AtomicBoolean(false);
    private final Map<String, BufferedImage> assetCache = new HashMap<>();
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
            try (InputStream in = Files.newInputStream(file)) {
                config.load(in);
            }
            int interval = Math.max(15, Integer.parseInt(config.getProperty("update.seconds", "30")));
            task = proxy.getScheduler().buildTask(this, this::tick)
                    .delay(5, TimeUnit.SECONDS)
                    .repeat(interval, TimeUnit.SECONDS)
                    .schedule();
            logger.info("CuboriaStatus v0.5.0 started with asset-based dashboard renderer.");
        } catch (Exception ex) {
            logger.error("Unable to initialize CuboriaStatus", ex);
        }
    }

    @com.velocitypowered.api.event.Subscribe
    public void onShutdown(com.velocitypowered.api.event.proxy.ProxyShutdownEvent event) {
        if (task != null) task.cancel();
    }

    private record ServerStat(String id, String name, int players, boolean online, long pingMs) {}

    private void tick() {
        if (!updating.compareAndSet(false, true)) return;

        String token = config.getProperty("discord.token", "").trim();
        String channel = config.getProperty("discord.channel-id", "").trim();
        if (token.isBlank() || token.startsWith("PASTE_") || !channel.matches("[0-9]{15,22}")) {
            logger.warn("CuboriaStatus: configure a valid Discord bot token and channel ID first.");
            updating.set(false);
            return;
        }

        List<String> ids = Arrays.stream(config.getProperty("servers", "oneblock,arcade").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();

        List<CompletableFuture<ServerStat>> checks = new ArrayList<>();
        for (String id : ids) {
            String title = config.getProperty("server." + id, prettify(id));
            var registered = proxy.getServer(id);
            if (registered.isEmpty()) {
                checks.add(CompletableFuture.completedFuture(new ServerStat(id, title, 0, false, -1)));
                continue;
            }
            var backend = registered.get();
            int count = backend.getPlayersConnected().size();
            long start = System.nanoTime();
            checks.add(backend.ping().orTimeout(5, TimeUnit.SECONDS)
                    .handle((response, error) -> new ServerStat(
                            id,
                            title,
                            count,
                            error == null && response != null,
                            error == null && response != null ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) : -1
                    )));
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

    private byte[] render(List<ServerStat> servers, int totalPlayers) {
        final int width = 1600;
        final int rowHeight = 88;
        final int rows = Math.max(1, servers.size());
        final int height = 720 + (rows * rowHeight);

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

        Color bg = hex("#07111E");
        Color overlay = new Color(3, 9, 18, 160);
        Color white = hex("#F4F8FF");
        Color ice = hex("#D3E9FF");
        Color blue = hex("#39C2FF");
        Color green = hex("#23F0A0");
        Color yellow = hex("#FFD056");
        Color red = hex("#FF6B7D");
        Color shadow = new Color(5, 12, 28, 220);

        g.setColor(bg);
        g.fillRect(0, 0, width, height);

        BufferedImage landscape = asset("/bg-landscape.png");
        if (landscape != null) {
            drawCover(g, landscape, 0, 0, width, height, 0.42f);
        }
        g.setColor(overlay);
        g.fillRect(0, 0, width, height);

        // Soft outer frame
        for (int i = 0; i < 7; i++) {
            g.setColor(new Color(0, 175, 255, 16 - i * 2));
            g.drawRoundRect(24 + i, 20 + i, width - 48 - i * 2, height - 40 - i * 2, 34, 34);
        }
        g.setColor(new Color(0, 120, 255, 90));
        g.drawRoundRect(32, 28, width - 64, height - 56, 30, 30);

        // Header assets
        BufferedImage badge = asset("/title-badge.png");
        BufferedImage onlineSign = asset("/online-sign.png");
        BufferedImage totalPanel = asset("/panel-total.png");
        BufferedImage networkPanel = asset("/panel-network.png");
        BufferedImage headerServers = asset("/header-servers.png");
        BufferedImage serverRow = asset("/row-server.png");
        BufferedImage footerPanels = asset("/footer-panels.png");
        BufferedImage leftChar = asset("/char-left.png");
        BufferedImage rightChar = asset("/char-right.png");

        int headerTop = 34;
        drawImage(g, badge, 170, headerTop + 4, 860, 286, 1f);
        drawImage(g, onlineSign, 1140, headerTop + 28, 330, 165, 1f);
        drawImage(g, leftChar, 56, 62, 180, 180, 1f);
        drawImage(g, rightChar, 1300, 86, 190, 142, 1f);

        long onlineCount = servers.stream().filter(ServerStat::online).count();
        boolean allOnline = !servers.isEmpty() && onlineCount == servers.size();
        String overallText = allOnline ? "OPERATIONAL" : (onlineCount == 0 ? "OFFLINE" : "DEGRADED");
        Color overallColor = allOnline ? green : (onlineCount == 0 ? red : yellow);

        // Summary panels
        int summaryY = 248;
        drawImage(g, totalPanel, 74, summaryY, 660, 220, 1f);
        drawImage(g, networkPanel, 846, summaryY, 680, 220, 1f);

        int networkMax = maxPlayers("network.max-players", 10000);
        // Total live number
        drawPixelText(g, String.valueOf(totalPlayers), 255, summaryY + 112, 8, white, shadow, 2);
        int totalWidth = pixelTextWidth(String.valueOf(totalPlayers), 8, 2);
        drawPixelText(g, "/", 255 + totalWidth + 18, summaryY + 118, 6, ice, shadow, 1);
        drawPixelText(g, String.valueOf(networkMax), 255 + totalWidth + 58, summaryY + 118, 6, ice, shadow, 1);

        // Network status live text
        int statusScale = overallText.length() > 10 ? 5 : 6;
        drawPixelText(g, overallText, 1035, summaryY + 116, statusScale, overallColor, shadow, 2);

        // Servers header
        int listHeaderY = summaryY + 232;
        drawImage(g, headerServers, 86, listHeaderY, 1425, 102, 1f);

        // Server rows
        int rowsY = listHeaderY + 78;
        for (int i = 0; i < servers.size(); i++) {
            ServerStat stat = servers.get(i);
            int y = rowsY + i * rowHeight;
            drawImage(g, serverRow, 90, y, 1410, 92, 1f);

            BufferedImage icon = switch (stat.id().toLowerCase(Locale.ROOT)) {
                case "oneblock" -> asset("/oneblock-icon.png");
                case "arcade" -> asset("/arcade-icon.png");
                default -> null;
            };
            if (icon != null) {
                drawImage(g, icon, 200, y + 23, 46, 46, 1f);
            }

            Color nameColor = stat.online() ? white : hex("#FFD7DE");
            drawPixelText(g, stat.name(), 272, y + 28, 5, nameColor, shadow, 1);

            String playersText = stat.players() + " / " + maxPlayers("server." + stat.id() + ".max-players", networkMax);
            drawPixelText(g, playersText, 982, y + 30, 4, white, shadow, 1);

            String pingText = stat.online() ? stat.pingMs() + " ms" : "Offline";
            Color pingColor = !stat.online() ? red : (stat.pingMs() <= 80 ? green : yellow);
            int pingScale = stat.online() ? 4 : 4;
            int pingWidth = pixelTextWidth(pingText, pingScale, 1);
            drawPixelText(g, pingText, 1430 - pingWidth, y + 30, pingScale, pingColor, shadow, 1);
        }

        // Footer
        int footerY = rowsY + rows * rowHeight + 28;
        drawImage(g, footerPanels, 86, footerY, 1425, 95, 1f);
        drawPixelText(g, config.getProperty("network.address", "play.cuborianetwork.net"), 270, footerY + 34, 4, white, shadow, 1);

        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new CompletionException(ex);
        }
    }

    private BufferedImage asset(String path) {
        return assetCache.computeIfAbsent(path, key -> {
            try (InputStream in = getClass().getResourceAsStream(key)) {
                return in == null ? null : ImageIO.read(in);
            } catch (IOException ex) {
                logger.warn("Unable to load asset {}", key, ex);
                return null;
            }
        });
    }

    private void drawImage(Graphics2D g, BufferedImage image, int x, int y, int w, int h, float alpha) {
        if (image == null) return;
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        g.drawImage(image, x, y, w, h, null);
        g.setComposite(old);
    }

    private void drawCover(Graphics2D g, BufferedImage image, int x, int y, int w, int h, float alpha) {
        if (image == null) return;
        double scale = Math.max(w / (double) image.getWidth(), h / (double) image.getHeight());
        int dw = (int) Math.round(image.getWidth() * scale);
        int dh = (int) Math.round(image.getHeight() * scale);
        int dx = x + (w - dw) / 2;
        int dy = y + (h - dh) / 2;
        drawImage(g, image, dx, dy, dw, dh, alpha);
    }

    private int maxPlayers(String key, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(config.getProperty(key, String.valueOf(fallback))));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String prettify(String id) {
        if (id == null || id.isBlank()) return "Server";
        String raw = id.replace('-', ' ').replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String part : raw.split("\\s+")) {
            if (part.isEmpty()) continue;
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        }
        return out.toString().trim();
    }

    private CompletableFuture<Void> publish(String token, String channel, byte[] png) {
        Path idFile = directory.resolve("message-id.txt");
        String id = "";
        try {
            if (Files.exists(idFile)) id = Files.readString(idFile).trim();
        } catch (IOException e) {
            logger.warn("Could not read message ID", e);
        }

        final boolean editing = id.matches("[0-9]{15,22}");
        String endpoint = "https://discord.com/api/v10/channels/" + channel + "/messages" + (editing ? "/" + id : "");

        JsonObject payload = new JsonObject();
        payload.addProperty("content", "");
        JsonArray attachments = new JsonArray();
        JsonObject attachment = new JsonObject();
        attachment.addProperty("id", "0");
        attachment.addProperty("filename", "cuboria-status.png");
        attachments.add(attachment);
        payload.add("attachments", attachments);

        String boundary = "cuboria" + UUID.randomUUID().toString().replace("-", "");
        byte[] body;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n" + gson.toJson(payload) + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"cuboria-status.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(png);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            body = out.toByteArray();
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bot " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method(editing ? "PATCH" : "POST", HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 404 && editing) {
                        try {
                            Files.deleteIfExists(idFile);
                        } catch (IOException ex) {
                            logger.warn("Unable to reset deleted Discord message ID", ex);
                        }
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
                        try {
                            Files.writeString(idFile, newId);
                        } catch (IOException e) {
                            logger.error("Failed to save Discord message ID; duplicates possible on restart", e);
                        }
                    }
                });
    }

    private static Color hex(String h) {
        return Color.decode(h);
    }

    // -----------------------------------------------------
    // Pixel font renderer for live data text.
    // -----------------------------------------------------
    private static final Map<Character, String[]> PIXEL_FONT = new HashMap<>();
    static {
        glyph('A', "01110","10001","10001","11111","10001","10001","10001");
        glyph('B', "11110","10001","10001","11110","10001","10001","11110");
        glyph('C', "01111","10000","10000","10000","10000","10000","01111");
        glyph('D', "11110","10001","10001","10001","10001","10001","11110");
        glyph('E', "11111","10000","10000","11110","10000","10000","11111");
        glyph('F', "11111","10000","10000","11110","10000","10000","10000");
        glyph('G', "01111","10000","10000","10111","10001","10001","01111");
        glyph('H', "10001","10001","10001","11111","10001","10001","10001");
        glyph('I', "11111","00100","00100","00100","00100","00100","11111");
        glyph('J', "00111","00010","00010","00010","10010","10010","01100");
        glyph('K', "10001","10010","10100","11000","10100","10010","10001");
        glyph('L', "10000","10000","10000","10000","10000","10000","11111");
        glyph('M', "10001","11011","10101","10101","10001","10001","10001");
        glyph('N', "10001","11001","10101","10011","10001","10001","10001");
        glyph('O', "01110","10001","10001","10001","10001","10001","01110");
        glyph('P', "11110","10001","10001","11110","10000","10000","10000");
        glyph('Q', "01110","10001","10001","10001","10101","10010","01101");
        glyph('R', "11110","10001","10001","11110","10100","10010","10001");
        glyph('S', "01111","10000","10000","01110","00001","00001","11110");
        glyph('T', "11111","00100","00100","00100","00100","00100","00100");
        glyph('U', "10001","10001","10001","10001","10001","10001","01110");
        glyph('V', "10001","10001","10001","10001","10001","01010","00100");
        glyph('W', "10001","10001","10001","10101","10101","10101","01010");
        glyph('X', "10001","10001","01010","00100","01010","10001","10001");
        glyph('Y', "10001","10001","01010","00100","00100","00100","00100");
        glyph('Z', "11111","00001","00010","00100","01000","10000","11111");
        glyph('0', "01110","10001","10011","10101","11001","10001","01110");
        glyph('1', "00100","01100","00100","00100","00100","00100","01110");
        glyph('2', "01110","10001","00001","00010","00100","01000","11111");
        glyph('3', "11110","00001","00001","01110","00001","00001","11110");
        glyph('4', "00010","00110","01010","10010","11111","00010","00010");
        glyph('5', "11111","10000","10000","11110","00001","00001","11110");
        glyph('6', "01111","10000","10000","11110","10001","10001","01110");
        glyph('7', "11111","00001","00010","00100","01000","01000","01000");
        glyph('8', "01110","10001","10001","01110","10001","10001","01110");
        glyph('9', "01110","10001","10001","01111","00001","00001","11110");
        glyph('/', "00001","00010","00100","00100","01000","10000","00000");
        glyph('.', "00000","00000","00000","00000","00000","01100","01100");
        glyph(':', "00000","01100","01100","00000","01100","01100","00000");
        glyph('-', "00000","00000","00000","11111","00000","00000","00000");
        glyph(' ', "000","000","000","000","000","000","000");
    }

    private static void glyph(char c, String... rows) {
        PIXEL_FONT.put(c, rows);
    }

    private static int pixelTextWidth(String text, int scale, int tracking) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = normalizeGlyph(text.charAt(i));
            String[] rows = PIXEL_FONT.getOrDefault(c, PIXEL_FONT.get(' '));
            width += rows[0].length() * scale;
            if (i < text.length() - 1) width += tracking * scale;
        }
        return width;
    }

    private static char normalizeGlyph(char c) {
        if (Character.isLetter(c)) return Character.toUpperCase(c);
        return PIXEL_FONT.containsKey(c) ? c : ' ';
    }

    private static void drawPixelText(Graphics2D g, String text, int x, int y, int scale, Color fill, Color shadow, int tracking) {
        int cx = x;
        for (int i = 0; i < text.length(); i++) {
            char c = normalizeGlyph(text.charAt(i));
            String[] rows = PIXEL_FONT.getOrDefault(c, PIXEL_FONT.get(' '));
            // shadow pass
            drawGlyph(g, rows, cx + Math.max(2, scale / 2), y + Math.max(2, scale / 2), scale, shadow);
            // outline pass
            drawGlyphOutline(g, rows, cx, y, scale, new Color(5, 10, 25, 245));
            // fill pass
            drawGlyph(g, rows, cx, y, scale, fill);
            cx += rows[0].length() * scale + tracking * scale;
        }
    }

    private static void drawGlyph(Graphics2D g, String[] rows, int x, int y, int scale, Color color) {
        g.setColor(color);
        for (int row = 0; row < rows.length; row++) {
            String line = rows[row];
            for (int col = 0; col < line.length(); col++) {
                if (line.charAt(col) == '1') {
                    g.fillRect(x + col * scale, y + row * scale, scale, scale);
                }
            }
        }
    }

    private static void drawGlyphOutline(Graphics2D g, String[] rows, int x, int y, int scale, Color color) {
        g.setColor(color);
        for (int row = 0; row < rows.length; row++) {
            String line = rows[row];
            for (int col = 0; col < line.length(); col++) {
                if (line.charAt(col) != '1') continue;
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        int nr = row + oy;
                        int nc = col + ox;
                        if (nr < 0 || nr >= rows.length || nc < 0 || nc >= line.length() || rows[nr].charAt(nc) != '1') {
                            g.fillRect(x + col * scale + ox, y + row * scale + oy, scale, scale);
                        }
                    }
                }
            }
        }
    }
}
