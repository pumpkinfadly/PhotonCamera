import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun String.toBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.hinnka.mycamera"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.hinnka.mycamera"
        minSdk = 30
        targetSdk = 36
        versionCode = 164
        versionName = "1.28.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
                // Build the JNI library and its dependencies, excluding libjpeg tools/tests.
                targets += "my-native-lib"
            }
        }

        buildConfigField(
            "String",
            "BUILT_IN_API_URL",
            "https://camera-api.hinnka.me/v1".toBuildConfigString()
        )
        buildConfigField(
            "String",
            "BUILT_IN_API_KEY",
            localProperties.getProperty("BUILT_IN_API_KEY_GOOGLE", "").toBuildConfigString()
        )
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val storeFileProp = project.findProperty("RELEASE_STORE_FILE") as? String
            if (storeFileProp != null) {
                storeFile = file(storeFileProp)
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as? String
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as? String
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as? String
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 无 RELEASE_* 属性时回退 debug 签名，个人分支可直接安装。
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            // Release 不带 -g 调试信息（默认 RelWithDebInfo 会为每个目标文件生成），
            // 加快 LibRaw/DNG SDK 数百个源文件的编译与链接；最终 .so 由 AGP strip。
            externalNativeBuild {
                cmake {
                    arguments += "-DCMAKE_BUILD_TYPE=Release"
                }
            }
        }
    }

    lint {
        // Keep fatal lint analysis off the Release packaging path.
        checkReleaseBuilds = false
    }

    // Drop the signed dependency-info blob from APK/AAB.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    flavorDimensions += "channel"
    productFlavors {
        create("dev") {
            dimension = "channel"
            applicationId = "com.hinnka.mycamera.dev"
        }
        create("google") {
            dimension = "channel"
        }
        create("default") {
            dimension = "channel"
        }
        create("samsung") {
            dimension = "channel"
            applicationId = "com.samsung.android.scan3d"
        }
        create("meitu") {
            dimension = "channel"
            applicationId = "com.meitu.meiyancamera"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += "tflite"
    }

    sourceSets {
        getByName("dev") {
            java {
                srcDir("src/default/java")
            }
            manifest.srcFile("src/default/AndroidManifest.xml")
        }
        getByName("samsung") {
            java {
                srcDir("src/default/java")
            }
            manifest.srcFile("src/default/AndroidManifest.xml")
        }
        getByName("meitu") {
            java {
                srcDir("src/default/java")
            }
            manifest.srcFile("src/default/AndroidManifest.xml")
        }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    
    // ViewModel Compose
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // Material Icons Core
    implementation(libs.androidx.material.icons.core)
    
    // Coil for image loading
    implementation(libs.coil.compose)

    // Telephoto for large image viewing with zoom support
    implementation(libs.telephoto.zoomable.image.coil)
    
    // Navigation Compose
    implementation(libs.androidx.navigation.compose)
    
    // ExifInterface for writing EXIF metadata
    implementation(libs.androidx.exifinterface)

    // HEIC export through the platform image encoder
    implementation(libs.androidx.heifwriter)
    
    // DataStore for user preferences
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.animation.core)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.gson)

    // Bugly for default flavor
    "defaultImplementation"(libs.bugly.crashreport)
    "samsungImplementation"(libs.bugly.crashreport)
    "meituImplementation"(libs.bugly.crashreport)
    "devImplementation"(libs.bugly.crashreport)

    // Billing for google flavor
    "googleImplementation"(libs.google.billing)
    "googleImplementation"(libs.google.billing.ktx)

    // Reorderable for drag-and-drop list reordering
    implementation(libs.reorderable)

    // Media3 for video playback and export
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.effect)
    implementation(libs.media3.transformer)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // LiteRT: Interpreter for CPU models, CompiledModel for GPU-accelerated models
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.support)
}
