import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "im.wisnu.nexora"
    compileSdk = 36

    defaultConfig {
        applicationId = "im.wisnu.nexora"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

kotlin {
    jvmToolchain(17)
}

/**
 * Compiles src/inputserver into a standalone dex jar (a few KB) and ships it as
 * an asset. The app pushes it to the TV and runs it with app_process, so key
 * presses skip the ~1 s Java VM start that `input keyevent` pays every time.
 */
abstract class BuildInputServerTask : DefaultTask() {
    @get:InputDirectory abstract val sourceDir: DirectoryProperty
    @get:InputFile abstract val androidJar: RegularFileProperty
    @get:InputFile abstract val d8Jar: RegularFileProperty
    @get:Nested abstract val javaLauncher: Property<JavaLauncher>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val classes = File(temporaryDir, "classes").apply { deleteRecursively(); mkdirs() }
        val sources = sourceDir.get().asFileTree.matching { include("**/*.java") }.files.map { it.absolutePath }
        val jdkBin = javaLauncher.get().metadata.installationPath.dir("bin").asFile
        val exe = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val android = androidJar.get().asFile.absolutePath
        exec.exec {
            commandLine(
                listOf(File(jdkBin, "javac$exe").absolutePath, "-source", "8", "-target", "8", "-Xlint:-options",
                    "-bootclasspath", android, "-d", classes.absolutePath) + sources,
            )
        }
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val classFiles = classes.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.map { it.absolutePath }
        exec.exec {
            commandLine(
                listOf(File(jdkBin, "java$exe").absolutePath, "-cp", d8Jar.get().asFile.absolutePath,
                    "com.android.tools.r8.D8", "--release", "--min-api", "26", "--lib", android,
                    "--output", File(out, "inputserver.jar").absolutePath) + classFiles.toList(),
            )
        }
    }
}

val buildInputServer = tasks.register<BuildInputServerTask>("buildInputServer") {
    sourceDir.set(layout.projectDirectory.dir("src/inputserver/java"))
    androidJar.set(androidComponents.sdkComponents.bootClasspath.map { it.first() })
    d8Jar.set(
        androidComponents.sdkComponents.sdkDirectory.map {
            it.file("build-tools/${android.buildToolsVersion}/lib/d8.jar")
        },
    )
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(buildInputServer, BuildInputServerTask::outputDir)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // ADB: only core/adb/libadb may import these. Features use core/adb interfaces.
    implementation(libs.libadb)
    implementation(libs.conscrypt.android)
    implementation(libs.bouncycastle.pkix)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
