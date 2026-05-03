plugins {
    kotlin("jvm") version "+"
    application
}

group = "br.com.dhionata"
version = "1.7.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test:+")
    implementation("org.apache.commons:commons-text:+")
    implementation("com.squareup.okhttp3:okhttp:+")
    implementation("net.jthink:jaudiotagger:+")
    implementation("org.jflac:jflac-codec:+")
    implementation("com.github.wendykierp:JTransforms:+")
}

tasks.test {
    useJUnitPlatform()
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
