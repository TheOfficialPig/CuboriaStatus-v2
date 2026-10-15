# CuboriaStatus v0.6.0 — fixed-layout pixel dashboard

This build uses the approved status dashboard artwork at its native 1672x941 size. It does not stretch the separate UI panels. Only the live player counts, health label and two backend pings are drawn over the reserved numeric areas.

## Build
Run `gradle clean shadowJar --no-daemon` or use the included GitHub Actions workflow. Upload the JAR from `build/libs` into your Velocity proxy's `plugins/` folder.

## Config
Keep your existing `plugins/cuboriastatus/config.properties` (with your bot token and channel ID); it is not overwritten. The intended backend IDs are `oneblock,arcade`, with all displayed maximums set to 10000. This dashboard layout is fixed to those two backends. Its footer address is currently part of the approved background art.

## Limitations
This is source code for a release candidate, not a compiled or tested JAR. A green ONLINE sign is part of the background illustration, while the actual live network status is rendered in the Network Status field. The customized mock-up uses artwork as a static template, not independently editable vector paths.
