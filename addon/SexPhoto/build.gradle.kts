plugins {
    java
    kotlin("jvm")
    id("com.gradleup.shadow")
}

repositories {
    maven("https://maven.aliyun.com/repository/public")
    mavenCentral()
    // Nukkit-MOT 官方仓库
    maven("https://repo.lanink.cn/repository/maven-public/")
    maven("https://repo.opencollab.dev/maven-snapshots/")
}

version = rootProject.version

dependencies {
    // HuHoBot Nukkit 插件本体：只做编译期引用。
    //
    // 运行时由 HuHoBot 插件自己的 PluginClassLoader 提供，addon 的 classloader
    // 通过 Nukkit 的「全局已加载类注册表」解析到它们（见 PluginClassLoader.findClass）。
    // 因此 HuHoBot 在 onEnable 里会主动预热 OnBotRecvMsg / OnBotCommand / MsgPack 三个类，
    // 否则 addon 注册监听器时这些类还没被加载过，会 ClassNotFound。
    compileOnly(project(":server-Nukkit"))
    // MsgPack 在 AdapterCommon 里，server-Nukkit 是 implementation 依赖，不会传递出来。
    compileOnly(project(":server-AdapterCommon"))

    compileOnly("cn.nukkit:Nukkit:MOT-SNAPSHOT")
    // MOT 服务端自带 gson 2.13.2（父优先 classloader），不必打包。
    compileOnly("com.google.code.gson:gson:2.13.2")

    implementation(kotlin("stdlib"))
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))
kotlin.jvmToolchain(17)

tasks.processResources {
    val pluginVersion = rootProject.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        filter(org.apache.tools.ant.filters.ReplaceTokens::class, mapOf(
            "tokens" to mapOf("version" to pluginVersion)
        ))
    }
}

tasks.shadowJar {
    archiveFileName.set("HuHoBot-Addon-SexPhoto-${project.version}.jar")
}

tasks.build { dependsOn(tasks.shadowJar) }
