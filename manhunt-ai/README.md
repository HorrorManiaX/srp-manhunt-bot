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

## Autonomous survival 0.4 (experimental)

New modules: SurvivorProgression (six stable role labels and deterministic needs policy), SurvivorWork (local gathering and workbench tasks), SurvivorTechnology (bounded backward recipe planner), SurvivorMemory (WorldSavedData per dimension), SurvivorPermissions (vanilla and Clans permission checks).

- Enable a bot's autonomous work explicitly with /manhuntai auto <name> on. Disable with off. Operator permission level 2 required. Local work radius is 12 blocks from its initial home; no forced chunk loading; only visible vanilla logs and stone/cobblestone are autonomous mining targets. No raids or arbitrary block excavation.
- Actual break progress, tool selection, vanilla harvesting and pickup. Workers reserve targets to avoid duplicating work. Inventory-based progression through planks, sticks, crafting table, wooden and stone tools and a basic 5x5 perimeter. A queued perimeter is not guaranteed completed. Six role labels; only builder priority is currently distinct in progression policy.
- 3x3 crafting requires a nearby visible, permitted workbench. Technology goal: /manhuntai goal <name> <registry-item-id>, or clear. Goals use item IDs (not metadata/NBT subtypes), real registry recipes and a bounded recursive search. Allowed namespaces: minecraft, thermalfoundation, thermalexpansion, thermaldynamics, openmodularturrets, modularwarfare. Goals do not support machine recipes, smelting, multiblocks, custom workbenches or actual turret ownership/power setup. /manhuntai list displays current goal and a candidate missing ingredient, not a complete bill of materials.
- Persistence: last resource/bench/threat locations and bounded counters. Not a full persistent player inventory, personality or offline agent simulation. No chat transcripts retained.
- Optional adapter calls the actual Modular Warfare basic server reload handler; enhanced weapons needing animation-task completion intentionally skipped. The adapter signatures were inspected in the supplied jar. Cotesia API signatures were recovered from intact entries of the truncated supplied jar; full jar remains unusable for testing. Clans block-break and placement API signatures also inspected. All three adapters still need live integration tests.
- Vanilla armor/shield selection, explicit AI squad warning messages, rate-limited Russian status replies. No external LLM yet. AI identity remains explicit and not included in authenticated player count.
- Standalone progression tests: 14 cases passed locally on JDK 21, and are included in the JDK 25 CI workflow. Full Gradle build and server behavior not verified. Do not deploy on production until CI and integration tests pass.

## Version 0.5 — Persistence and industrial work (experimental)

- `/manhuntai park <name>`, `/manhuntai parked`, `/manhuntai resume <name>`: dimension-local saved inventory, health, food, home, last position and progression settings. Normal orderly shutdown parks living AI. Explicit resume requires its saved position to be loaded and standable. This is not crash-atomic persistence; in-flight furnace jobs and temporary effects are not restored.
- `/manhuntai furnace <name>`: queues placement of a carried vanilla furnace. `/manhuntai smelt <name> <input-item-id>`: a single real furnace operation, with a carried input and coal. Only a station placed by this agent in its current session is eligible. Existing contents are not taken. On interruption, remaining materials stay in the furnace. Vanilla smelting time is unchanged. These operations do not support Thermal machines, RF, or turret configuration yet.
- `/manhuntai industry <name> on` plus `/manhuntai auto <name> on`: experimental iron-tool progression using visible coal/iron ore, real harvesting and the agent's furnace. Limited to the local home radius. No underground omniscience, claim bypass, forced chunks or guaranteed resource availability.
- Actual Clans container-interaction API signature verified with javap on the supplied 2.1.9 JAR. Runtime permission and machine behavior still require integration testing.
- Optional local dialogue: install and run Ollama separately, set the server process environment variable `MANHUNT_AI_OLLAMA_MODEL` to an installed model name. Only directly addressed messages (`@AI_name ...`) are sent to loopback 127.0.0.1:11434. Tell players about this processing before enabling. Single worker, bounded queue, request timeout, fallback replies; no model-generated game commands, no external API, no fake human identity. Model output can still be inaccurate and is not used for gameplay decisions. Disabled unless the variable is set.
- CI now supports a draft PR for build visibility and cancels superseded runs on the same ref. The full Gradle build has not been verified; no production-ready binary is claimed.

## Version 0.6 — Collective construction (experimental)

- `/manhuntai squadbuild <leader>` distributes a 5x5 two-level perimeter among up to six nearby AI agents with nearby home positions. The entrance is left open. Each worker handles both levels of its assigned columns; 30 blocks total. Requires real supplies and permissions. This is a wall plan, not a roofed complete base. Placement success depends on terrain, visibility, pathfinding and materials.
- Construction uses a standable adjacent approach instead of navigating to the target block itself; it rejects self-intersecting placement and reports placed/skipped/queued counts in activity status.
- Furnace jobs reuse residual heat and their own previously inserted coal. Stalled jobs expire without deleting materials in the world.
- Standalone blueprint tests: all 12 partition scenarios passed locally. CI includes this test suite.
- CI fixes: full upstream git history and tags for Cleanroom versioning; use non-generic Forge 1.12.2 IShapedRecipe. Run 54 reached Java compilation and reported two instances of that interface error. Fixed in source; full build pending confirmation.

## Version 0.7 — Squad logistics and personalities (experimental)

- Version 0.6 compiled successfully in GitHub Actions run 46045980802. Runtime mod archive is automata-1.2.0-local.dirty.jar; it replaces upstream Automata, not an additional mod. This is a successful compilation, not an in-game compatibility test.
- Six deterministic dialogue styles (practical, cautious, friendly, reserved, optimistic, analytical). The stable AI identity is always visible; the style and actual state are passed to the optional local language model.
- Nearby autonomous agents with neighboring homes can share surplus vanilla prepared food, planks, cobblestone, coal and iron. Maximum eight items per transfer, at most one transfer every 100 ticks for the squad; transfer requires proximity and sight and is suppressed during threats or server stress. Role-specific reserves and deficits keep supplies from being exhausted. No human inventory access. The exchange generates two short AI-labelled lines based on the actual transfer, not a fabricated event.
- Squad scheduling has a soft 1 ms coordination deadline, not a hard CPU cap. CI adds exhaustive pure-policy coverage for 386100 stock/role/resource combinations plus six personality styles. Tests and full build for 0.7 must still be verified.
- Real turret ownership/configuration, Thermal machine/RF networks, clan membership, full autonomous logistics navigation and free-form inter-agent LLM conversations remain unimplemented. The exact pack archive is too large for the connected download limit; do not guess mod-specific APIs.
