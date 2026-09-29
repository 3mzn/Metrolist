plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.metrolist.spotify"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    // Needed for BuildConfig.DEBUG, which SpotifyClient uses to keep full request logging
    // out of release builds. See SPEC_SPOTIFY_CANVAS.md 14.1b.
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.json)
    // Spotify replies in protobuf; CanvasResponse/TokenResponse index their fields with
    // @ProtoNumber, so both the Ktor converter and the runtime are required.
    implementation(libs.ktor.serialization.protobuf)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.kotlin.onetimepassword)
    // Decodes Spotify's brotli responses. Encode is unimplemented in the library and
    // is not needed: Ktor only advertises br when a request asks for it.
    implementation(libs.brotli)
    testImplementation(libs.junit)

    coreLibraryDesugaring(libs.desugaring)
}
