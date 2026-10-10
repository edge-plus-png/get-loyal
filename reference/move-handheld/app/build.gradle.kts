plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val productionBuild = providers.gradleProperty("moveEnvironment").orElse("staging").get().let {
    require(it == "staging") { "The loyalty test APK is staging only." }
    false
}
val mixedCashInstrumentation = providers.gradleProperty("mixedCashInstrumentation").orNull == "true"
val loyaltyInstrumentation = providers.gradleProperty("loyaltyInstrumentation").orNull == "true"
android {
    namespace = "uk.edge.move.handheld"
    compileSdk = 35
    defaultConfig {
        applicationId = "uk.edge.move.handheld.loyaltytest.staging"
        manifestPlaceholders["moveAppLabel"] = "Move Loyalty Staging"
        minSdk = 25
        targetSdk = 35
        versionCode = 4
        versionName = "0.4-loyalty-v1"
        testInstrumentationRunner = if (loyaltyInstrumentation) "uk.edge.move.handheld.LoyaltyInstrumentation" else if (mixedCashInstrumentation) "uk.edge.move.handheld.MixedCashInstrumentation" else "uk.edge.move.handheld.FixtureInstrumentation"
        buildConfigField("String", "OPS_URL", "\"https://move-staging.getedgeportal.app\"")
        buildConfigField("boolean", "IS_LOYALTY_TEST", "true")
        buildConfigField("boolean", "IS_STAGING", (!productionBuild).toString())
    }
    buildTypes {
        debug { isMinifyEnabled = false; applicationIdSuffix = ".benchmark" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Staging artifact only. This certificate cannot upgrade legacy production Move.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    if (mixedCashInstrumentation) {
        // Dedicated diagnostic source set; the default instrumentation and all release unit gates remain unchanged.
        sourceSets.getByName("androidTest").java.setSrcDirs(listOf("src/mixedCashAndroidTest/java"))
    }
    if (loyaltyInstrumentation) sourceSets.getByName("androidTest").java.setSrcDirs(listOf("src/loyaltyAndroidTest/java"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation(files("libs/libpositive-0.01.20.4807-release.aar", "libs/NeptuneLiteApi_V3.24.00_20210519.jar"))
    implementation(project(":shared-counter"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
tasks.matching { it.name == "assembleRelease" || it.name == "assembleDebug" }.configureEach { dependsOn("testDebugUnitTest") }

val verifySharedContractSources by tasks.registering(Exec::class) {
    commandLine("python3", rootProject.file("scripts/verify-shared-source.py").absolutePath)
}
tasks.matching { it.name.startsWith("test") && it.name.endsWith("UnitTest") }.configureEach {
    dependsOn(verifySharedContractSources)
}
