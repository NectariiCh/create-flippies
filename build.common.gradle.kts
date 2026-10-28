// Build logic both nodes share. Applied by build.forge.gradle.kts and build.neoforge.gradle.kts
// after their loader and maven-publish plugins, so the java plugin is already present.
//
// Node projects live in versions/<node>/, so every repo path is anchored with rootProject.
// A script plugin gets no type-safe accessors, hence the<...>() and "configuration"(...).

import java.util.Date
import java.text.SimpleDateFormat

apply(plugin = "eclipse")
apply(plugin = "idea")

val modId = property("mod_id") as String
val modAuthors = property("mod_authors") as String
val modVersion = property("mod_version") as String
val loader = project.name.substringAfterLast('-')
val sourceSets = the<SourceSetContainer>()

// Jars are create_flippies-<loader>-0.1.0+mc<minecraft_version>.jar
group = property("mod_group_id") as String
version = "$modVersion+mc${property("minecraft_version")}"
the<BasePluginExtension>().archivesName = "$modId-$loader"

repositories {
    maven("https://maven.createmod.net") { name = "Create" }
    maven("https://maven.tterrag.com") { name = "tterrag" }
    maven("https://maven.architectury.dev/") { name = "Architectury" }
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
}

dependencies {
    // Unit tests. The PLC's emulator (content/plc/mcu) is pure Java, so its tests run
    // on the plain JUnit platform with no game or mod loader behind them.
    "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    // Power Grid carries its matrix library (EJML) jar-in-jar inside its own jar, where Gradle
    // cannot see it; the circuit tests solve real Power Grid networks, so they need it put back.
    "testRuntimeOnly"("org.ejml:ejml-simple:0.44.0")
}

// Tests read guest/... and src/main/resources/... relative to the working directory, and
// several skip silently when their input is missing, so run them from the repo root.
tasks.withType<Test>().configureEach {
    workingDir = rootProject.projectDir
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        // the Linux boot (spec 12.11) needs the guest kernel built: run it with linuxTest
        excludeTags("linux")
    }
    // Forge's union file system (securejarhandler), which ClasspathContentTest serves from, needs
    // what Forge's own launcher opens for it
    jvmArgs("--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED")
}

// linuxTest and runHeadlessPc work on the repo root (guest/ and build/), and runHeadlessPc reads
// this terminal, so they exist on one node only: the vcs version.
if (project.name == "1.20.1-forge") {
    // The tests tagged "linux" (spec 12.11): the kernel and initramfs of guest/linux/build.sh and
    // guest/linux/mkinitramfs.sh booted in the headless PC, skipped while they are not built. The
    // run's transcript, screenshot and timings stay in build/linux-boot.
    tasks.register<Test>("linuxTest") {
        group = "verification"
        description = "Boots the guest/linux kernel in the headless PC (spec 12.11)"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform {
            includeTags("linux")
        }
        systemProperty("java.awt.headless", "true")
        testLogging.showStandardStreams = true
        // its inputs are the guest build's outputs, which Gradle does not track
        outputs.upToDateWhen { false }
        // NetinstallDebianTest takes the netinstall floppy from the mod jar (and the OS from
        // guest/packs/out); the jar's resources take the floppy as an input, so a rebuilt floppy
        // rebuilds it first. The tests' default jar path is the old single-project one, so name
        // this node's jar.
        val modJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
        dependsOn(tasks.named("jar"))
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf("-Dflippies.modJar=${modJar.get().asFile.absolutePath}")
        })
    }

    // The headless PC runner (spec 12.9): a PC outside Minecraft, COM1 on this terminal.
    // ./gradlew -q --console=plain :1.20.1-forge:runHeadlessPc --args='--cpu 68040 --floppy boot.img'
    // Options are listed at the top of HeadlessPc.java and by --args='--help'.
    val launcher = the<JavaToolchainService>().launcherFor(the<JavaPluginExtension>().toolchain)
    tasks.register<JavaExec>("runHeadlessPc") {
        group = "application"
        description = "Runs a PC from content/computer without Minecraft (spec 12.9)"
        classpath = sourceSets["test"].runtimeClasspath
        mainClass = "com.nectariich.create_flippies.content.computer.tools.HeadlessPc"
        javaLauncher = launcher
        workingDir = rootProject.projectDir
        standardInput = System.`in`
        jvmArgs("-Djava.awt.headless=true")
    }
}

// guest/netinstall's build writes out/netinstall.img, the one guest output in the jar. The OS image
// and the apt archive are content packs (guest/packs/out/*.flippack, docs/PC_SPEC.md 12.4.3) that a
// server loads, never jar resources.
val netinstallOut = rootProject.file("guest/netinstall/out")

tasks.named<ProcessResources>("processResources") {
    // Each node has only some of these (forge_version_range on Forge, neoforge_version_range on
    // NeoForge), and each excludes the other loader's mods.toml, so expand never sees a token it
    // has no value for.
    val replaceProperties = listOf(
        "minecraft_version", "minecraft_version_range",
        "forge_version", "forge_version_range", "neoforge_version", "neoforge_version_range",
        "loader_version_range",
        "mod_id", "mod_name", "mod_license", "mod_version", "mod_authors", "mod_description",
        "create_version_range", "powergrid_version_range", "hypergraphical_version_range",
    ).mapNotNull { key -> project.findProperty(key)?.let { key to it.toString() } }.toMap()
    inputs.properties(replaceProperties)

    filesMatching(listOf("META-INF/mods.toml", "META-INF/neoforge.mods.toml", "pack.mcmeta")) {
        expand(replaceProperties)
    }

    // The Blockbench exports the breaker models are cut from live beside the models
    // they produce, because that is where they are authored and re-exported. They
    // are not resources: Minecraft resource paths may hold only [a-z0-9/._-], so
    // every one of them is rejected by the loader with an ignorable error, and a
    // dozen of those at every startup is a dozen places for a real error to hide.
    // tools/gen_circuit_breaker.py reads them from the source tree, not from here.
    exclude("assets/*/models/block/CB*-DIN-WO-*.json")

    // The netinstall floppy (docs/PC_SPEC.md 12.5), taken from the guest build's output when it
    // exists. It is a build output, not a source, so it never goes into src/main/resources (git); a
    // build without it has a netinstall floppy that comes out blank.
    into("data/create_flippies/floppies") {
        from(netinstallOut) { include("netinstall.img") }
    }
}

tasks.named<Jar>("jar") {
    manifest.attributes(
        "Specification-Title" to modId,
        "Specification-Vendor" to modAuthors,
        "Specification-Version" to "1",
        "Implementation-Title" to rootProject.name,
        "Implementation-Version" to modVersion,
        "Implementation-Vendor" to modAuthors,
        "Implementation-Timestamp" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(Date()),
    )
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

configure<PublishingExtension> {
    publications.register<MavenPublication>("mavenJava") {
        // the jar that ships is added by each loader script (on Forge the reobfuscated one)
        artifactId = "$modId-$loader"
    }
    repositories { maven(rootProject.file("mcmodsrepo")) }
}
