import keiyoushi.gradle.extensions.baseVersionCode

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 51

dependencies {
    api(project(":lib:cryptoaes"))
    api(project(":lib:i18n"))
}

android {
    sourceSets {
        named("test") {
            kotlin.directories.add("test")
        }
    }
}

dependencies {
    testImplementation(libs.bundles.common)
    testImplementation(libs.junit)
}
