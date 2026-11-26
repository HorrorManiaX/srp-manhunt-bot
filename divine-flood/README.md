# Divine Flood — SRP Manhunt
Forge 1.12.2 (14.23.5.2860), Java 8 bytecode, no external runtime dependencies. Install the same reobfuscated jar on server and clients. Back up the world first: this event edits actual blocks and is not reversible by the stop command.

First server start initializes a persistent 72-hour real-time countdown (including downtime). The server sends the timer to every client; the gold/icon-inspired HUD appears at the top center. If the admin accepts the explanation, use /divineflood pardon (OP level 4 or console). No automatic confession detection. /divineflood status, /divineflood stop, /divineflood start [seconds] (after stop). /divineflood stop stops new writes but does not drain fluids.

When the countdown expires, actual source water fills air through Y=120 in dimension 0; source lava fills air through Y=120 in the Nether and Y=128 in the End. All three levels configurable, inclusive. Solid blocks and tile entities are retained; rooms and caves are not exempt. Loaded chunks are filled in a bounded queue, and other chunks when loaded later. Does not force-load chunks or flood other dimensions. Per-chunk completion stored in chunk NBT. Unfinished chunks resume by rescanning on reload. Vanilla fluid interactions and lighting may still be expensive; test on a backup, adjust config. Removing the mod does not undo placed fluid.

Build: JDK 17 for Gradle, JDK 8 toolchain for Java compilation, Gradle 7.6.4: gradle clean build. GitHub Actions uploads the reobfuscated jar.
