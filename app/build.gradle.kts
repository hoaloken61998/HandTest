plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.myapplication"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 24
        //noinspection OldTargetApi
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.core.ktx) // Assuming core-ktx is already in libs
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // CameraX
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.camera.extensions)
    implementation(libs.camera.video)

    // Navigation
    implementation(libs.navigation.fragmentKtx)
    implementation(libs.navigation.uiKtx)

    // Fragment
    implementation(libs.fragment.ktx)

    // WindowManager
    implementation(libs.androidx.window)

    // Unit testing
    testImplementation(libs.junit)

    // Instrumented testing
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    // MediaPipe Library
    implementation(libs.mediapipe.tasksVision)
    implementation(libs.mediapipe.tasksCore)

    // Filament dependencies
    implementation(libs.gltfio.android)
    implementation(libs.filament.utils) // Corrected to use direct alias
    implementation(libs.filament.android) // Corrected to use direct alias
}