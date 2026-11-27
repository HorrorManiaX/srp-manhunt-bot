# Manhunt AI Survivors (experimental 0.2)

AI survivor overlay for X4yii/Automata, pinned to upstream commit 5bf94f646ff6cc0fb07b20c1067f9c393d451225. GPL-3.0 upstream license applies; the assembled source retains the upstream LICENSE. Upstream: https://github.com/X4yii/Automata

Target: Minecraft 1.12.2 / Cleanroom 0.7.14-alpha, JDK 25 build. Server-side AI EntityPlayerMP bodies, explicitly identified with AI_ prefix and not counted as authenticated humans. Optional reflective adapters for Cotesia and Modular Warfare. Includes patrol, pathfinding, basic melee/gun behavior, food, bounded 2x2 crafting, administrator-controlled building and basic Russian chat replies. Not a tested release; no automatic turret setup, autonomous progression or LLM integration yet.

Build locally: check out the pinned upstream commit, run python3 manhunt-ai/apply_overlay.py /path/to/Automata, then run bash gradlew build --no-daemon in that checkout. GitHub Actions, if enabled, assembles the same source and uploads the built JAR and complete source archive.

Do not install on a production server before testing. No private modpack jars, configs, credentials, or player chat logs are included.

## Tactical AI 0.3

- Local decision layer every AI drive tick; perception every 4 ticks while alerted / 10 idle, or 8 / 24 under load. These are scheduling intervals, not benchmarked response times.
- Damage events and nearby explosion events trigger immediate alert state. Nearby visible SRP explosives, clouds and incoming projectiles trigger evasion. No global map or chunk loading is intended.
- Short-term last-visible threat memory, escape destinations, stale route cancellation, stuck recovery and opportunistic pickup of unowned visible supplies.
- /manhuntai list includes measured mean AI time, mean server tick interval and degraded mode. Optional scans and crafting slow down under pressure; this is not a hard CPU cap.
- Standalone BrainSelfTest checks 11 action cases plus scan cadence. Full Forge/Cleanroom integration and mod-specific APIs remain unverified until successful CI and a dedicated test server run.
- No full crafting progression, automated turret configuration, persistent memory or LLM dialogue yet. AI identity remains explicit.
