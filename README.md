# Create: Flippies

An addon for [Create: Power Grid](https://modrinth.com/mod/power-grid) on Minecraft **1.20.1 Forge** and **1.21.1 NeoForge**.

- **Mod ID:** `create_flippies`
- **Author:** nectariich
- **Depends on:** [Create](https://modrinth.com/mod/create) 6.0.7+ and Create: Power Grid 0.6.0+ (both mandatory, declared in `mods.toml` and `neoforge.mods.toml`); Create 6.0.9+ on 1.21.1

## Requirements

- Gradle 9.8.2 (the wrapper). The Gradle daemon runs on JDK 21, pinned by
  `gradle/gradle-daemon-jvm.properties`, so no machine path is needed. Gradle finds or downloads
  the JDK 17 and JDK 21 toolchains the two versions compile with.
- The Hypergraphical Lib clone in `hypergraphical-lib/` (see `settings.gradle.kts`).

## Two versions from one source tree

The build makes two jars from the shared `src/`, with [Stonecutter](https://stonecutter.kikugie.dev)
and ModDevGradle (see `docs/NEOFORGE_PORT.md`):

- `1.20.1-forge`: Forge 1.20.1, the version the committed source is written for
- `1.21.1-neoforge`: NeoForge 1.21.1

Each version is a Gradle project, so tasks are run per version:

```sh
./gradlew :1.20.1-forge:build        # jar in versions/1.20.1-forge/build/libs/ (reobfuscated)
./gradlew :1.21.1-neoforge:build     # jar in versions/1.21.1-neoforge/build/libs/
./gradlew build                      # both
./gradlew :1.20.1-forge:runClient    # dev client with Create + Power Grid loaded, in run/
./gradlew :1.21.1-neoforge:runClient # same on NeoForge, in run/1.21.1-neoforge/
./gradlew :1.20.1-forge:runData      # data generators, output to src/generated/resources/
```

The headless PC tools exist on `1.20.1-forge` only:
`./gradlew -q --console=plain :1.20.1-forge:runHeadlessPc --args='--help'` and
`./gradlew :1.20.1-forge:linuxTest`.

To edit the source as another version sees it, switch the active version, which rewrites the
`//? if` blocks in `src/`:

```sh
./gradlew "Set active project to 1.21.1-neoforge"
./gradlew "Reset active project"     # back to 1.20.1-forge
```

Always run "Reset active project" before committing.

The first invocation downloads both loaders, decompiles Minecraft twice and remaps the 1.20.1
mod dependencies, so expect it to take a while.

## Dependency versions

Per-version versions are in `stonecutter.properties.toml`. On 1.20.1 they are the exact set
Create: Power Grid 0.6.0 (1.20.1 Forge) is built against: Create `6.0.7-281`, Ponder `1.0.91`,
Flywheel `1.0.5`, Registrate `MC1.20-1.3.3`, Architectury API `9.2.14`. On 1.21.1 they are
Create `6.0.10-280` with the Ponder, Flywheel and Registrate builds it bundles, Power Grid 0.6.2
and Architectury API `13.0.11`.

Power Grid itself is pulled from the Modrinth maven. Note that `powergrid_version` is a
Modrinth **version id** (`ZHqOfBhb` = 0.6.0 for 1.20.1 Forge, `jCkPHjmP` = 0.6.2 for 1.21.1
NeoForge), not a version number, because a number like `0.6.0` matches builds for both
versions on Modrinth. When bumping it, grab the new id from
<https://modrinth.com/mod/power-grid/versions>.

## Project layout

- `src/main/java/com/nectariich/create_flippies/`: mod code
  - `CreateFlippies.java`: mod entrypoint
  - `registry/`: `DeferredRegister` holders for blocks, items, and the creative tab
- `src/main/resources/META-INF/mods.toml`: Forge 1.20.1 mod metadata, and `neoforge.mods.toml` for
  NeoForge 1.21.1 (tokens expanded from `gradle.properties` and `stonecutter.properties.toml`)
- `src/main/resources/assets/create_flippies/`: models, textures, lang
- `src/generated/resources/`: output of `:1.20.1-forge:runData`, included in the 1.20.1 jar
  automatically (1.21.1 writes to `versions/1.21.1-neoforge/src/generated/resources/`)

## Network modem content packs

The jar holds no OS image or package archive. They come in content packs (`*.flippack`, zip files
with a `pack.json`, see `docs/PC_SPEC.md` 12.4.3) built by `guest/packs/build.sh` into
`guest/packs/out/`. A server loads them from:

- `<world>/flippies_packs/`: this world only, looked up first
- `config/create_flippies/packs/`: every world of the server

Both folders are made at server start. Drop packs in and run `/flippies packs reload` (operators),
or list them in the server config (`[packs] downloads` in `create_flippies-server.toml`, one
`"<file name> <sha256> <url>"` each) to have them fetched at start. `/flippies packs` lists what
is loaded. Each world also serves its folder `<world>/localfilesys/` read only at
`http://localfilesys.com/`.

For the dev runs the server folder is `run/config/create_flippies/packs/` (1.20.1-forge) and
`run/1.21.1-neoforge/config/create_flippies/packs/` (1.21.1-neoforge); symlinks to
`guest/packs/out/*.flippack` work.

The headless runner takes packs with `--pack FILE` (repeatable).
