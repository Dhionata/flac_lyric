plugins {
    kotlin("jvm") version "2.4.0-Beta2"
    application
}

group = "br.com.dhionata"
version = "1.5.1"

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
    jvmToolchain(25)
}

application {
    mainClass.set("MainKt")
}
