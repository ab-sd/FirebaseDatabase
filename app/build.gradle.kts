plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)

    // Add the Google services Gradle plugin
    id("com.google.gms.google-services")

}

android {
    namespace = "com.example.basicfiredatabase"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.basicfiredatabase"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        vectorDrawables.useSupportLibrary = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }


    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)


    // Import the Firebase BoM
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))

    // ✅ Firestore
    implementation("com.google.firebase:firebase-firestore-ktx")

    // Optional - Firebase Analytics (helps debugging)
    implementation("com.google.firebase:firebase-analytics-ktx")

    // Add (or update) these lines in app/build.gradle dependencies
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // RecyclerView (if not already present)
    implementation("androidx.recyclerview:recyclerview:1.3.0")

// Glide for image loading (network + caching)
    implementation("com.github.bumptech.glide:glide:4.15.1")

    implementation ("com.google.firebase:firebase-config-ktx")

    // ✅ JSON parsing
    implementation("org.json:json:20240303")

    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    implementation("androidx.fragment:fragment-ktx:1.8.9")


    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}