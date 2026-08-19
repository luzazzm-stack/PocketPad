plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pocketpad.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocketpad.app"
        minSdk = 23 // Android 6.0
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.2"
    }

    signingConfigs {
        create("release") {
            // Checked in on purpose. Without one stable key, every update fails
            // to install over the previous build with INSTALL_FAILED_UPDATE_
            // INCOMPATIBLE and the user has to uninstall, losing their layout
            // and settings. For a private hobby repo that trade is worth it.
            storeFile = rootProject.file("../keystore/pocketpad.jks")
            storePassword = "pocketpad"
            keyAlias = "pocketpad"
            keyPassword = "pocketpad"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Same key as release, so a development build installs straight over
            // a released one instead of demanding an uninstall.
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Backports java.util APIs (AtomicReference.getAndUpdate etc.) to Android 6/7.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

// Keep the in-app help in sync with the single source at docs/manual.html.
val copyManual = tasks.register<Copy>("copyManual") {
    from(rootProject.projectDir.resolve("../docs/manual.html"))
    into(projectDir.resolve("src/main/assets"))
}
tasks.named("preBuild") { dependsOn(copyManual) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") // QR pairing scanner
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    testImplementation("junit:junit:4.13.2")
    // The android.jar used by unit tests stubs org.json — every method throws
    // "not mocked". PadLayout stores its blob as JSON, so its tests need the
    // real implementation on the test classpath.
    testImplementation("org.json:json:20240303")
    // PadConnectionTest runs the real sender against loopback sockets.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
