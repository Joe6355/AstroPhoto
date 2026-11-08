import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseSigningProperties = Properties()
val releaseSigningFile = rootProject.file("keystore.properties")
if (releaseSigningFile.isFile) {
    releaseSigningFile.inputStream().use(releaseSigningProperties::load)
}
val hasReleaseSigning = listOf(
    "storeFile",
    "storePassword",
    "keyAlias",
    "keyPassword"
).all { !releaseSigningProperties.getProperty(it).isNullOrBlank() }

// The published document is also the offline copy; keep one source of policy text.
abstract class PreparePrivacyPolicy : DefaultTask() {
    @get:InputFile abstract val policyFile: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun prepare() {
        val target = outputDirectory.file("PRIVACY_POLICY_RU.md").get().asFile
        target.parentFile.mkdirs()
        policyFile.get().asFile.copyTo(target, overwrite = true)
    }
}
val preparePrivacyPolicy = tasks.register<PreparePrivacyPolicy>("preparePrivacyPolicy") {
    policyFile.set(rootProject.layout.projectDirectory.file("docs/PRIVACY_POLICY_RU.md"))
    outputDirectory.set(layout.buildDirectory.dir("generated/privacyAssets"))
}

android {
    namespace = "com.joe6355.astrophoto"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // Allows an in-place update of the earlier phone build without changing the published ID.
        applicationId = providers.gradleProperty("astrophoto.installApplicationId")
            .getOrElse("com.joe6355.astrophoto")
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.9.0-beta.6"

        buildConfigField("String", "YAN_NATIVE_UNIT_ID", "\"R-M-20351551-1\"")
        buildConfigField("String", "YAN_APP_OPEN_UNIT_ID", "\"R-M-20351551-2\"")

        manifestPlaceholders["astrophotoAppLabel"] = providers
            .gradleProperty("astrophoto.installAppLabel").getOrElse("@string/app_name")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            buildConfigField("String", "YAN_NATIVE_UNIT_ID", "\"demo-native-content-yandex\"")
            buildConfigField("String", "YAN_APP_OPEN_UNIT_ID", "\"demo-appopenad-yandex\"")
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(preparePrivacyPolicy, PreparePrivacyPolicy::outputDirectory)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.yandex.mobileads)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
