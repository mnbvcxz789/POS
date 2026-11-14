import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Kunci tanda tangan APK. Pakai kunci yang SAMA setiap build agar aplikasi bisa
// di-update tanpa uninstall (uninstall = data toko di HP ikut terhapus).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "id.digitalservice.pos"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.digitalservice.pos"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "6.0"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
}

// ---------------------------------------------------------------------------
// Unduh library web (ikon, QR, font) SEKALI saat build, lalu ikut dibundel ke
// dalam APK sehingga aplikasi berjalan 100% offline.
// ---------------------------------------------------------------------------
val vendorDir = file("src/main/assets/www/vendor")
val downloadVendor by tasks.registering {
    description = "Mengunduh library web untuk mode offline"
    val files = linkedMapOf(
        "lucide.min.js" to "https://unpkg.com/lucide@0.468.0/dist/umd/lucide.min.js",
        "qrcode.min.js" to "https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js",
        "fonts/PlusJakartaSans.ttf" to "https://github.com/google/fonts/raw/main/ofl/plusjakartasans/PlusJakartaSans%5Bwght%5D.ttf",
        "fonts/JetBrainsMono.ttf" to "https://github.com/google/fonts/raw/main/ofl/jetbrainsmono/JetBrainsMono%5Bwght%5D.ttf"
    )
    val dir = vendorDir
    doLast {
        files.forEach { (name, url) ->
            val target = File(dir, name)
            if (target.exists() && target.length() > 0L) return@forEach
            target.parentFile.mkdirs()
            try {
                var conn = URI(url).toURL().openConnection() as java.net.HttpURLConnection
                var hops = 0
                while (conn.responseCode in 300..399 && hops < 5) {
                    val next = conn.getHeaderField("Location")
                    conn.disconnect()
                    conn = URI(url).resolve(next).toURL().openConnection() as java.net.HttpURLConnection
                    hops++
                }
                conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
                logger.lifecycle("Diunduh: $name (${target.length() / 1024} KB)")
            } catch (e: Exception) {
                target.delete()
                if (name.endsWith(".js")) throw GradleException("Gagal mengunduh $name dari $url: ${e.message}")
                logger.warn("Font $name gagal diunduh (${e.message}); aplikasi memakai font bawaan.")
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(downloadVendor) }
