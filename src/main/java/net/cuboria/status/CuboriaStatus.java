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

@Plugin(id = "cuboriastatus", name = "CuboriaStatus", version = "0.9.0", authors = {"Cuboria"})
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
            String[] arts={"template-base","sign-online","sign-offline","font-atlas","background","logo","character-left","character-right","players","network","row","footer-address","footer-update","oneblock","arcade"};
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
            logger.info("CuboriaStatus v0.9.0 started with independent artwork layers.");
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
        final int W = 1672, H = 941;
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        place(g, "template-base", 0, 0, W, H);

        long connected = servers.stream().filter(ServerStat::online).count();
        boolean online = connected > 0;
        Color statusColor = online ? Color.decode("#31F28D") : Color.decode("#FF6A79");
        String sign = online ? "sign-online" : "sign-offline";
        place(g, sign, 1319, 157, 290, 111);
        if (!online) {
            // The offline overlay changes the state label without altering its frame.
            g.setColor(new Color(38, 11, 21));
            g.fillRect(1392, 184, 192, 49);
            drawAtlas(g, "OFFLINE", 1408, 188, 30, 175, statusColor);
        }

        // Only values change. All static art, labels, panels, and characters
        // are preserved from the approved master image.
        atlasField(g, "total", totalPlayers+" / "+maxPlayers("network.max-players",10000), 377, 408, 31, 412, Color.WHITE);
        atlasField(g, "network-state", online ? "Operational" : "Offline", 1095, 407, 30, 360, statusColor);
        int n = Math.min(servers.size(), 2);
        for (int i=0;i<n;i++) {
            ServerStat r=servers.get(i);
            int y=614+i*80;
            // Online/offline dot, independently controlled per backend.
            g.setColor(r.online()?Color.decode("#2CF090"):Color.decode("#F65B6B"));
            g.fillOval(185,y+10,23,23);
            String count=r.online()?r.players()+" / "+maxPlayers("server."+r.id()+".max-players",10000):"0 / "+maxPlayers("server."+r.id()+".max-players",10000);
            String ping=r.online()?r.pingMs()+" ms":"Offline";
            atlasField(g,"server."+r.id()+".players",count,981,y+9,25,236,Color.WHITE);
            atlasField(g,"server."+r.id()+".ping",ping,1305,y+9,25,170,r.online()?Color.decode("#31F28D"):Color.decode("#FF6478"));
        }
        atlasField(g,"address",config.getProperty("network.address","play.cuborianetwork.net"),386,838,27,438,Color.WHITE);
        // Update interval is fixed at its design position but changes with configuration.
        // The update interval label is baked into the approved footer artwork.
        // Keep the matching typography rather than drawing a conflicting second label.
        g.dispose();
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            if(!ImageIO.write(image,"png",out))throw new IOException("PNG writer unavailable");
            return out.toByteArray();
        } catch(IOException ex) {throw new CompletionException(ex);}
    }

    private static final String FONT_CHARS="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 /.-:_!?@#+%()";
    private void atlasField(Graphics2D g,String key,String value,int x,int y,int size,int maxWidth,Color color){
        x=slot("text."+key,"x",x);
        y=slot("text."+key,"y",y);
        size=slot("text."+key,"size",size);
        maxWidth=slot("text."+key,"max-width",maxWidth);
        drawAtlas(g,value,x,y,size,maxWidth,color);
    }
    private void drawAtlas(Graphics2D g,String value,int x,int y,int height,int maxWidth,Color color){
        BufferedImage sheet=asset("/dashboard-assets/font-atlas.png");
        if(sheet==null){pixelPanelText(g,value,x,y,3,color);return;}
        int cellWidth=43,cellHeight=49,columns=12;
        double natural=value.length()*height*0.69;
        int letterWidth=Math.max(6,(int)Math.round(height*0.69*Math.min(1.,maxWidth/Math.max(1,natural))));
        Composite original=g.getComposite();
        // Keep source glyph colors, with a modest state tint only for the network status.
        for(int i=0;i<value.length();i++){
            int idx=FONT_CHARS.indexOf(value.charAt(i));
            if(idx<0)idx=FONT_CHARS.indexOf(' ');
            if(value.charAt(i)==' ')continue;
            int sx=(idx%columns)*cellWidth,sy=(idx/columns)*cellHeight;
            g.drawImage(sheet,x+i*letterWidth,y,x+(i+1)*letterWidth,y+height,
                    sx,sy,sx+cellWidth,sy+cellHeight,null);
        }
        g.setComposite(original);
    }
    private void pixelPanelText(Graphics2D g,String value,int x,int y,int scale,Color color){
        drawPixelText(g,value,x,y,scale,color,Color.decode("#050B1F"),1);
    }
    private void liveText(Graphics2D g,String field,String text,int x,int y,int scale,Color color) {
        x=slot("text."+field,"x",x);
        y=slot("text."+field,"y",y);
        scale=Math.max(1,slot("text."+field,"scale",scale));
        pixelPanelText(g,text,x,y,scale,color);
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
