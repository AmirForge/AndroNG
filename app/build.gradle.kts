import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val controlPanelUrl = providers.gradleProperty("controlPanelUrl")
    .orElse("https://v2meda-control.andro-meda.workers.dev").get().trim().trimEnd('/')
if (controlPanelUrl.isNotEmpty()) {
    val endpoint = URI(controlPanelUrl)
    require(endpoint.scheme == "https" && endpoint.host != null && endpoint.userInfo == null &&
        endpoint.rawQuery == null && endpoint.rawFragment == null && endpoint.rawPath.isNullOrEmpty()) {
        "controlPanelUrl must be the HTTPS origin of the Cloudflare Worker"
    }
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.v2meda.android"
        minSdk = 28
        targetSdk = 36
        versionCode = 774
        versionName = "1.5.0"
        ndk { abiFilters.add("arm64-v8a") }

        buildConfigField("String", "DISTRIBUTION", "\"v2meda\"")
        buildConfigField("String", "CONTROL_PANEL_URL", "\"$controlPanelUrl\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        generateLocaleConfig = true
        ignoreAssetsPatterns.addAll(listOf("psiphon_servers.dat", "psiphon_servers.stamp"))
        localeFilters += listOf(
            "en",
            "zh-rCN",
            "zh-rTW",
            "vi",
            "ru",
            "fa",
            "ar",
            "az",
            "bn",
            "bqi-rIR"
        )
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += setOf("**/libpsiphon-tunnel-core.so", "**/liblyrebird.so")
        }
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.compose)
    implementation(libs.core)

    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    implementation(libs.reorderable)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
