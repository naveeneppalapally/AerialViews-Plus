import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.android.junit5)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.firebase.perf)
    alias(libs.plugins.kotlinter.gradle)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.neilturner.aerialviews"
    compileSdk = 37

    var betaVersion = ""
    val keyProps = loadProperties("secrets.properties")
    val openWeatherDebug =
        sequenceOf(
            keyProps.getProperty("openWeatherDebug"),
            System.getenv("OPEN_WEATHER_DEBUG"),
            keyProps.getProperty("openWeather"),
            System.getenv("OPEN_WEATHER"),
        ).firstOrNull { !it.isNullOrBlank() } ?: ""
    val openWeatherRelease =
        sequenceOf(
            keyProps.getProperty("openWeather"),
            System.getenv("OPEN_WEATHER"),
            keyProps.getProperty("openWeatherDebug"),
            System.getenv("OPEN_WEATHER_DEBUG"),
        ).firstOrNull { !it.isNullOrBlank() } ?: ""
    defaultConfig {
        applicationId = "com.naveen.aerialviewsplus"
        minSdk = 23 // Android v6
        targetSdk = 37
        versionCode = 104
        versionName = "1.2"
        betaVersion = "-beta12"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["analyticsCollectionEnabled"] = false
        manifestPlaceholders["crashlyticsCollectionEnabled"] = false
        manifestPlaceholders["performanceCollectionEnabled"] = false

        buildConfigField("String", "OPEN_WEATHER", "\"$openWeatherDebug\"")
        buildConfigField("String", "BUILD_TIME", "\"${System.currentTimeMillis()}\"")
        buildConfigField("boolean", "ENABLE_YOUTUBE_LOGS", "false")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        abortOnError = false
    }

    bundle {
        // Trying this fix for reported crashes
        // https://stackoverflow.com/questions/50471888/android-app-bundle-introduces-resource-not-found-crash-in-android-app
        density {
            @Suppress("UnstableApiUsage")
            enableSplit = false
        }
        // App bundle (not APK) should contain all languages so 'locale switch'
        // feature works on Play Store and Amazon Appstore builds
        // https://stackoverflow.com/a/54862243/247257
        language {
            @Suppress("UnstableApiUsage")
            enableSplit = false
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            isMinifyEnabled = false
            // isPseudoLocalesEnabled = true
        }
        create("nonMinifiedRelease") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            buildConfigField("boolean", "ENABLE_YOUTUBE_LOGS", "false")
            matchingFallbacks += listOf("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // isDebuggable = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            buildConfigField("String", "OPEN_WEATHER", "\"$openWeatherRelease\"")

            manifestPlaceholders["analyticsCollectionEnabled"] = true
            manifestPlaceholders["crashlyticsCollectionEnabled"] = true
            manifestPlaceholders["performanceCollectionEnabled"] = true
        }
    }

    packaging {
        resources {
            resources {
                excludes.add("META-INF/INDEX.LIST")
                excludes.add("META-INF/LICENSE")
                excludes.add("META-INF/NOTICE")
                excludes.add("META-INF/*.kotlin_module")
                // JANSI: Windows/Mac/Linux native libs bundled by slf4j-simple — useless on Android
                excludes.add("org/fusesource/jansi/internal/native/**")
                // BouncyCastle PQC binary data — not used on Android
                excludes.add("org/bouncycastle/pqc/crypto/picnic/**")
                excludes.add("org/bouncycastle/pqc/crypto/lms/**")
                // Redundant license/notice files from dependencies
                excludes.add("META-INF/DEPENDENCIES")
                excludes.add("META-INF/LICENSE.txt")
                excludes.add("META-INF/NOTICE.txt")
                excludes.add("META-INF/AL2.0")
                excludes.add("META-INF/LGPL2.1")
                excludes.add("META-INF/ASL2.0")
                excludes.add("META-INF/*.SF")
                excludes.add("META-INF/*.RSA")
                excludes.add("META-INF/*.DSA")
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(
                System.getenv("KEYSTORE_PATH")
                    ?: "${System.getProperty("user.home")}/.android/aerialviews-plus-release.jks",
            )
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: "aerialviewsplus"
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
        create("legacy") {
            val releaseProps = loadProperties("signing/legacy.properties")
            storeFile = releaseProps["storeFile"]?.let { file(it) }
            storePassword = releaseProps["storePassword"] as String?
            keyAlias = releaseProps["keyAlias"] as String?
            keyPassword = releaseProps["keyPassword"] as String?
        }
    }

    flavorDimensions += "version"
    productFlavors {
        create("github") {
            signingConfig = signingConfigs.getByName("release")
            dimension = "version"
        }
        create("beta") {
            signingConfig = signingConfigs.getByName("legacy")
            dimension = "version"
            isDefault = true
            versionNameSuffix = betaVersion
        }
        create("googleplay") {
            signingConfig = signingConfigs.getByName("release")
            dimension = "version"
        }
        create("googleplaybeta") {
            signingConfig = signingConfigs.getByName("release")
            dimension = "version"
            versionNameSuffix = betaVersion
        }
        create("amazon") {
            signingConfig = signingConfigs.getByName("release")
            dimension = "version"
        }
        create("fdroid") {
            signingConfig = signingConfigs.getByName("release")
            dimension = "version"
        }
    }

    // Using this method https://stackoverflow.com/a/30548238/247257
    sourceSets {
        getByName("github").kotlin.directories.add("src/common/java")
        getByName("beta").kotlin.directories.add("src/common/java")
        getByName("googleplay").kotlin.directories.add("src/common/java")
        getByName("googleplaybeta").kotlin.directories.add("src/common/java")
        getByName("amazon").kotlin.directories.add("src/common/java")
        getByName("fdroid").kotlin.directories.add("src/fdroid/java")
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Support all favors except F-Droid
    val firebaseFlavors = listOf(
        "github", "beta", "googleplay", "googleplaybeta", "amazon"
    )
    firebaseFlavors.forEach { flavor ->
        "${flavor}Implementation"(platform(libs.firebase.bom))
        "${flavor}Implementation"(libs.bundles.firebase)
    }

    implementation(libs.bundles.kotlin)
    implementation(libs.bundles.androidx)
    implementation(libs.bundles.flowbus)
    implementation(libs.bundles.kotpref)
    implementation(libs.bundles.coil)
    implementation(libs.bundles.retrofit)

    implementation(libs.bundles.ktor)
    implementation(libs.bundles.room)
    ksp(libs.room.compiler)
    implementation(libs.bundles.exoplayer)
    implementation(libs.media3.dash)
    implementation(libs.media3.container)
    implementation(libs.sardine.android)
    implementation(libs.smbj)
    implementation(libs.timber)
    implementation(libs.slf4j.simple)
    implementation(libs.work.runtime.ktx)
    implementation(libs.newpipe.extractor) {
        exclude(group = "com.google.protobuf", module = "protobuf-javalite")
    }

    debugImplementation(libs.leakcanary)

    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.jupiter.engine)
    androidTestImplementation(libs.runner)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)

    implementation(libs.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))
    implementation(project(":projectivyapi"))

    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xmx1024m")
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events("started", "skipped", "passed", "failed")
        showStandardStreams = true
    }
}

fun loadProperties(fileName: String): Properties {
    return Properties().apply {
        val propertiesFile = rootProject.file(fileName)
        if (propertiesFile.exists()) {
            load(FileInputStream(propertiesFile))
        }
    }
}
