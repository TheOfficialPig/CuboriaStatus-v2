# CuboriaStatus — Velocity status dashboard (v0.2.1 source)

A Velocity plugin that generates a dark PNG dashboard and creates/updates **one** Discord bot message every 30 seconds. Displays proxy-wide online player count; Hub, OneBlock, and Arcade player counts; independent online/unreachable backend health; and measured proxy-to-backend ping in milliseconds.

## Current status

**Source prototype, NOT a verified JAR release.** This environment cannot access Gradle/Maven repositories to resolve Velocity API or annotation processor dependencies, so compilation against the actual Velocity 4.1.2-SNAPSHOT runtime has **not** been tested. Review/test on a staging proxy before production deployment. Java 25 on your server is compatible with Java 21-targeted class files, subject to Velocity API compatibility.

## Fastest build path: GitHub Actions (no Gradle installation)

1. Create a **private** GitHub repository and upload the project files, including `.github/workflows/build.yml` (keep the folder structure).
2. Open **Actions → Build CuboriaStatus → Run workflow**. If this is the first Actions run, enable workflows when prompted.
3. When the job succeeds, download its `CuboriaStatus-plugin-JAR` artifact; unzip it to get the JAR.
4. If the build fails, inspect the **Build shaded plugin JAR** log. The repository and plugin API must be reachable; the snapshot API may need adjusting.

**Never commit your real Discord token.** This source includes only a placeholder.

## Build with access to dependencies

Install JDK 21+ and Gradle 8.x, or run with a Gradle wrapper if your environment supplies one. From the project directory:

```sh
gradle clean shadowJar
```

Result should be `build/libs/CuboriaStatus-0.2.0.jar` after a successful build. Currently depends on `velocity-api:3.4.0-SNAPSHOT`; if your proxy's 4.1.2-SNAPSHOT API differs, use the matching available API artifact and rebuild. The shaded JAR relocates Gson; Velocity provides Guice, SLF4J and its own API.

## Install & configure

1. Create a fresh Discord bot token via the Discord Developer Portal. Never reuse the token previously shared in chat.
2. Add the bot to your Discord server with **View Channel**, **Send Messages**, **Attach Files**, and **Read Message History** in its target channel.
3. Stop Velocity, place the *successfully built* JAR in the proxy `plugins/` folder. Move/disable Discord Status Bot once the new plugin has been tested to avoid double posting.
4. Start Velocity once to create `plugins/cuboriastatus/config.properties`, stop Velocity, edit `discord.token`, `discord.channel-id`, and `network.address`, then start again.
5. The plugin saves the Discord message ID in `plugins/cuboriastatus/message-id.txt` to update the same post. If that message gets deleted, a new message should be created on the next scheduled tick after detection.

## Backend IDs

The default config includes exactly: `hub`, `oneblock`, `arcade`. They must match the keys from `velocity.toml`; SMP is intentionally absent.

## Interpretation

- The player counts are the number of players Velocity currently sees on each backend. These are not max capacities.
- Each backend's ping is time for **Velocity** to receive the backend's server-list ping response; **not player/client latency**. It can change between updates.
- A 5-second timeout, rejected connection, or unavailable server produces an offline indicator for that backend. A backend that answers ping may still have gameplay issues.
- Discord dashboard updates **stop when Velocity stops**; external monitoring is needed to display an actual proxy-offline dashboard. If the proxy crashes, Discord will continue showing the most recent image.
- A successful proxy boot does not guarantee a valid bot token or permission to send messages.
- This prototype doesn't implement Discord 429 retry/backoff or token hot reload. Rate limits and errors are logged; next tick tries again.

## Security

Protect config.properties from public downloads, keep bot credentials private, and rotate any exposed bot tokens.
