import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// CI-stable DEBUG identity only, never a release signing path: when the
// -PoffleashDebugKeystore property is present (CI passes the cached
// ci-debug/debug.keystore), debug builds sign with that keystore so the
// hunt APK keeps one signer across runs. When the property is absent,
// this is inert and builds behave exactly as stock (auto debug keystore).
val ciDebugKeystore: String? = providers.gradleProperty("offleashDebugKeystore").orNull

// The sign-in client pair is BUILD-SUPPLIED, never source-resident
// (0.8.6): two supply lines, Gradle properties first (the release
// workflow passes the pair from its Actions secrets this way),
// then the git-ignored `offleash-oauth.properties` at the repo
// root for local builds. Absent both, the fields are empty and
// the app runs unconfigured — sign-in unavailable, everything
// else working — until the owner sets up their own key in the
// app. The values land only in the generated BuildConfig.
val oauthPropsFile = rootProject.file("offleash-oauth.properties")
val oauthProps = Properties()
if (oauthPropsFile.isFile) {
    oauthPropsFile.inputStream().use { oauthProps.load(it) }
}

fun oauthSupply(propertyName: String, fileKey: String): String =
    providers.gradleProperty(propertyName).orNull
        ?: oauthProps.getProperty(fileKey)
        ?: ""

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val oauthClientId = oauthSupply("offleashOauthClientId", "clientId")
val oauthClientSecret = oauthSupply("offleashOauthClientSecret", "clientSecret")
val oauthClientKind = oauthSupply("offleashOauthClientKind", "clientKind")

android {
    namespace = "dev.cyberdogs.offleash"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.cyberdogs.offleash"
        minSdk = 26
        targetSdk = 35
        versionCode = 23
        versionName = "0.8.6-sweep"
        buildConfigField("String", "OAUTH_CLIENT_ID", buildConfigString(oauthClientId))
        buildConfigField("String", "OAUTH_CLIENT_SECRET", buildConfigString(oauthClientSecret))
        buildConfigField("String", "OAUTH_CLIENT_KIND", buildConfigString(oauthClientKind))
    }

    flavorDimensions += "edition"

    productFlavors {
        create("standard") {
            dimension = "edition"
            applicationId = "dev.cyberdogs.offleash"
            buildConfigField("String", "EDITION_LABEL", "\"\"")
        }
        create("hunt") {
            dimension = "edition"
            applicationId = "dev.cyberdogs.offleash.hunt"
            versionNameSuffix = "-hunt1"
            buildConfigField("String", "EDITION_LABEL", "\"HUNT BUILD\"")
        }
    }

    signingConfigs {
        if (ciDebugKeystore != null) {
            create("ciDebug") {
                storeFile = file(ciDebugKeystore)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            if (ciDebugKeystore != null) {
                signingConfig = signingConfigs.getByName("ciDebug")
            }
        }
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-datasource:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
}
