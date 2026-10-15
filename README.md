# CuboriaStatus v0.9 — approved artwork renderer

Velocity Discord status dashboard. **Source release; requires Gradle build and runtime testing.**

## Design
`src/main/resources/dashboard-assets/template-base.png` is built from the user-approved Cuboria status artwork. It contains fixed scenery, characters, panels, labels, rows, and footer. Live values and network signs are drawn separately. Existing PNG modules are retained in `dashboard-assets` for future swaps.

`font-atlas.png` is a **fixed-cell technical glyph atlas** with explicit 43×49 pixel cells, 12 columns, ordered by the `FONT_CHARS` constant in the Java renderer. It is a best-effort styled alternative; it is **not an exact extraction of the font from the original artwork**. `icy_pixel_font_atlas_sprite_sheet.png` is also supplied as a visual font reference, but its irregular glyph alignment is not safe for automatic slicing.

## Config and installation
Build with the supplied GitHub Actions workflow (`gradle clean shadowJar`), stop Velocity, replace the JAR in `plugins`, and restart. Your existing server-side `plugins/cuboriastatus/config.properties` retains Discord credentials. Backend identifiers must match Velocity server names (`oneblock`, `arcade`). This JAR copies the new assets to `plugins/cuboriastatus/assets` **only when files do not already exist**. If updating from older versions, replace the `template-base.png`, `sign-online.png`, `sign-offline.png` and `font-atlas.png` files if you want fresh versions.

New positional overrides use `layout.text.total.x`, `layout.text.total.y`, `layout.text.total.size` and `layout.text.total.max-width`; similarly `network-state`, `server.oneblock.players`, `server.oneblock.ping`, `server.arcade.players`, `server.arcade.ping`, `address` and `interval`. The status sign is selected by proxy-backend availability: at least one backend reachable => ONLINE, none => OFFLINE. A dead proxy cannot update Discord until it starts again.

The 10,000-player maximum is for display only; it does not alter proxy capacity.
