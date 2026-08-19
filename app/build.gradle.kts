import java.util.Properties

plugins {
    id("com.android.application") version "8.2.2"
    id("org.jetbrains.kotlin.android") version "1.9.22"
    id("com.google.devtools.ksp") version "1.9.22-1.0.17"
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) load(localFile.inputStream())
}

android {
    namespace = "com.example.telegrambackup"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.telegrambackup"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        val apiId = localProperties.getProperty("TELEGRAM_API_ID") ?: "0"
        val apiHash = localProperties.getProperty("TELEGRAM_API_HASH") ?: ""
        buildConfigField("int", "TELEGRAM_API_ID", apiId)
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$apiHash\"")

        ndk { abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a")) }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
}
