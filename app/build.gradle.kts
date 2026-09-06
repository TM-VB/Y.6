import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.util.Base64

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

val releaseKeystoreBase64: String? = System.getenv("RELEASE_KEYSTORE_BASE64")
val releaseStorePassword: String? = System.getenv("RELEASE_STORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("RELEASE_KEY_PASSWORD")

// Materialize keystore securely from Base64 secret into an isolated temporary directory in build
val decodedKeystoreFile: File? = if (!releaseKeystoreBase64.isNullOrBlank()) {
  val decodedBytes = Base64.getDecoder().decode(releaseKeystoreBase64.trim())
  val tempFile = file("${layout.buildDirectory.get().asFile}/intermediates/signing/release.keystore")
  tempFile.parentFile.mkdirs()
  tempFile.writeBytes(decodedBytes)
  tempFile
} else null

val missingReleaseSigningVars = buildList {
  if (releaseKeystoreBase64.isNullOrBlank()) add("RELEASE_KEYSTORE_BASE64")
  if (releaseStorePassword.isNullOrBlank()) add("RELEASE_STORE_PASSWORD")
  if (releaseKeyAlias.isNullOrBlank()) add("RELEASE_KEY_ALIAS")
  if (releaseKeyPassword.isNullOrBlank()) add("RELEASE_KEY_PASSWORD")
}

val hasReleaseSigning: Boolean = missingReleaseSigningVars.isEmpty() && decodedKeystoreFile != null && decodedKeystoreFile.exists()

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.videodownloader.app"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    if (hasReleaseSigning && decodedKeystoreFile != null) {
      create("release") {
        storeFile = decodedKeystoreFile
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (hasReleaseSigning) {
        signingConfig = signingConfigs.getByName("release")
      } else {
        signingConfig = null
      }
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
      all {
        it.jvmArgs(
          "--add-opens=java.base/java.lang=ALL-UNNAMED",
          "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
          "--add-opens=java.base/java.io=ALL-UNNAMED",
          "--add-opens=java.base/java.util=ALL-UNNAMED"
        )
        it.testLogging {
          events("passed", "skipped", "failed")
          exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
      }
    }
  }

  bundle {
    language {
      enableSplit = false
    }
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
  lint {
    disable += "InvalidFragmentVersionForActivityResult"
    abortOnError = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.ui)
  implementation(libs.androidx.media3.common)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

abstract class VerifyReleaseSigningTask : DefaultTask() {
  @get:Input
  abstract val hasSigning: Property<Boolean>

  @get:Input
  abstract val missingVariables: ListProperty<String>

  @TaskAction
  fun verify() {
    if (!hasSigning.get()) {
      val missingList = missingVariables.get()
      val details = if (missingList.isNotEmpty()) {
        "Missing release signing configuration:\n" + missingList.joinToString("\n") { "  - $it" }
      } else {
        "Release keystore file does not exist or is invalid."
      }
      throw GradleException(
        """
        ================================================================================
        RELEASE SIGNING FAILED:
        $details
        
        Required environment variables:
          - RELEASE_KEYSTORE_BASE64
          - RELEASE_STORE_PASSWORD
          - RELEASE_KEY_ALIAS
          - RELEASE_KEY_PASSWORD
        ================================================================================
        """.trimIndent()
      )
    }
  }
}

val verifyReleaseSigning = tasks.register<VerifyReleaseSigningTask>("verifyReleaseSigning") {
  hasSigning.set(hasReleaseSigning)
  missingVariables.set(missingReleaseSigningVars)
}

tasks.matching {
  it.name == "packageRelease" || it.name == "bundleRelease" || it.name == "assembleRelease"
}.configureEach {
  dependsOn(verifyReleaseSigning)
}

