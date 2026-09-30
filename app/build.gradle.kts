plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "ai.joydurm"
    compileSdk = 35
    defaultConfig { applicationId = "ai.joydurm"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "0.1.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (System.getenv("KEYSTORE_PATH") != null) create("production") {
            storeFile = file(System.getenv("KEYSTORE_PATH"))
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes {
        release { isMinifyEnabled = false; if (signingConfigs.findByName("production") != null) signingConfig = signingConfigs.getByName("production") }
    }
    lint { abortOnError = true }
}
dependencies {
    implementation("io.github.sceneview:arsceneview:2.3.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    testImplementation("junit:junit:4.13.2")
}
