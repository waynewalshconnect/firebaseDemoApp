import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun localProperty(name: String): String = localProperties.getProperty(name, "")

val appEnvironment = localProperty("app.environment").ifBlank { "CI" }.uppercase()
check(appEnvironment in setOf("CI", "UAT")) {
    "Unsupported app.environment '$appEnvironment'; use CI or UAT"
}
val environmentPrefix = appEnvironment.lowercase()
fun environmentProperty(name: String): String = localProperty("$environmentPrefix.$name")

val requiredEnvironmentProperties = listOf(
    "b2c.clientId",
    "b2c.authority",
    "b2c.redirectUri",
    "b2c.signatureHash",
    "b2c.scopes",
    "tokenExchange.url",
    "userProfile.url",
)
val missingEnvironmentProperties = requiredEnvironmentProperties.filter { environmentProperty(it).isBlank() }
check(missingEnvironmentProperties.isEmpty()) {
    "Missing required $appEnvironment properties in local.properties: " +
        missingEnvironmentProperties.joinToString { "$environmentPrefix.$it" }
}

fun jsonString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val generatedMsalResources = layout.buildDirectory.dir("generated/res/msal")
val generateMsalConfig by tasks.registering {
    inputs.properties(
        mapOf(
            "clientId" to environmentProperty("b2c.clientId"),
            "redirectUri" to environmentProperty("b2c.redirectUri"),
            "authority" to environmentProperty("b2c.authority"),
        ),
    )
    outputs.dir(generatedMsalResources)
    doLast {
        val rawDirectory = generatedMsalResources.get().dir("raw").asFile.apply { mkdirs() }
        rawDirectory.resolve("msal_config.json").writeText(
            """
            {
                "client_id": ${jsonString(environmentProperty("b2c.clientId"))},
                "authorization_user_agent": "DEFAULT",
                "redirect_uri": ${jsonString(environmentProperty("b2c.redirectUri"))},
                "account_mode": "SINGLE",
                "broker_redirect_uri_registered": false,
                "authorities": [{
                    "type": "B2C",
                    "authority_url": ${jsonString(environmentProperty("b2c.authority"))},
                    "default": true
                }]
            }
            """.trimIndent(),
        )
    }
}

android {
    namespace = "com.intouch.testapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.intouch.testapp"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "APP_ENVIRONMENT", jsonString(appEnvironment))
        buildConfigField("String", "B2C_CLIENT_ID", jsonString(environmentProperty("b2c.clientId")))
        buildConfigField("String", "B2C_AUTHORITY", jsonString(environmentProperty("b2c.authority")))
        buildConfigField("String", "B2C_REDIRECT_URI", jsonString(environmentProperty("b2c.redirectUri")))
        buildConfigField("String", "B2C_SCOPES", jsonString(environmentProperty("b2c.scopes")))
        buildConfigField("String", "FIREBASE_OIDC_PROVIDER_ID", jsonString(environmentProperty("firebase.oidcProviderId")))
        buildConfigField("String", "TOKEN_EXCHANGE_URL", jsonString(environmentProperty("tokenExchange.url")))
        buildConfigField("String", "USER_PROFILE_URL", jsonString(environmentProperty("userProfile.url")))
        manifestPlaceholders["msalSignatureHash"] = environmentProperty("b2c.signatureHash")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    sourceSets["main"].res.srcDir(generatedMsalResources)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

tasks.named("preBuild").configure { dependsOn(generateMsalConfig) }

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.microsoft.identity.client:msal:8.5.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}