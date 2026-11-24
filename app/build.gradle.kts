plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val releaseCredentials = listOf("KEYSTORE_FILE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
    .associateWith { providers.gradleProperty(it).orElse(providers.environmentVariable(it)).orNull }
val hasReleaseCredentials = releaseCredentials.values.all { !it.isNullOrBlank() }
require(hasReleaseCredentials || releaseCredentials.values.all { it.isNullOrBlank() }) {
    "Configure all four release signing properties, or leave all four unset for an unsigned release."
}

android {
    namespace = "me.animeheaven.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "me.animeheaven.tv"
        minSdk = 24
        targetSdk = 35
        versionCode = providers.gradleProperty("versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("versionName").getOrElse("1.0.0")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Strip ~80 unused AndroidX/Material3 translation tables (runtime anime text is unaffected)
        resourceConfigurations += listOf("en")
    }

    if (hasReleaseCredentials) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(releaseCredentials.getValue("KEYSTORE_FILE")!!)
            storePassword = releaseCredentials.getValue("KEYSTORE_PASSWORD")
            keyAlias = releaseCredentials.getValue("KEY_ALIAS")
            keyPassword = releaseCredentials.getValue("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("sideload") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".sideload"
            matchingFallbacks += "release"
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/androidx/**/LICENSE.txt",
                "META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

composeCompiler {
    stabilityConfigurationFile = layout.projectDirectory.file("compose_stability.conf")
    featureFlags.add(org.jetbrains.kotlin.compose.compiler.gradle.ComposeFeatureFlag.OptimizeNonSkippingGroups)
    if (project.findProperty("composeCompilerReports") == "true") {
        val reportsDir = layout.buildDirectory.dir("compose_compiler")
        reportsDestination.set(reportsDir)
        metricsDestination.set(reportsDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)

    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.profileinstaller)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
