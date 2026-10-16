# Create: Flippies

An addon for [Create: Power Grid](https://modrinth.com/mod/power-grid) on Minecraft **1.20.1 Forge**.

- **Mod ID:** `create_flippies`
- **Author:** nectariich
- **Depends on:** [Create](https://modrinth.com/mod/create) 6.0.7+ and Create: Power Grid 0.6.0+ (both mandatory, declared in `mods.toml`)

## Requirements

- JDK 17 (Gradle is pinned to `C:/Program Files/Java/jdk-17` via `org.gradle.java.home` in
  `gradle.properties` because the system-default Java 25 is too new for Gradle 8.8 —
  adjust that path if the project moves to another machine)

## Getting started

```powershell
.\gradlew build        # build the mod jar into build/libs/
.\gradlew runClient    # launch a dev client with Create + Power Grid loaded
.\gradlew runServer    # launch a dev server
.\gradlew runData      # run data generators (output goes to src/generated/resources/)
```

The first invocation downloads Forge, decompiles Minecraft, and deobfuscates all mod
dependencies — expect it to take a while.

## Dependency versions

`gradle.properties` pins the exact dependency set that Create: Power Grid 0.6.0 (1.20.1 Forge)
is built against: Create `6.0.7-281`, Ponder `1.0.91`, Flywheel `1.0.5`, Registrate
`MC1.20-1.3.3`, Architectury API `9.2.14`.

Power Grid itself is pulled from the Modrinth maven. Note that `powergrid_version` is a
Modrinth **version id** (`ZHqOfBhb` = 0.6.0 for 1.20.1 Forge), not a version number, because
the number `0.6.0` also matches the 1.21.1 NeoForge build on Modrinth. When bumping it, grab
the new id from <https://modrinth.com/mod/power-grid/versions>.

## Project layout

- `src/main/java/com/nectariich/create_flippies/` — mod code
  - `CreateFlippies.java` — mod entrypoint
  - `registry/` — `DeferredRegister` holders for blocks, items, and the creative tab
- `src/main/resources/META-INF/mods.toml` — mod metadata (tokens expanded from `gradle.properties`)
- `src/main/resources/assets/create_flippies/` — models, textures, lang
- `src/generated/resources/` — output of `runData`, included in the jar automatically
