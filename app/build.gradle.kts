plugins {
    id("com.android.application")
}

android {
    namespace = "com.nishant.airwidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nishant.airwidget"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
