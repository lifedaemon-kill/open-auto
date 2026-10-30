plugins {
    id("com.android.library")
}

android {
    namespace = "com.github.lfdk.OAM.projection"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.google.protobuf:protobuf-javalite:4.36.2")
    testImplementation(libs.junit)
}
