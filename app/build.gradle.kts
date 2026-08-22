import java.util.Properties

plugins {
    id("com.android.application")
}

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

val releaseKeystoreFile = rootProject.file("ci/skt-common-signing.jks")

android {
    namespace = "com.sktpj.gbemulator"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sktpj.gbemulator"
        minSdk = 23
        targetSdk = 36
        versionCode = versionProperties.getProperty("VERSION_CODE").toInt()
        versionName = versionProperties.getProperty("VERSION_NAME")
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    signingConfigs {
        create("commonStable") {
            storeFile = releaseKeystoreFile
            storePassword = "2048td-release"
            keyAlias = "2048td-release"
            keyPassword = "2048td-release"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("commonStable")
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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
