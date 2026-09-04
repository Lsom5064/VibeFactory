plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun configuredValue(name: String, fallback: String = ""): String =
    providers.environmentVariable(name)
        .orElse(providers.gradleProperty(name))
        .getOrElse(fallback)

fun quotedBuildConfigValue(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val generatedApplicationId = configuredValue(
    "GENERATED_APP_APPLICATION_ID",
    "kr.ac.kangwon.hai.generated.baseproject",
)
val generatedTaskId = configuredValue("GENERATED_APP_TASK_ID", "task-unknown")
val generatedServerBaseUrl = configuredValue("SERVER_BASE_URL", "http://127.0.0.1:8000")
    .trimEnd('/')
val generatedVersionCode = configuredValue("GENERATED_APP_VERSION_CODE", "2100000000")
    .toIntOrNull()
    ?: error("GENERATED_APP_VERSION_CODE must be an integer")
val generatedVersionName = configuredValue("GENERATED_APP_VERSION_NAME", "1.0.0")

val keystorePath = configuredValue("GENERATED_APP_KEYSTORE_PATH")
val keystorePassword = configuredValue("GENERATED_APP_KEYSTORE_PASSWORD")
val keyAliasName = configuredValue("GENERATED_APP_KEY_ALIAS")
val keyPasswordValue = configuredValue("GENERATED_APP_KEY_PASSWORD")
val signingConfigured = listOf(
    keystorePath,
    keystorePassword,
    keyAliasName,
    keyPasswordValue,
).all(String::isNotBlank)

android {
    namespace = "kr.ac.kangwon.hai.generated"
    compileSdk = 36

    defaultConfig {
        applicationId = generatedApplicationId
        minSdk = 26
        targetSdk = 35
        versionCode = generatedVersionCode
        versionName = generatedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "VIBE_TASK_ID", quotedBuildConfigValue(generatedTaskId))
        buildConfigField("String", "VIBE_SERVER_BASE_URL", quotedBuildConfigValue(generatedServerBaseUrl))
        buildConfigField(
            "String",
            "OPENWEATHER_API_KEY",
            quotedBuildConfigValue(configuredValue("OPENWEATHER_API_KEY")),
        )
        buildConfigField(
            "String",
            "DATA_GO_KR_SERVICE_KEY",
            quotedBuildConfigValue(configuredValue("DATA_GO_KR_SERVICE_KEY")),
        )
    }

    signingConfigs {
        if (signingConfigured) {
            create("generatedRelease") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (signingConfigured) {
                signingConfig = signingConfigs.getByName("generatedRelease")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    doFirst {
        require(signingConfigured) {
            "Generated release signing is not configured. Set GENERATED_APP_KEYSTORE_PATH, " +
                "GENERATED_APP_KEYSTORE_PASSWORD, GENERATED_APP_KEY_ALIAS, and GENERATED_APP_KEY_PASSWORD."
        }
        require(file(keystorePath).isFile) {
            "Generated release keystore does not exist: $keystorePath"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core-ktx:1.5.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
