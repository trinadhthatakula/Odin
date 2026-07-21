import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.maven.publish)
    alias(libs.plugins.binary.compatibility.validator)
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // Emit real JVM default methods for interface members (no DefaultImpls classes / no
        // compatibility bridges) — better Java interop. Set at the API freeze because switching
        // this AFTER publish would be an ABI break.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

android {
    namespace = "com.valhalla.superuser"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("proguard-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        aidl = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)

    // kotlin("test-junit") (not bare kotlin("test")) so the kotlin.test.Test typealias binds to
    // JUnit4 — Odin uses AGP's built-in Kotlin, so the Kotlin Gradle Plugin's test-framework
    // auto-selection isn't present; the -junit variant pulls the backend (+junit) explicitly.
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// -----------------------------------------------------------------------------
// binary-compatibility-validator (BCV) — manual wiring for AGP's built-in Kotlin.
//
// The BCV plugin only auto-registers its api tasks when it detects the
// `kotlin-android` / `kotlin-multiplatform` / `kotlin` plugin ids. Odin uses AGP's
// built-in Kotlin support (KotlinBaseApiPlugin), which BCV does NOT hook, so no
// apiDump/apiCheck tasks are created automatically. We therefore reproduce BCV's
// standard task graph ourselves against the `release` variant:
//   apiBuild (KotlinApiBuildTask) -> apiCheck (KotlinApiCompareTask) + apiDump (copy)
// This freezes the public Kotlin API in `odin/api/odin.api`, identically to the
// stock plugin. `internal` declarations are excluded via Kotlin @Metadata, so the
// engine internals never enter the dump.
// -----------------------------------------------------------------------------
val bcvApiFileName = "odin.api"

// Worker runtime classpath, mirroring BCV's internal prepareJvmValidationClasspath().
// BCV pins ASM 9.6 for bytecode reading; it normally also adds kotlin-metadata-jvm, but only
// under a `plugins.withId("org.jetbrains.kotlin.*")` guard that never fires for AGP built-in
// Kotlin — so we add it explicitly. Keep the metadata version in sync with the Kotlin compiler
// AGP bundles (AGP 9.3.0 -> Kotlin 2.2.10) so the ABI reader parses @Metadata correctly.
val bcvWorkerClasspath: Configuration = configurations.detachedConfiguration(
    dependencies.create("org.ow2.asm:asm:9.6"),
    dependencies.create("org.ow2.asm:asm-tree:9.6"),
    dependencies.create("org.jetbrains.kotlin:kotlin-metadata-jvm:2.2.10"),
)

val apiBuild = tasks.register<KotlinApiBuildTask>("apiBuild") {
    group = "other"
    description = "Builds the ABI dump of the release variant's public Kotlin API."
    inputClassesDirs.from(tasks.named("compileReleaseKotlin").map { it.outputs.files })
    runtimeClasspath.from(bcvWorkerClasspath)
    outputApiFile.set(layout.buildDirectory.file("api/$bcvApiFileName"))
}

val apiCheck = tasks.register<KotlinApiCompareTask>("apiCheck") {
    group = "verification"
    description = "Checks the release ABI against the committed api/$bcvApiFileName."
    projectApiFile.set(layout.projectDirectory.file("api/$bcvApiFileName"))
    generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
}

tasks.register<Copy>("apiDump") {
    group = "other"
    description = "Regenerates the committed api/$bcvApiFileName from the current release ABI."
    from(apiBuild.flatMap { it.outputApiFile })
    into(layout.projectDirectory.dir("api"))
}

tasks.named("check") {
    dependsOn(apiCheck)
}

mavenPublishing {
    coordinates(
        groupId = providers.gradleProperty("GROUP").get(),
        artifactId = "odin",
        version = providers.gradleProperty("VERSION_NAME").get()
    )
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true
        )
    )
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    pom {
        name.set("Odin")
        description.set("Kotlin-first root shell + RootService IPC for Android (in-house libsu fork).")
        inceptionYear.set("2026")
        url.set("https://github.com/trinadhthatakula/Odin")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("trinadhthatakula")
                name.set("Trinadh Thatakula")
                url.set("https://github.com/trinadhthatakula")
            }
        }
        scm {
            url.set("https://github.com/trinadhthatakula/Odin")
            connection.set("scm:git:https://github.com/trinadhthatakula/Odin.git")
            developerConnection.set("scm:git:ssh://git@github.com/trinadhthatakula/Odin.git")
        }
    }
}
