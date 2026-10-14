# CuboriaStatus v0.4 — refined dashboard

This is the full Java/Gradle source project, not a compiled JAR.

## What changed
- More vertical room: corrected footer clipping and separated sections
- Landscape softly blended into header rather than a hard-edged rectangle
- Sharper server icons loaded from PNG resources
- Preserves OneBlock/Arcade live players, backend ping, outage state, Discord message updating
- Shows configured display maxima of 10,000 for network and each backend

## Build using GitHub Actions
Upload the **contents** of this project folder to the root of the GitHub repository, preserving `src/main/java/` and `src/main/resources/` paths. Select **Actions -> Build CuboriaStatus -> Run workflow**. Download the plugin JAR from the run's Artifacts.

## Deploy
1. Back up your current JAR and `plugins/cuboriastatus/config.properties`.
2. Stop Velocity and replace the CuboriaStatus JAR with the new build.
3. Start Velocity. Existing `config.properties` is preserved, not overwritten. Set `servers=oneblock,arcade`, all player maxima to `10000` and `update.seconds=30` in that config.
4. Do not commit the bot token to GitHub. Keep it in the private Velocity config only.
5. Watch console for plugin or Discord errors.

**Notes:** This source was not compiled in this environment. It is intended for the successful GitHub Actions build used previously. Proxy-to-backend pings are not individual player latency. If Velocity stops, this plugin cannot refresh the Discord message until it restarts. The 10,000 figure is dashboard display capacity, not an enforced player limit.
