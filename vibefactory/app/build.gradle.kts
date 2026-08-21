plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun configuredServerBaseUrl(): String =
    providers.environmentVariable("VIBE_SERVER_BASE_URL")
        .orElse(providers.gradleProperty("VIBE_SERVER_BASE_URL"))
        .getOrElse("http://KangwonHaiLab.iptime.org:8000")
        .trimEnd('/')

fun quotedBuildConfigValue(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "kr.ac.kangwon.hai.vibefactory"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.ac.kangwon.hai.vibefactory"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "VIBE_SERVER_BASE_URL",
            quotedBuildConfigValue(configuredServerBaseUrl())
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)

    // 🌐 Retrofit & JSON 파싱
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)

    // 📥 OkHttp (APK 다운로드용)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    // ⚡ Coroutines (비동기 처리)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core-ktx:1.5.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
