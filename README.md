# CuboriaStatus v0.8 — two-state modular Velocity dashboard

Build the plugin using GitHub Actions (`.github/workflows/build.yml`). Download the JAR artifact and install on the Velocity proxy. Java 21+ supported by the Gradle target; your proxy uses Java 25.

## Changes
- **Network status only Online / Offline**, with no Degraded state. If at least one registered backend answers its ping, network shows Online; if none answer, Offline. (This does not measure the proxy being alive from outside.)
- OneBlock and Arcade independently show connected players, online/offline, and ping.
- Status artwork is separate `online.png` / `offline.png`, with each graphic position configurable.
- Each layer copied out to `plugins/cuboriastatus/assets/` on first start; edit its PNG then restart proxy to clear the image cache.
- Live text positions are adjustable with `layout.text.<field>.x`, `.y`, `.scale` in `config.properties`.
- Per-backend/max network display set to 10000. This is **not** enforced capacity.
- Edits one Discord message every 30 sec; preserves `message-id.txt`.

## Important
This build retains the source artwork placement from v0.7. The source project's earlier hand-drawn frame and rows still differ from the exact approved mockup. It has not been compiled or tested here. Test the Discord output before removing your working v0.7 JAR. The separate assets are transparent PNGs (not SVG vectors).

## Security
Do not commit your Discord bot token. Put it only into your server's `plugins/cuboriastatus/config.properties`.

## Build
`gradle clean shadowJar --no-daemon`

## Installation
1. Upload extracted contents **inside** `CuboriaStatus-v0.8/` to your GitHub repository root (preserve src/main/... paths).
2. Run Actions → Build CuboriaStatus.
3. Download and extract the build artifact.
4. Stop Velocity, back up/remove old CuboriaStatus jar, add new jar, start proxy.
5. Keep your existing server-side config/token and message ID. New layout options can be added to existing config.
