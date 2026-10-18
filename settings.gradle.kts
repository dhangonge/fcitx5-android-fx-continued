pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "fcitx5-android"

include(":lib:common")
include(":lib:fcitx5")
include(":lib:fcitx5-lua")
include(":lib:libime")
include(":lib:fcitx5-chinese-addons")
include(":codegen")
include(":app")
include(":lib:plugin-base")
include(":lib:voice-asr")
include(":plugin:anthy")
include(":plugin:clipboard-filter")
include(":plugin:unikey")
include(":plugin:rime")
include(":plugin:pinyin-lm")
include(":plugin:table-data")
include(":plugin:hangul")
include(":plugin:chewing")
include(":plugin:sayura")
include(":plugin:jyutping")
include(":plugin:thai")
include(":plugin:text-editor")
include(":plugin:text-editor:language-textmate")
// :plugin:voice-asr is intentionally not built: the same engine now runs in-process inside the
// app (:lib:voice-asr, see VoiceAsrProviders), and a separate process is exactly what ROM freezers
// kill. The sources stay as the reference implementation of the IVoiceInputProvider AIDL contract.
