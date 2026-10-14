# CuboriaStatus v0.3 — Discord dashboard

Custom Velocity status dashboard using the Cuboria logo and a neon-blue renderer. Shows live proxy total, per-backend connected players, backend reachability, and proxy-to-backend ping.

## Build

Upload **the contents** of this project directory (preserving paths) into your GitHub repository. In Actions, run **Build CuboriaStatus**. Download `CuboriaStatus-plugin-JAR` from the successful run and extract the JAR.

## Install

1. Stop Velocity. Replace the prior `cuboriastatus` JAR in `plugins/` with v0.3.
2. The config is stored in `plugins/cuboriastatus/config.properties`. **If this file already exists, the JAR does not overwrite it.** Edit your existing file by hand.
3. Set `servers=oneblock,arcade`, `network.max-players=10000`, `server.oneblock.max-players=10000`, `server.arcade.max-players=10000`, and optionally `network.address=play.cuborianetwork.net`.
4. Keep your valid `discord.token` and `discord.channel-id` on the server (not GitHub).
5. Start Velocity; the same Discord message will update on the configured interval.

## Important

All figures for players and ping are measured live. The 10,000 capacities are **display-only**; these values do not enforce player limits. The Velocity proxy's `show-max-players` option is also primarily a displayed maximum.

The design is **code-rendered**, not a static screenshot. Brand logo and a small header landscape are included under `src/main/resources/`.

The proxy must be running for the bot to refresh Discord. A separate external monitor would be required to reliably show a proxy-down status while the proxy itself is offline.
