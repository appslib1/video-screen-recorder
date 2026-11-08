plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.screen_recorder.screen_video_recorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.screen_recorder.screen_video_recorder"
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            resValue("string", "app_id", "ca-app-pub-3940256099942544~3347511713")
            resValue("string", "banner", "ca-app-pub-3940256099942544/6300978111")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            resValue("string", "app_id", "ca-app-pub-5592679724436623~6188455580")
            resValue("string", "banner", "ca-app-pub-5592679724436623/9665414595")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        resValues = true
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.glide)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.preference)
    implementation(libs.documentfile)
    implementation(libs.play.services.ads)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
