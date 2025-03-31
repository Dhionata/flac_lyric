plugins {
    kotlin("jvm") version "+"
    application
}

group = "br.com.dhionata"
version = "1.3 Alpha 1"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test:+")
    implementation("org.apache.commons:commons-text:+")
    implementation("com.squareup.okhttp3:okhttp:+")
    implementation("net.jthink:jaudiotagger:+")

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
    jvmToolchain(21)
}

application {
    mainClass.set("MainKt")
}
