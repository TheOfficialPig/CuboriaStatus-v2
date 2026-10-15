# CuboriaStatus v0.5.0

Asset-based Discord server status image plugin for **Velocity**.

## What changed in v0.5.0
- Uses the new Mineclub-style art assets.
- Uses a built-in pixel live-text renderer so player counts, ping, status text, and server address match the image style much better.
- Keeps live data for:
  - total proxy player count
  - backend online/offline state
  - backend player counts
  - backend ping from proxy to backend
  - server address
- Discord message is edited in place after the first post.

## Build
Use GitHub Actions or Gradle:

```bash
gradle clean shadowJar
```

Final jar:

```text
build/libs/CuboriaStatus-0.5.0.jar
```

## Install
1. Put the jar in your Velocity `plugins/` folder.
2. Start the proxy once.
3. Edit:

```text
plugins/cuboriastatus/config.properties
```

4. Set your Discord bot token and channel ID.
5. Restart Velocity.

## Default server config
```properties
servers=oneblock,arcade
server.oneblock=OneBlock
server.oneblock.max-players=10000
server.arcade=Arcade
server.arcade.max-players=10000
network.max-players=10000
network.address=play.cuborianetwork.net
update.seconds=30
```

## Notes
- This plugin reads the backend servers by **Velocity server ID**, not by forced hosts.
- `network.max-players` and `server.<id>.max-players` are **display values only**.
- If you delete the Discord status message, the plugin will recreate it on the next update cycle.
