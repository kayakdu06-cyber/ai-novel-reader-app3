plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.zhijuan.provider.gemini"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { targetSdk = 36 }
}

dependencies {
    api(project(":provider:common"))
    implementation(project(":provider:stream"))
    implementation(project(":provider:transport"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okio)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockwebserver3)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.mockwebserver3)
    androidTestImplementation(libs.okhttp.tls)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
