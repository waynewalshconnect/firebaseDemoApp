plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Derives the MSAL redirect host/path from the same env file used to configure
// the Dart app (env/env.json), so no environment value is duplicated or hardcoded.
fun percentDecode(value: String): String {
    val bytes = mutableListOf<Byte>()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%' && i + 2 < value.length) {
            bytes.add(value.substring(i + 1, i + 3).toInt(16).toByte())
            i += 3
        } else {
            bytes.add(c.code.toByte())
            i += 1
        }
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}

val envFile = rootProject.file("../env/env.json")
val redirectUri: String = if (envFile.exists()) {
    val parsed = groovy.json.JsonSlurper().parse(envFile) as Map<*, *>
    (parsed["b2c.redirectUri"] as? String).orEmpty()
} else {
    ""
}
val redirectUriMatch = Regex("^msauth://([^/]+)(/.+)$").find(redirectUri)
val redirectHost = redirectUriMatch?.groupValues?.get(1).orEmpty()
val redirectPath = redirectUriMatch?.groupValues?.get(2)?.let { percentDecode(it) }.orEmpty()

android {
    namespace = "com.intouch.testapp"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.intouch.testapp"
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        manifestPlaceholders["msalRedirectHost"] = redirectHost
        manifestPlaceholders["msalRedirectPath"] = redirectPath
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}

