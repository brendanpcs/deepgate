# Deepgate

Server-side teleportation for Minecraft **26.2** (Fabric): TPA, spawn, beacon homes, `/back`, and
reinforced-deepslate portal networks.

The server needs the mod. **Vanilla clients need nothing** — no mod, no resource pack. Every
interface is a native Minecraft dialog, vanilla text, vanilla items and vanilla particles.

## Build requirements

| Requirement | Version | Why |
| --- | --- | --- |
| JDK | **25 (GA)** | Minecraft 26.1+ requires Java 25, and so does Fabric Loom 1.17 |
| Gradle | 9.5.1 | Supplied by the committed wrapper |
| IntelliJ IDEA (optional) | 2025.3+ | Required for unobfuscated 26.x projects; CLI builds do not care |

> **Note:** a Project Loom early-access build (`~/.jdks/loom-ea-25-loom+1-11`) reports itself as
> Java 25, but it is a JDK research build, not a general-purpose runtime. Install Temurin JDK 25 GA.

```bash
./gradlew build
```

Minecraft 26.1 onwards ships unobfuscated, so there is no `mappings` line and dependencies use
`implementation` rather than `modImplementation`. Versions in `gradle.properties` are pinned
deliberately; do not float them without re-running the test suite.

## Testing

Three automated layers, plus a manual pass. Each acceptance criterion goes to the layer that can
actually test it, rather than being forced into an awkward game test.

```bash
./gradlew test          # pure JUnit, no Minecraft needed  (81 tests)
./gradlew runGametest   # world-dependent behaviour        (20 tests)
./gradlew runServer     # boot a dev server by hand
```

`runGametest` writes a JUnit report to `build/junit.xml`. The dev server and game test server
both need `eula=true` (in `run/eula.txt` and `build/gametest/eula.txt` respectively).

The pure-JUnit layer covers pricing, the experience curve, cross-dimension distance, failure
precedence, the transaction ledger, re-approval policy and dialog nonces. Those classes deliberately
carry **no Minecraft imports**, which keeps them fast and independently verifiable:

```bash
# every Minecraft-free source, for reference
grep -rL "import net.minecraft" src/main/java --include='*.java'
```

## Status

| Milestone | Scope | State |
| --- | --- | --- |
| M1 | Gamerules, pricing, experience, combat, `/back`, dialogs, TPA | **Done** - server boots, mixin applies, playtested |
| M2 | `/spawn` and beacon homes | **Done** - builds, 81 unit + 20 game tests green |
| M3 | Deepgates, normal network | Not started |
| M4 | P2P pairs | Not started |

## Layout

```
core/     pricing, experience, combat, teleport transactions  (mostly Minecraft-free)
home/     beacon detection, home records, name validation
spawn/    bed, respawn anchor and world spawn
state/    the one persistent record (homes, portals, schema version)
dialog/   native dialog builders, nonces, click routing
request/  /tpa and /tpahere
command/  command registration
ui/       screens and chat feedback
mixin/    the four permitted mixins
```

Mixins are capped at four, each because no Fabric event or vanilla hook covers the need:
dialog click delivery, reinforced-deepslate piston mobility, piston gate invalidation, and beacon
level access.
