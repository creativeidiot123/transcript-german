import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.creativeidiot.transcriptgerman"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.creativeidiot.transcriptgerman"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation(files("libs/translate-kit-android-0.1.0.aar"))

    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

val sherpaAarUrl =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
val sherpaAarSha256 =
    "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"

val translateKitAarUrl =
    "https://github.com/creativeidiot123/transcript-german/releases/download/" +
        "deps-translate-kit-0.1.0/translate-kit-android-0.1.0.aar"
val translateKitAarSha256 =
    "e0da38118cd27504a1b6a0037818e7e490f3a4eff1930205e0bb5b774163d60e"

val downloadSherpaAar by tasks.registering {
    val output = layout.projectDirectory.file("libs/sherpa-onnx-1.13.8.aar").asFile
    outputs.file(output)
    outputs.upToDateWhen { false }

    doLast {
        if (output.isFile && sha256(output) == sherpaAarSha256) {
            logger.lifecycle("Verified sherpa-onnx 1.13.8 AAR")
            return@doLast
        }

        output.delete()
        output.parentFile.mkdirs()
        val partial = File(output.parentFile, output.name + ".part")
        partial.delete()

        logger.lifecycle("Downloading sherpa-onnx 1.13.8 AAR")
        try {
            URI(sherpaAarUrl).toURL().openStream().buffered().use { input ->
                partial.outputStream().buffered().use { out -> input.copyTo(out) }
            }
            check(sha256(partial) == sherpaAarSha256) {
                "sherpa-onnx AAR checksum mismatch"
            }
            check(partial.renameTo(output)) {
                "Could not finalize sherpa-onnx AAR"
            }
        } finally {
            partial.delete()
        }
    }
}

val downloadTranslateKitAar by tasks.registering {
    val output = layout.projectDirectory.file("libs/translate-kit-android-0.1.0.aar").asFile
    outputs.file(output)
    outputs.upToDateWhen { false }

    doLast {
        if (output.isFile && sha256(output) == translateKitAarSha256) {
            logger.lifecycle("Verified translate-kit 0.1.0 AAR")
            return@doLast
        }

        output.delete()
        output.parentFile.mkdirs()
        val partial = File(output.parentFile, output.name + ".part")
        partial.delete()

        logger.lifecycle("Downloading translate-kit 0.1.0 AAR")
        try {
            URI(translateKitAarUrl).toURL().openStream().buffered().use { input ->
                partial.outputStream().buffered().use { out -> input.copyTo(out) }
            }
            check(sha256(partial) == translateKitAarSha256) {
                "translate-kit AAR checksum mismatch"
            }
            check(partial.renameTo(output)) {
                "Could not finalize translate-kit AAR"
            }
        } finally {
            partial.delete()
        }
    }
}

tasks.named("preBuild") {
    dependsOn(downloadSherpaAar, downloadTranslateKitAar)
}
