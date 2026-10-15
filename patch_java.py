from pathlib import Path
p=Path('/mnt/data/cuboria_v09_work/src/main/java/net/cuboria/status/CuboriaStatus.java')
s=p.read_text();s=s.replace('version = "0.8.0"','version = "0.9.0"')
a=s.index('    private byte[] render(List<ServerStat> servers, int totalPlayers) {')
b=s.index('    private void pixelPanelText(',a)
replacement='''    private byte[] render(List<ServerStat> servers, int totalPlayers) {
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
        atlasField(g,"interval","Updates every "+config.getProperty("update.seconds","30")+"s",1216,832,20,220,Color.WHITE);
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
'''
s=s[:a]+replacement+s[b:]
p.write_text(s)
print('patched',len(s))
