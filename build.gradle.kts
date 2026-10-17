plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "audiogear"
version = "1.2.1"

repositories {
    mavenCentral()
}

javafx {
    version = "27"
    modules = listOf("javafx.controls", "javafx.swing")
}

dependencies {
    implementation("io.github.mkpaz:atlantafx-base:3.0.0")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("org.jdbi:jdbi3-core:3.55.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    runtimeOnly("org.slf4j:slf4j-jdk14:2.0.17")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:unchecked"))
}

application {
    mainClass = "audiogear.app.Launcher"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "--enable-native-access=javafx.graphics")
}

// AppVersion reads this when the app runs outside the jpackage launcher
tasks.jar {
    manifest { attributes("Implementation-Version" to project.version) }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Lets `gradlew run -Daudiogear.home=<dir>` use a separate data folder (handy for trying things out).
tasks.named<JavaExec>("run") {
    System.getProperty("audiogear.home")?.let { systemProperty("audiogear.home", it) }
}

// Dev tool: renders every screen to PNG from a throwaway data folder. gradlew screenshots -Pout=<dir> -Pdata=<dir>
tasks.register<JavaExec>("screenshots") {
    group = "verification"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "audiogear.tour.ScreenshotTour"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    args(project.findProperty("data") ?: layout.buildDirectory.dir("tour-data").get().asFile.path,
         project.findProperty("out") ?: layout.buildDirectory.dir("screenshots").get().asFile.path,
         project.findProperty("mode") ?: "seed")
}

// The full catalog that ships inside the installer (build/snapshot/seed/catalog.db, from gradlew buildSnapshot). The
// packaging tasks include it when it exists, checked when they run so `gradlew buildSnapshot packageInstaller` works.
val seedDir = layout.buildDirectory.dir("snapshot/seed")
fun Exec.includeSeedIfBuilt() {
    val seed = seedDir.get().asFile
    if (seed.resolve("catalog.db").exists()) args("--app-content", seed.path)
}

// Windows app folder with a launcher .exe and its own Java runtime: build/jpackage/Audio Gear Aggregator/
// (an .msi installer would additionally need the WiX toolset).
tasks.register<Exec>("packageApp") {
    group = "distribution"
    dependsOn("installDist")
    val lib = layout.buildDirectory.dir("install/${project.name}/lib").get().asFile
    val dest = layout.buildDirectory.dir("jpackage").get().asFile
    doFirst { delete(dest); includeSeedIfBuilt() }
    commandLine("jpackage", "--type", "app-image", "--name", "Audio Gear Aggregator", "--app-version", "${project.version}",
        "--input", lib.path, "--main-jar", "${project.name}-${project.version}.jar", "--main-class", "audiogear.app.Launcher",
        "--java-options", "--enable-native-access=ALL-UNNAMED", "--dest", dest.path)
}

// Windows installer: build/installer/Audio Gear Aggregator-<version>.msi. Installs per user (no admin rights) with Start
// menu and desktop shortcuts; a newer version replaces the old one (fixed upgrade UUID) and leaves the data folder
// alone. Needs the WiX toolset on PATH (CI installs WiX 5).
tasks.register<Exec>("packageInstaller") {
    group = "distribution"
    dependsOn("installDist")
    val lib = layout.buildDirectory.dir("install/${project.name}/lib").get().asFile
    val dest = layout.buildDirectory.dir("installer").get().asFile
    doFirst { delete(dest); includeSeedIfBuilt() }
    commandLine("jpackage", "--type", "msi", "--name", "Audio Gear Aggregator", "--app-version", "${project.version}",
        "--vendor", "billbishere", "--description", "Catalog, compare and analyse headphones, IEMs, amps and DACs",
        "--input", lib.path, "--main-jar", "${project.name}-${project.version}.jar", "--main-class", "audiogear.app.Launcher",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--win-per-user-install", "--win-dir-chooser", "--win-menu", "--win-menu-group", "Audio Gear Aggregator", "--win-shortcut",
        "--win-upgrade-uuid", "c5d8879f-d25b-4ac8-a32e-69288cfd835b", "--dest", dest.path)
}

// Builds the installer's full catalog into build/snapshot/seed/catalog.db from AutoEq and the data branch.
// Set GITHUB_TOKEN to avoid GitHub's 60-requests-an-hour limit. Fails below -PminGear (default 3000) items.
// gradlew buildSnapshot [-PdataBase=<url>] [-PminGear=<n>]
tasks.register<JavaExec>("buildSnapshot") {
    group = "distribution"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "audiogear.tools.BuildSnapshot"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    args(seedDir.get().file("catalog.db").asFile.path,
        (project.findProperty("dataBase") ?: "https://raw.githubusercontent.com/billbishere/audio-gear-aggregator/data/").toString(),
        (project.findProperty("minGear") ?: "3000").toString())
}

// The daily GitHub Actions job: gradlew dailyFeed -Pdata=<data-branch checkout> [-Pdate=yyyy-MM-dd] [-PlatestVersion=V -PlatestUrl=U]
tasks.register<JavaExec>("dailyFeed") {
    group = "application"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "audiogear.tools.DailyFeed"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val a = mutableListOf("--data", (project.findProperty("data") ?: "build/data").toString(), "--sources", "feeds/sources.json",
        "--research", "research", "--exclude", "config/snapshot-exclude-sources.txt")
    project.findProperty("date")?.let { a += listOf("--date", it.toString()) }
    project.findProperty("latestVersion")?.let { a += listOf("--latest-version", it.toString()) }
    project.findProperty("latestUrl")?.let { a += listOf("--latest-url", it.toString()) }
    args(a)
}

// Research file of every amp and DAC Audio Science Review has measured: gradlew asrAmpsDacs [-Pout=research/<file>.json]
tasks.register<JavaExec>("asrAmpsDacs") {
    group = "application"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "audiogear.tools.AsrAmpsDacs"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    args((project.findProperty("out") ?: "research/asr-amps-dacs.json").toString())
}

// Loads a research bundle into the real catalog (backs up first): gradlew importResearch -Pfile=research/<file>.json
tasks.register<JavaExec>("importResearch") {
    group = "application"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "audiogear.tools.ImportResearch"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    System.getProperty("audiogear.home")?.let { systemProperty("audiogear.home", it) }
    args(project.findProperty("file") ?: "research/2026-10-most-discussed.json")
}
