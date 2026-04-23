plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )
        pluginVerifier()
        zipSigner()
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

val ggRootDir = file("..")
val binaryName = if (System.getProperty("os.name", "").lowercase().contains("windows")) "gg.exe" else "gg"

// build the Svelte/TypeScript frontend (must run before cargo so assets get embedded)
// find the highest nvm node major version that satisfies vite's ≥20 requirement,
// so the task works even when the shell's default node is too old
fun nvmNodeBinDir(): String? {
    val nvmDir = file("${System.getProperty("user.home")}/.nvm/versions/node")
    if (!nvmDir.isDirectory) return null
    return nvmDir.listFiles()
        ?.mapNotNull { dir ->
            val major = dir.name.removePrefix("v").substringBefore(".").toIntOrNull()
            if (major != null && major >= 20) dir to major else null
        }
        ?.maxByOrNull { (_, major) -> major }
        ?.first
        ?.resolve("bin")
        ?.takeIf { it.isDirectory }
        ?.absolutePath
}

val buildGGFrontend by tasks.registering(Exec::class) {
    group = "gg"
    description = "Build the GG Svelte/TypeScript frontend"
    workingDir = ggRootDir
    // call vite directly to skip the prebuild (svelte-check) lifecycle hook
    commandLine("npx", "vite", "build")
    // prepend a nvm-managed node ≥20 if the system node is older
    nvmNodeBinDir()?.let { binDir ->
        environment("PATH", "$binDir:${System.getenv("PATH")}")
    }
}

// build the Rust binary (embeds the pre-built frontend assets)
val buildGGBinary by tasks.registering(Exec::class) {
    group = "gg"
    description = "Build the GG Rust binary with cargo --release"
    dependsOn(buildGGFrontend)
    workingDir = ggRootDir
    commandLine("cargo", "build", "--release")
}

// copy the binary into the JAR resources so it ships with the plugin
val copyGGBinary by tasks.registering(Copy::class) {
    group = "gg"
    description = "Copy the built GG binary into plugin resources"
    dependsOn(buildGGBinary)
    from(file("../target/release/$binaryName"))
    into(file("src/main/resources/binaries"))
}

tasks {
    processResources {
        dependsOn(copyGGBinary)
    }
    wrapper {
        gradleVersion = "8.14"
    }
}
