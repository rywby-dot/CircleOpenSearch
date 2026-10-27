plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "org.circleopensearch"
    compileSdk = 35
    defaultConfig { applicationId = "org.circleopensearch"; minSdk = 29; targetSdk = 35; versionCode = 11; versionName = "0.5.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
