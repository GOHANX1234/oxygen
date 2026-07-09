plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.oxygens.core.loader"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core-storage"))
    // NOTE: must NOT depend on :core-virtual — core-virtual depends on core-loader
    // (CloneManager uses GuestApkParser). Parsed-package model types therefore live
    // here, not in core-virtual, to avoid a module cycle.
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
