plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    id("application")
    id("com.diffplug.spotless") version "7.2.1"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(24)
}
application {
    mainClass.set("com.example.miniweb.app.MainKt")
}
spotless {
    kotlin {
        // ktlint のバージョンはプロジェクト都合で変更可
        // （例: 1.3.x 系。迷ったら 1.2.1〜1.3.x あたりが無難）
        ktlint("1.3.1")
        target("src/**/*.kt")
    }
    kotlinGradle {
        ktlint("1.3.1")
        target("*.gradle.kts")
    }
}
