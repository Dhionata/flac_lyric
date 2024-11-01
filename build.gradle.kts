plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "br.com.dhionata"
version = "1.2"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.20")
    implementation("org.apache.commons:commons-text:1.12.0")
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
