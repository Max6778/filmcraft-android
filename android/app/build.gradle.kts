plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.filmcraft.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.filmcraft.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}


dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
}

val rustJniLibs = layout.projectDirectory.dir("src/main/jniLibs")
val buildFilmCraftRust by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir.parentFile)
    commandLine(
        "cargo", "ndk",
        "--platform", "26",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", rustJniLibs.asFile.absolutePath,
        "build", "--release", "-p", "filmcraft-android-native",
    )
    outputs.dir(rustJniLibs)
}

tasks.named("preBuild").configure {
    dependsOn(buildFilmCraftRust)
}
