plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mohnishraj.aether"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mohnishraj.aether"
        minSdk = 26
        targetSdk = 36
        versionCode = 1800
        versionName = "0.18.0-m18"

    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        allWarningsAsErrors = true
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        // API 37 requires an AGP 9 migration. M12 intentionally remains on the
        // proven API 36 toolchain while every actionable lint issue remains enforced.
        disable += "OldTargetApi"
    }
}

dependencies {
    implementation(project(":engine-core"))
    implementation(project(":engine-platform-android"))
    implementation("androidx.activity:activity-ktx:1.13.0")
}
