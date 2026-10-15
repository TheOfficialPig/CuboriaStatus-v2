# CuboriaStatus v0.7 — layered dashboard

For your Velocity 4.1.2-SNAPSHOT network with Java 25.

## Included
- Separately replaceable transparent PNG art assets in `src/main/resources/dashboard-assets/` (not SVG vectors)
- External asset files copied automatically on startup to `plugins/cuboriastatus/assets/`
- Three network sign appearances: `online.png`, `degraded.png`, `offline.png`
- Live pixel-style rendering for player totals, per-server counts, individual ping and network health
- Individually rendered OneBlock and Arcade rows, with green/red status colors
- Configurable PNG element coordinates using `layout.<asset>.x/y/w/h` keys in `config.properties`
- Retains the existing Discord message ID and automatically updates the same message

## Build
Run GitHub Actions (`Build CuboriaStatus`) and download its `CuboriaStatus-plugin-JAR` artifact. Or run `gradle clean shadowJar` with Gradle and Java 21+ installed.

## Installation
1. Back up the old plugin JAR and `plugins/cuboriastatus/` folder.
2. Replace the JAR while Velocity is stopped.
3. Start Velocity, then edit `plugins/cuboriastatus/config.properties` to set your bot token and channel.
4. Restart Velocity for new configuration values.

The initial run copies bundled artwork into `plugins/cuboriastatus/assets/`. **Existing artwork in this folder is not overwritten.** If you are upgrading after testing an older build, replace the individual assets you want changed or delete the `assets` directory while the proxy is stopped to regenerate it.

## Status state behavior
- Both `oneblock` and `arcade` responding: ONLINE
- One responsive: DEGRADED
- Neither responding: OFFLINE
- Backend rows with errors show a red dot and `Offline` instead of a ping

Network offline means **all monitored backends are unavailable while Velocity is running**. When Velocity itself shuts down or loses connectivity, this plugin cannot send an update to Discord and the last status image remains. External monitoring is required for that case.

## Branding and layout
- The background, logo, characters, sign states, icons, and most decorative pieces are independent raster PNG files.
- Labels and changing statistics are drawn by code using a built-in pixel typeface, so they can change without regenerating artwork.
- Asset positions are editable by `layout.<name>.x/y/w/h` in `config.properties`; the supported names include `background`, `logo`, `character-left`, `character-right`, `online`, `degraded`, `offline`, `players`, `network`, `oneblock`, `arcade`.
- Server-list headings, row backgrounds and footer backgrounds are code-drawn for readability at Discord image size.

## Notes
- Per-server displayed maximum 10,000 is not a capacity enforcement setting.
- The project depends on the Velocity API and Gson via Gradle.
- Not tested against the user's actual proxy yet. Verify the build before replacing the working JAR.
