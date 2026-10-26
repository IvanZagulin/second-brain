import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Version comes from the release tag in CI (-PappVersion=1.2.3); versionCode = major*10000 + minor*100 + patch.
val appVersion: String = (findProperty("appVersion") as String?)?.removePrefix("v") ?: "1.0.0"
val versionParts = appVersion.split(".").map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
val appVersionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]

// Signing: keystore.properties next to this file locally, or SIGNING_* environment variables in CI.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = signingProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "ru.secondbrain.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.secondbrain.app"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://brain.quasar-x.ru\"")
        buildConfigField("String", "GITHUB_REPO", "\"IvanZagulin/second-brain\"")
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "SIGNING_STORE_FILE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
        debug {
            // Same package as release: Firebase is configured for ru.secondbrain.app only.
            versionNameSuffix = "-debug"
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
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
    }

    testOptions.unitTests.isReturnDefaultValues = true
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.work)
    implementation(libs.androidx.datastore)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.biometric)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.health.connect)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.code.scanner)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
}
