import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Filled in by GitHub Actions; local builds get "dev".
val buildSha: String = System.getenv("GITHUB_SHA")?.take(7) ?: "dev"
val buildRepo: String = System.getenv("GITHUB_REPOSITORY") ?: ""
val buildRun: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "app.hearth"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.hearth"
        minSdk = 26
        targetSdk = 35
        versionCode = buildRun
        versionName = "0.2.$buildRun"
        buildConfigField("String", "BUILD_SHA", "\"$buildSha\"")
        buildConfigField("String", "UPDATE_REPO", "\"$buildRepo\"")
    }

    signingConfigs {
        // A fixed sideload key (committed on purpose) so every CI build installs over the last one.
        // Replace with your own keystore before publishing anywhere.
        create("sideload") {
            storeFile = rootProject.file("keystore/hearth-sideload.jks")
            storePassword = "hearth-sideload"
            keyAlias = "hearth"
            keyPassword = "hearth-sideload"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    testImplementation("junit:junit:4.13.2")
}
