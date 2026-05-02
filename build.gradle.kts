// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    configurations.classpath {
        resolutionStrategy {
            force(
                "org.bouncycastle:bcpkix-jdk18on:1.84",
                "org.bouncycastle:bcprov-jdk18on:1.84",
                "org.bouncycastle:bcutil-jdk18on:1.84",
                "org.bitbucket.b_c:jose4j:0.9.6",
                "org.jdom:jdom2:2.0.6.1",
                "org.apache.commons:commons-lang3:3.20.0",
            )
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.screenshot) apply false
}

subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            when ("${requested.group}:${requested.name}") {
                "com.fasterxml.jackson.core:jackson-core" -> useVersion("2.21.3")
                "com.fasterxml.jackson.core:jackson-databind" -> useVersion("2.21.3")
                "com.google.guava:guava" -> useVersion("33.6.0-android")
                "io.netty:netty-codec" -> useVersion("4.1.132.Final")
                "io.netty:netty-codec-http" -> useVersion("4.1.132.Final")
                "io.netty:netty-codec-http2" -> useVersion("4.1.132.Final")
                "io.netty:netty-common" -> useVersion("4.1.132.Final")
                "io.netty:netty-handler" -> useVersion("4.1.132.Final")
            }
        }
    }
}
