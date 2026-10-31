plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.plugin-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.extradata"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.extradata"
    }

    buildFeatures {
        resValues = true
    }

    buildTypes {
        release {
            resValue("string", "app_name", "@string/app_name_release")
            proguardFile("proguard-rules.pro")
        }
        debug {
            resValue("string", "app_name", "@string/app_name_debug")
        }
    }
}

val copyExtraData = tasks.register<Copy>("copyExtraData") {
    from(rootProject.file("lib/fcitx5/src/main/cpp/prebuilt/spell-dict/en_dict.fscd")) {
        into("usr/share/fcitx5/spell")
    }
    from(rootProject.file("lib/fcitx5/src/main/cpp/fcitx5/src/modules/unicode/charselectdata")) {
        into("usr/share/fcitx5/unicode")
    }
    // pinyinhelper's stroke-order and pinyin tables (笔画 and 拆字 lookups)
    from(rootProject.file("lib/fcitx5/src/main/cpp/prebuilt/chinese-addons-data/pinyinhelper")) {
        into("usr/share/fcitx5/pinyinhelper")
    }
    // opencc character and phrase dictionaries for simplified/traditional conversion
    from(rootProject.file("lib/fcitx5/src/main/cpp/prebuilt/opencc/data")) {
        into("usr/share/opencc")
    }
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.named("generateDataDescriptor") {
    dependsOn(copyExtraData)
}

tasks.named("clean") {
    doLast {
        projectDir.resolve("src/main/assets/usr").deleteRecursively()
    }
}

dependencies {
    implementation(project(":lib:plugin-base"))
}
