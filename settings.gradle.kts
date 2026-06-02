@file:Suppress("ktlint:standard:kdoc")

pluginManagement {
    includeBuild("gradle/build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://www.jitpack.io")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("kei") {
            from(files("gradle/kei.versions.toml"))
        }
    }
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Korean-Mihon-Extensions-Source"

loadSelectedIndividualExtensions()

include(":core")

File(rootDir, "lib").eachDir { include("lib:${it.name}") }
File(rootDir, "lib-multisrc").eachDir { include("lib-multisrc:${it.name}") }

fun loadSelectedIndividualExtensions() {
    val selectedExtensions = providers.environmentVariable("CI_SELECTED_EXTENSIONS").orNull
        ?.split(Regex("""[\s,]+"""))
        ?.filter { it.isNotBlank() && it != "all" }
        ?.toSet()
        .orEmpty()

    File(rootDir, "src").eachDir { dir ->
        dir.eachDir { subdir ->
            if (selectedExtensions.isNotEmpty() && subdir.name !in selectedExtensions) {
                return@eachDir
            }
            include("src:${dir.name}:${subdir.name}")
        }
    }
}

fun loadIndividualExtension(lang: String, name: String) {
    include("src:$lang:$name")
}

fun File.eachDir(block: (File) -> Unit) {
    val files = listFiles() ?: return
    for (file in files) {
        if (file.isDirectory && file.name != ".gradle" && file.name != "build") {
            block(file)
        }
    }
}
