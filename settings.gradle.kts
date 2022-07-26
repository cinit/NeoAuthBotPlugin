@file:Suppress("UnstableApiUsage")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "NeoAuth3BotPlugin"

fun getLocalProperty(baseDir: File, propertyName: String): String? {
    val localProp = File(baseDir, "local.properties")
    if (!localProp.exists()) {
        return null
    }
    val localProperties = java.util.Properties()
    localProp.inputStream().use {
        localProperties.load(it)
    }
    return localProperties.getProperty(propertyName, null)
}

val gTelebotDirPath = getLocalProperty(rootProject.projectDir, "telebotconsole.project.dir")
if (gTelebotDirPath.isNullOrEmpty()) {
    throw IllegalArgumentException("telebotconsole.project.dir is not set, please set it in local.properties")
}
val gTelebotDir = File(gTelebotDirPath!!)
if (!gTelebotDir.isDirectory) {
    throw IllegalArgumentException("telebotconsole.project.dir: $gTelebotDirPath is not a directory")
}

val core = "cc.ioctl.telebot:core:1.0"

dependencyResolutionManagement {
    versionCatalogs {
        create("external") {
            library("core", core)
        }
    }
}

includeBuild(gTelebotDir) {
    dependencySubstitution {
        substitute(module(core)).using(project(":core"))
    }
}
