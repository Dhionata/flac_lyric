plugins {
    kotlin("jvm") version "+"
    application
    id("org.jetbrains.dokka") version "+"
}

group = "br.com.dhionata"
version = findProperty("version") as? String ?: "1.7.0"

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test:+")
    implementation("org.apache.commons:commons-text:+")
    implementation("com.squareup.okhttp3:okhttp:+")
    implementation("net.jthink:jaudiotagger:+")
    implementation("org.jflac:jflac-codec:+")
    implementation("com.github.wendykierp:JTransforms:+")
    implementation("com.google.code.gson:gson:+")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("flac.lyric.log.dir", layout.buildDirectory.dir("test-runs/logs").get().asFile.absolutePath)
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "MainKt"
    }

    configurations["runtimeClasspath"].forEach { file: File ->
        from(zipTree(file.absoluteFile))
    }
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("MainKt")
}
