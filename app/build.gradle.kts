plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNr = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "de.rico.akkumonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.rico.akkumonitor"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNr
        versionName = "1.0.$buildNr"
    }

    // Fester Schlüssel im Repo, damit sich jede neue Version über die alte installieren lässt.
    signingConfigs {
        create("fixed") {
            storeFile = file("akkumonitor.jks")
            storePassword = "akkumonitor"
            keyAlias = "akkumonitor"
            keyPassword = "akkumonitor"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
