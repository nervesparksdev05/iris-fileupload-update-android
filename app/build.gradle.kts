plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nervesparks.iris"
    compileSdk = 35

    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.nervesparks.iris"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions { jvmTarget = "1.8" }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.1" }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"

            // Common POI / XML duplicates
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/*.kotlin_module"

            // Extra safe excludes
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/*.version"
            excludes += "META-INF/versions/**"
            excludes += "module-info.class"
        }
    }
}



dependencies {
    // ✅ Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // ✅ Lifecycle + Activity
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.activity:activity-compose:1.9.3")

    // ✅ Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material:1.8.0-alpha07")
    implementation("androidx.compose.foundation:foundation-layout-android:1.7.6")
    implementation("androidx.compose.ui:ui-text-google-fonts:1.7.6")
    implementation("androidx.compose.material:material-icons-extended")

    // ✅ Navigation
    implementation("androidx.navigation:navigation-runtime-ktx:2.8.5")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // ✅ Misc UI
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // ✅ Network / storage
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // ✅ llama
    implementation(project(":llama"))

    // ✅ PDF extraction
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // =========================================================
    // ✅ DOCX / XLSX extraction (Apache POI) — FIXED
    // =========================================================

    // Prefer newer POI (better Android + dependency alignment)
    implementation("org.apache.poi:poi:5.5.1")
    implementation("org.apache.poi:poi-ooxml:5.5.1")

    // ✅ CRITICAL: required for XWPFDocument / OOXML parsing
    implementation("org.apache.xmlbeans:xmlbeans:5.3.0")

    // POI runtime deps (pin them to avoid Gradle choosing older ones)
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("commons-io:commons-io:2.21.0")
    implementation("org.apache.commons:commons-collections4:4.5.0")
    implementation("com.github.virtuald:curvesapi:1.08")

    // ✅ Java 8+ desugaring
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // =========================================================
    // ✅ On-device RAG dependencies
    // =========================================================


    // ✅ WorkManager (background indexing)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // ✅ Tests
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

}
