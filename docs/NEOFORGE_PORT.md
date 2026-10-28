# Dual build: Forge 1.20.1 and NeoForge 1.21.1

One source tree builds two jars: the existing Forge 1.20.1 mod and a NeoForge 1.21.1 port. This file records the decisions behind that setup and the conventions code must follow so both versions keep compiling.

## Toolchain

- Gradle 9.8.2. The Gradle daemon runs on JDK 21, pinned by `gradle/gradle-daemon-jvm.properties` (written by `./gradlew updateDaemonJvm --jvm-version=21`), so no machine path is needed in `gradle.properties`.
- Stonecutter 0.9.8 (`dev.kikugie.stonecutter`) preprocesses the shared `src/` per version.
- ModDevGradle 2.0.148: `net.neoforged.moddev.legacyforge` for Forge 1.20.1 (47.4.6), `net.neoforged.moddev` for NeoForge 1.21.1 (21.1.265).
- ForgeGradle 6, MixinGradle and the `createSrgToMcp` task are gone. MDG legacy sets the dev refmap remapping properties itself.
- Kotlin DSL build scripts, one per loader: `build.forge.gradle.kts` and `build.neoforge.gradle.kts`. Logic both need (guest packaging, test wiring) lives in a shared script applied by both.
- Java 17 for the 1.20.1 node, Java 21 for the 1.21.1 node, through toolchains.

## Version nodes

- `1.20.1-forge` (condition version `1.20.1`, constant `forge`)
- `1.21.1-neoforge` (condition version `1.21.1`, constant `neoforge`)
- The same node names are used in hypergraphical-lib.
- `vcsVersion` is `1.20.1-forge`: the source committed to git is the Forge 1.20.1 form. Run "Reset active project" before committing.
- Node project dirs are `versions/<node>/`. Shared build logic anchors every path with `rootProject.file(...)`. Test tasks and `runHeadlessPc` use `workingDir = rootProject.projectDir`.
- Each node has its own run folder. The 1.20.1 node keeps `run/` so existing worlds and configs still work; 1.21.1 uses `run/1.21.1-neoforge`.

## Dependencies

1.20.1 (unchanged versions, now through MDG legacy `mod*` configurations in place of `fg.deobf`):
- Forge 47.4.6, Create 6.0.7-281 slim, Ponder 1.0.91, Flywheel 1.0.5, Registrate MC1.20-1.3.3
- Power Grid 0.6.0 (Modrinth `ZHqOfBhb`), Architectury 9.2.14, Embeddium 0.3.31+mc1.20.1 (runtime only)

1.21.1:
- NeoForge 21.1.265
- Create `create-1.21.1:6.0.10-280:slim` (the 6.0.10 release build)
- Ponder `ponder-neoforge:1.0.82+mc1.21.1`, Flywheel `flywheel-neoforge-1.21.1:1.0.6`, Registrate `MC1.21-1.3.0+67` (from `https://maven.ithundxr.dev/snapshots`). These are the versions Create 6.0.10 bundles.
- Power Grid 0.6.2 (Modrinth `jCkPHjmP`), Architectury `architectury-neoforge:13.0.11`
- Sodium `mc1.21.1-0.6.13-neoforge` (Modrinth `Pb3OXVqC`) at runtime. Sodium 0.8 jars wrap the real mod in a nested jar, which the dev classpath loader skips. Embeddium for 1.21.1 was abandoned in January 2025 and Iris rejects it.

Hypergraphical Lib is an included build, also a Stonecutter project with the same two nodes. Flippies maps one coordinate per loader to each node with `dependencySubstitution`. It is consumed with plain `implementation`, never `mod*`.

## Source conventions

Prefer, in this order:
1. Loader-neutral code that compiles on both (for example `Supplier<T>` registry fields, `Registries.X` keys, `BuiltInRegistries` lookups).
2. A small compat class in `com.nectariich.create_flippies.compat` whose body is version-conditioned, so call sites never branch. Examples: `Ids` (ResourceLocation), `ClientOnly` (replaces `DistExecutor`), `StackData` (item NBT vs `DataComponents.CUSTOM_DATA`), `SavedDataCompat`, packet registration and sending.
3. Stonecutter string replacements, only for exact, textually uniform strings (imports, a few override signatures). Never broad prefixes.
4. Inline `//? if` blocks for one-off structural differences.

Stonecutter condition syntax, as it sits in `src/` with 1.20.1 active:
```java
//? if forge {
import net.minecraftforge.fml.common.Mod;
//?} else {
/*import net.neoforged.fml.common.Mod;
*///?}
```

Each branch of a block must hold code. A branch of only `//` comments does not survive switching the active version and back (Stonecutter rewraps it differently each way), so a note about the other loader goes above the block instead.

Item NBT maps to `DataComponents.CUSTOM_DATA` on 1.21.1, keeping the same tag shape as 1.20.1, so old saves keep their data.

## Resources

- `src/main/resources` stays in the 1.20.1 layout. The 1.21.1 node renames paths in `processResources`: `loot_tables` to `loot_table`, `tags/items` to `tags/item`, `tags/blocks` to `tags/block`, and `data/forge/tags/items/wires.json` to `data/c/tags/item/wires.json`.
- `META-INF/mods.toml` is Forge only. `META-INF/neoforge.mods.toml` is NeoForge only (`type="required"` instead of `mandatory`, mixins declared with `[[mixins]]`). Each node excludes the other.
- The access transformer is Forge only (SRG names). NeoForge and Power Grid 1.21.1 already make `getEntities()` public.
- `pack.mcmeta` is excluded from the 1.21.1 jar.
- The 1.21.1 node strips the `refmap` key from `create_flippies.mixins.json`. The 1.20.1 jar gets a `MixinConfigs` manifest attribute, which MixinGradle used to add.

## Known behaviour changes to check in game on 1.21.1

- Power Grid's `CurveParameters` now takes the wire length instead of sag coefficients. Flippies computes the length with the formula 1.20.1 used internally, so sag looks the same.
- `RenderLevelStageEvent.getPoseStack()` is null or identity on several stages. Rendering uses `getModelViewMatrix()` instead.
- `TextureAtlasSprite.getU/getV` take 0..1 instead of 0..16.
- `Screen.render` draws the background itself. Screens no longer call `renderBackground` before drawing.
- NeoForge sets camera roll inside `Camera.setup`. `CameraMixin` keeps it.
- `Block.use` is split into `useItemOn` and `useWithoutItem`.
