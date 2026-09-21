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
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
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
