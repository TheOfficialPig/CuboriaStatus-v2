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

@Plugin(id = "cuboriastatus", name = "CuboriaStatus", version = "0.7.0", authors = {"Cuboria"})
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
            Path artDir=directory.resolve("assets");
            Files.createDirectories(artDir);
            String[] arts={"background","logo","character-left","character-right","online","degraded","offline","players","network","header-title","header-players","header-ping","row","footer-address","footer-update","oneblock","arcade"};
            for(String name:arts){
                Path target=artDir.resolve(name+".png");
                if(Files.notExists(target))try(InputStream asset=getClass().getResourceAsStream("/dashboard-assets/"+name+".png")){
                    if(asset!=null)Files.copy(asset,target);
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
            logger.info("CuboriaStatus v0.7.0 started with independent artwork layers.");
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

    // Every image is a separate PNG file, copied into plugins/cuboriastatus/assets.
    // Every slot can be repositioned with layout.<slot>.x/y/w/h in config.properties.
    private byte[] render(List<ServerStat> servers, int totalPlayers) {
        final int W = 1672;
        final int H = 941;
        BufferedImage image = new BufferedImage(W,H,BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.decode("#071327"));g.fillRect(0,0,W,H);
        place(g,"background",0,0,W,H);
        g.setColor(new Color(3,12,32,185));g.fillRoundRect(22,20,1628,895,26,26);
        g.setColor(new Color(41,168,238,170));g.setStroke(new BasicStroke(3));g.drawRoundRect(22,20,1628,895,26,26);

        long up = servers.stream().filter(ServerStat::online).count();
        String status = servers.isEmpty() || up==0 ? "OFFLINE" : up==servers.size() ? "ONLINE" : "DEGRADED";
        Color signal = status.equals("ONLINE") ? Color.decode("#31F28D") : status.equals("OFFLINE") ? Color.decode("#FF6276") : Color.decode("#FFCA56");
        String state = status.equals("ONLINE") ? "Operational" : status.equals("DEGRADED") ? "Degraded" : "Offline";

        // Header layers (never flatten into the underlying background).
        place(g,"logo",200,40,780,265);
        place(g,"character-left",44,135,178,178);
        place(g,"character-right",1140,189,197,148);
        place(g,status.toLowerCase(java.util.Locale.ROOT),1294,85,335,174);
        // Draw the state word over the sign to avoid a misleading static ONLINE label.
        // A panel inset covers the old green label and uses the same pixel font for all states.
        // Disabled by default for ONLINE, whose source artwork already contains the correct word.
        if (!status.equals("ONLINE")) {
            g.setColor(status.equals("OFFLINE") ? new Color(74,21,33,245) : new Color(73,47,20,245));
            g.fillRoundRect(1436,162,160,45,8,8);
            drawPixelText(g,status,1444,172,4,signal,Color.decode("#081021"),1);
        }
        place(g,"players",66,310,734,228);
        place(g,"network",852,310,754,228);
        pixelPanelText(g,totalPlayers+" / "+maxPlayers("network.max-players",10000),282,440,5,Color.decode("#F4F8FF"));
        pixelPanelText(g,state,1093,440,5,signal);

        // Keep headings as live text rather than flattening or horizontally crushing artwork.
        g.setColor(new Color(12,30,64,240));g.fillRoundRect(104,551,1450,57,12,12);
        g.setColor(new Color(70,177,241,210));g.setStroke(new BasicStroke(3));g.drawRoundRect(104,551,1450,57,12,12);
        pixelPanelText(g,"SERVERS",140,565,5,Color.decode("#EAF5FF"));
        pixelPanelText(g,"PLAYERS",1008,569,4,Color.decode("#EAF5FF"));
        pixelPanelText(g,"PING",1363,569,4,Color.decode("#EAF5FF"));
        int n = Math.min(servers.size(),3);
        for(int i=0;i<n;i++) {
            ServerStat row = servers.get(i);
            int y=624+i*83;
            // Separate row panel geometry and editable contents.
            g.setColor(new Color(7,26,55,245));g.fillRoundRect(110,y,1450,76,13,13);
            g.setColor(new Color(41,180,242,225));g.setStroke(new BasicStroke(3));g.drawRoundRect(110,y,1450,76,13,13);
            g.setColor(new Color(54,95,150,165));g.setStroke(new BasicStroke(2));
            g.drawLine(989,y+8,989,y+68);g.drawLine(1310,y+8,1310,y+68);
            Color rowColor = row.online() ? Color.decode("#38F28C") : Color.decode("#FF6276");
            g.setColor(rowColor);g.fillOval(140,y+26,22,22);
            String icon = row.id().equalsIgnoreCase("oneblock")?"oneblock":row.id().equalsIgnoreCase("arcade")?"arcade":null;
            if(icon!=null) place(g,icon,178,y+10,55,55);
            pixelPanelText(g,row.name(),255,y+22,4,Color.decode("#F3F7FF"));
            String count=row.players()+" / "+maxPlayers("server."+row.id()+".max-players",10000);
            pixelPanelText(g,count,1035,y+23,3,Color.decode("#F3F7FF"));
            String ping=row.online()?row.pingMs()+" ms":"Offline";
            Color pingColor=!row.online()?Color.decode("#FF6276"):row.pingMs()>80?Color.decode("#FFCA56"):Color.decode("#38F28C");
            pixelPanelText(g,ping,1370,y+23,3,pingColor);
        }
        int footerY=Math.min(850,628+Math.max(2,n)*83+12);
        g.setColor(new Color(11,34,67,244));g.fillRoundRect(106,footerY,940,88,14,14);
        g.fillRoundRect(1080,footerY,474,88,14,14);
        g.setColor(new Color(47,175,240,225));g.setStroke(new BasicStroke(3));
        g.drawRoundRect(106,footerY,940,88,14,14);g.drawRoundRect(1080,footerY,474,88,14,14);
        pixelPanelText(g,"SERVER ADDRESS",149,footerY+12,3,Color.decode("#98CDF9"));
        pixelPanelText(g,config.getProperty("network.address","play.cuborianetwork.net"),149,footerY+48,3,Color.decode("#F4F8FF"));
        pixelPanelText(g,"UPDATES EVERY "+config.getProperty("update.seconds","30")+"S",1107,footerY+31,3,Color.decode("#EAF5FF"));
        g.dispose();
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            if(!ImageIO.write(image,"png",out))throw new IOException("PNG writer unavailable");
            return out.toByteArray();
        } catch(IOException ex) {throw new CompletionException(ex);}
    }

    private void pixelPanelText(Graphics2D g,String value,int x,int y,int scale,Color color){
        drawPixelText(g,value,x,y,scale,color,Color.decode("#050B1F"),1);
    }
    private int slot(String name,String axis,int fallback){
        try{return Integer.parseInt(config.getProperty("layout."+name+"."+axis,""+fallback));}
        catch(NumberFormatException e){return fallback;}
    }
    private void place(Graphics2D g,String key,int x,int y,int w,int h){
        x=slot(key,"x",x);y=slot(key,"y",y);w=slot(key,"w",w);h=slot(key,"h",h);
        BufferedImage src=asset("/dashboard-assets/"+key+".png");
        if(src==null)return;
        g.drawImage(src,x,y,w,h,null);
    }
    private void drawNineSliceRow(Graphics2D g,String key,int x,int y,int w,int h){
        x=slot("row","x",x); w=slot("row","w",w);h=slot("row","h",h);
        BufferedImage src=asset("/dashboard-assets/"+key+".png");
        if(src==null)return;
        int left=Math.round(src.getHeight()*.75f);
        left=Math.min(left,src.getWidth()/4);
        int right=left;
        g.drawImage(src,x,y,x+100,y+h,0,0,left,src.getHeight(),null);
        g.drawImage(src,x+100,y,x+w-100,y+h,left,0,src.getWidth()-right,src.getHeight(),null);
        g.drawImage(src,x+w-100,y,x+w,y+h,src.getWidth()-right,0,src.getWidth(),src.getHeight(),null);
    }

    private synchronized BufferedImage asset(String path) {
        return assetCache.computeIfAbsent(path, key -> {
            try {
                Path external=directory.resolve("assets").resolve(Path.of(key).getFileName().toString());
                if(Files.isRegularFile(external))return ImageIO.read(external.toFile());
                try(InputStream in=getClass().getResourceAsStream(key)){
                    return in==null?null:ImageIO.read(in);
                }
            } catch(IOException ex){logger.warn("Cannot load artwork {}",key,ex);return null;}
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
