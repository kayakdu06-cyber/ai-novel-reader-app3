plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.zhijuan.provider.capability.storage"
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
    implementation(project(":core:database"))
    implementation(libs.androidx.room.runtime)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}
