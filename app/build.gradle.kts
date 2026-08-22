import java.util.Properties

val localSigningFile = rootProject.file("local-signing.properties")
val localSigning = Properties().apply {
    if (localSigningFile.isFile) localSigningFile.inputStream().use { load(it) }
}
fun signingValue(environmentName: String, propertyName: String): String? =
    providers.environmentVariable(environmentName).orNull
        ?: localSigning.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val uploadStoreFile = signingValue("CAD2CNY_UPLOAD_STORE_FILE", "storeFile")
val uploadStorePassword = signingValue("CAD2CNY_UPLOAD_STORE_PASSWORD", "storePassword")
val uploadKeyAlias = signingValue("CAD2CNY_UPLOAD_KEY_ALIAS", "keyAlias")
val uploadKeyPassword = signingValue("CAD2CNY_UPLOAD_KEY_PASSWORD", "keyPassword")
val releaseSigningReady = listOf(uploadStoreFile, uploadStorePassword, uploadKeyAlias, uploadKeyPassword)
    .all { !it.isNullOrBlank() }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.ramgpt.cad2cnycam"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.ramgpt.cad2cnycam"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                val externalKeystore = file(requireNotNull(uploadStoreFile)).canonicalFile
                val repositoryRoot = rootProject.projectDir.canonicalFile
                require(!externalKeystore.toPath().startsWith(repositoryRoot.toPath())) {
                    "The upload keystore must be stored outside the Git repository."
                }
                require(externalKeystore.isFile) {
                    "Upload keystore does not exist: " + externalKeystore
                }
                storeFile = externalKeystore
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    doFirst {
        check(releaseSigningReady) {
            "Release signing is not configured. Set CAD2CNY_UPLOAD_* environment variables " +
                "or create ignored local-signing.properties from the example file."
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    val cameraXVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
}
