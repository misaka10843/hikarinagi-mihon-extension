plugins {
    id("com.android.application") version "8.7.3"
    kotlin("android") version "2.1.21"
}

val releaseVersion = providers.gradleProperty("extensionVersion").orElse("1.4.4").get()
val releaseCode = Regex("""1\.4\.([1-9][0-9]{0,8})""").matchEntire(releaseVersion)
    ?.groupValues?.get(1)?.toInt() ?: error("extensionVersion 必须是 1.4.N，N 为正整数")
val releaseKeystore = providers.environmentVariable("APK_KEYSTORE_PATH").orNull

android {
    namespace = "eu.kanade.tachiyomi.extension.zh.hikarinagi"
    compileSdk = 35

    defaultConfig {
        applicationId = namespace
        minSdk = 26
        targetSdk = 35
        versionCode = releaseCode
        versionName = releaseVersion
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (releaseKeystore != null) {
        signingConfigs.create("releaseKey") {
            storeFile = file(releaseKeystore)
            storePassword = providers.environmentVariable("APK_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("APK_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("APK_KEY_PASSWORD").get()
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("releaseKey")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly("com.github.keiyoushi:extensions-lib:v1.4.2.1") { isTransitive = false }
    compileOnly("com.squareup.okhttp3:okhttp:5.0.0-alpha.14")
    compileOnly("io.reactivex:rxjava:1.3.8")
    compileOnly("androidx.preference:preference:1.2.1") { isTransitive = false }
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("stdlib"))
    testImplementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.14")
    testImplementation("org.json:json:20250107")
}
