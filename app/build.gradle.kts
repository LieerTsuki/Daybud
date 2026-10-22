import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
}

abstract class BuildDiaryDotprodEngine : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:Internal abstract val sdkDirectory: DirectoryProperty
    @get:Internal abstract val ndkDirectory: DirectoryProperty
    @get:Input abstract val cmakeVersion: Property<String>
    @get:Input abstract val ndkVersion: Property<String>
    @get:LocalState abstract val nativeBuildDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Inject abstract val execOperations: ExecOperations
    @get:Inject abstract val fileSystemOperations: FileSystemOperations

    @TaskAction fun buildEngine() {
        val executableSuffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val cmakeBin = sdkDirectory.dir("cmake/${cmakeVersion.get()}/bin").get().asFile
        val cmake = cmakeBin.resolve("cmake$executableSuffix")
        val buildDirectory = nativeBuildDirectory.get().asFile
        execOperations.exec {
            commandLine(cmake, "-S", sourceDirectory.get().asFile, "-B", buildDirectory,
                "-G", "Ninja", "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                "-DCMAKE_MAKE_PROGRAM=${cmakeBin.resolve("ninja$executableSuffix")}",
                "-DCMAKE_TOOLCHAIN_FILE=${ndkDirectory.file("build/cmake/android.toolchain.cmake").get().asFile}",
                "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-26",
                "-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                "-DDIARY_ENGINE_VARIANT=dotprod")
        }
        execOperations.exec {
            commandLine(cmake, "--build", buildDirectory, "--target", "diary_llama_dotprod", "--parallel", "2")
        }
        val library = buildDirectory.resolve("libdiary_llama_dotprod.so")
        check(library.isFile) { "The dotprod candidate did not produce its expected native library" }
        fileSystemOperations.copy {
            from(library)
            into(outputDirectory.dir("arm64-v8a"))
        }
    }
}

// Candidate-only until physical ARM correctness and latency measurements pass.
// A normal build neither compiles nor packages the extra complete engine.
val diaryDotprodCandidate = providers.gradleProperty("diaryDotprodCandidate")
    .map(String::toBooleanStrict).orElse(false)

extensions.configure<ApplicationExtension> {
    namespace = "com.example.lifemanager"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.example.lifemanager"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 15
        versionName = "1.3.1-preview"
        buildConfigField("boolean", "DIARY_DOTPROD_CANDIDATE", diaryDotprodCandidate.get().toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                targets += listOf("diary_llama", "diary_cpu_features")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
            buildStagingDirectory = file("${rootProject.projectDir}/.tools/native-gradle")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs(
                "--enable-native-access=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

if (diaryDotprodCandidate.get()) {
    val components = extensions.getByType<ApplicationAndroidComponentsExtension>()
    val dotprodEngine = tasks.register<BuildDiaryDotprodEngine>("buildDiaryDotprodEngine") {
        sourceDirectory.set(layout.projectDirectory.dir("src/main/cpp"))
        sdkDirectory.set(components.sdkComponents.sdkDirectory)
        ndkDirectory.set(components.sdkComponents.ndkDirectory)
        cmakeVersion.set("3.22.1")
        ndkVersion.set("27.2.12479018")
        nativeBuildDirectory.set(rootProject.layout.projectDirectory.dir(".tools/native-dotprod"))
        outputDirectory.set(layout.buildDirectory.dir("generated/offlineEngineJni"))
    }
    components.onVariants(components.selector().all()) { variant ->
        // AGP establishes the generated JNI folder dependency before merging and packaging.
        variant.sources.jniLibs?.addGeneratedSourceDirectory(dotprodEngine, BuildDiaryDotprodEngine::outputDirectory)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Room's migration test reader needs 1.8.1; align the app's Navigation-provided
    // core runtime too, because Android tests reuse dependencies from the app APK.
    implementation(platform(libs.kotlinx.serialization.bom))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    kapt(libs.hilt.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(kotlin("test"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(kotlin("test"))
}

kapt {
    correctErrorTypes = true
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}
