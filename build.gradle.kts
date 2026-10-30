plugins {
    id("java")
}

group = "com.example"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.16-alpha")
    // Server internals, for the one thing the API can't do: showing a mob's
    // glowing outline to a single player (WardenGlow). 26.x server code ships
    // unobfuscated, so this compiles straight against the local server jar and
    // its libraries - no paperweight, which this machine couldn't download
    // anyway. Must point at the jar for the same Paper build as paper-api.
    compileOnly(files("run/versions/26.3/paper-26.3.jar"))
    // Minus paper-api: the declaration above is the API, and run/libraries
    // still holds copies from every earlier server version (1.21.11, 26.1.2,
    // 26.2), which shadow it with older classes.
    compileOnly(fileTree("run/libraries") {
        include("**/*.jar")
        exclude("io/papermc/paper/paper-api/**")
    })
}

// A source set for checks that run against the built plugin classes. There is
// no test framework on the classpath (this machine can't reach Maven Central,
// so no JUnit), so these are main methods with their own assertions, wired to
// Gradle tasks. paper-api is compileOnly for the plugin but has to be on the
// runtime classpath here, since loading a listener class resolves the Bukkit
// types it mentions.
sourceSets {
    create("harness") {
        compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath
        runtimeClasspath += output + sourceSets["main"].output + sourceSets["main"].compileClasspath
    }
}

tasks.register<JavaExec>("unbreakingCheck") {
    group = "verification"
    description = "Checks the Unbreaking damage scaling lands on its intended curve."
    classpath = sourceSets["harness"].runtimeClasspath
    mainClass.set("com.example.vanillakfc.listeners.UnbreakingCurveCheck")
    // Gradle itself runs on an older JDK than the toolchain compiles against,
    // so the launcher has to be pinned or the classes won't load.
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
}

tasks.register<JavaExec>("mobXpCheck") {
    group = "verification"
    description = "Checks the mob XP boost averages exactly 1.5x after rounding."
    classpath = sourceSets["harness"].runtimeClasspath
    mainClass.set("com.example.vanillakfc.listeners.MobXpCheck")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

tasks.compileJava {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:deprecation")
}

// Copies the built jar straight into a local test server's plugins folder.
tasks.register<Copy>("deployToTestServer") {
    dependsOn(tasks.jar)
    from(tasks.jar.get().archiveFile)
    into("${rootDir}/run/plugins")
}
