plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.w51il21d.island"
 compileSdk = 35
 signingConfigs.getByName("debug") {
  storeFile = file("signing/island.jks")
  storePassword = "android"
  keyAlias = "island"
  keyPassword = "android"
 }
 defaultConfig {
  applicationId = "com.w51il21d.island"
  minSdk = 30
  targetSdk = 35
  versionCode = 2
  versionName = "1.0.1"
 }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
 kotlinOptions { jvmTarget = "17" }
 testOptions { unitTests.isIncludeAndroidResources = true }
 lint { checkReleaseBuilds = false }
}
dependencies {
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.robolectric:robolectric:4.14.1")
}
