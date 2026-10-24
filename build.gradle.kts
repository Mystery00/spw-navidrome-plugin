import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

dependencies {
    compileOnly(kotlin("stdlib"))
    // Metadata 解析使用宿主提供的反射运行时；独立测试补齐，不打入插件包。
    testRuntimeOnly(kotlin("reflect"))

    if (providers.gradleProperty("standalone").orNull == "true") {
        // 使用官方多模块发布中的 API 模块，避免引入其 Gradle 插件。
        compileOnly("com.github.Moriafly.spw-workshop-api:spw-workshop-api:0.1.0-dev23") {
            isTransitive = false
        }
        compileOnly("org.pf4j:pf4j:3.12.0")
        testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
        testRuntimeOnly("net.java.dev.jna:jna-platform:5.17.0")
    } else {
        compileOnly(files("tools/.cache/host-compile-only.jar"))
    }

    compileOnly("org.jetbrains.compose.runtime:runtime-desktop:1.12.2")
    compileOnly("org.jetbrains.compose.foundation:foundation-desktop:1.12.2")
    compileOnly("org.jetbrains.compose.ui:ui-desktop:1.12.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

// 测试只加载编译依赖，不将这些依赖放入插件包。
configurations.testImplementation {
    extendsFrom(configurations.compileOnly.get())
}

tasks.register<JavaExec>("permissionLifecycleTest") {
    dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    providers.gradleProperty("spwHostJar").orNull?.let {
        classpath += files(it)
    }
    mainClass.set("vip.mystery0.spw.navidrome.PermissionLifecycleTest")
    enableAssertions = true
}

mapOf(
        "coreTest" to "vip.mystery0.spw.navidrome.CoreTest",
        "viewRequestTest" to "vip.mystery0.spw.navidrome.ui.ViewRequestTest",
        "credentialStoreTest" to "vip.mystery0.spw.navidrome.credentials.CredentialStoreTest",
    )
    .forEach { (taskName, entryPoint) ->
        tasks.register<JavaExec>(taskName) {
            dependsOn("testClasses")
            classpath = sourceSets.test.get().runtimeClasspath
            mainClass.set(entryPoint)
            enableAssertions = true
            jvmArgs("--enable-native-access=ALL-UNNAMED")
        }
    }

tasks.register<JavaExec>("nativeStateTest") {
    dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("vip.mystery0.spw.navidrome.NativeStateTest")
    enableAssertions = true
}

// 宿主主题语义色的纯 JVM 回归。
tasks.register<JavaExec>("nativeThemeTest") {
    dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("vip.mystery0.spw.navidrome.ui.NativeThemeTest")
    enableAssertions = true
}

// 只重命名合成测试类的 JVM 标识，保留 Metadata，以模拟宿主重新混淆。
val remapMetadataFixture =
    tasks.register<Exec>("remapMetadataFixture") {
        dependsOn("testClasses")
        commandLine(
            "python",
            "tools/remap_fixture.py",
            "build/classes/kotlin/test",
            "build/fixtures/metadata-renamed.jar",
            "--prefix",
            "vip/mystery0/spw/navidrome/host/fixtures/",
        )
    }

tasks.register<JavaExec>("metadataRenamingTest") {
    dependsOn(remapMetadataFixture)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("vip.mystery0.spw.navidrome.host.MetadataRenamingTest")
    args("build/fixtures/metadata-renamed.jar")
    enableAssertions = true
}

tasks.register<JavaExec>("hostUiResolverTest") {
    dependsOn(remapMetadataFixture)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("vip.mystery0.spw.navidrome.ui.HostUiResolverTest")
    args("build/fixtures/metadata-renamed.jar")
    enableAssertions = true
}

tasks.register<JavaExec>("hostUiAbiTest") {
    dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("vip.mystery0.spw.navidrome.ui.HostUiAbiTest")
    providers.gradleProperty("spwHostJar").orNull?.let { args(it) }
    enableAssertions = true
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
