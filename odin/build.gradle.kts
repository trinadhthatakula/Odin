import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.maven.publish)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
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
