import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
 id("org.jetbrains.kotlin.jvm") version "2.4.20"
 id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}
java { sourceCompatibility = JavaVersion.VERSION_25; targetCompatibility = JavaVersion.VERSION_25 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_25) } }
dependencies {
 compileOnly(files("tools/.cache/host-compile-only.jar"))
 compileOnly("org.jetbrains.compose.runtime:runtime-desktop:1.12.2")
 compileOnly("org.jetbrains.compose.foundation:foundation-desktop:1.12.2")
 compileOnly("org.jetbrains.compose.ui:ui-desktop:1.12.2")
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release.set(25) }

// 测试只加载编译依赖，不将这些依赖放入插件包。
configurations.testImplementation { extendsFrom(configurations.compileOnly.get()) }
tasks.register<JavaExec>("nativeStateTest") {
 dependsOn("testClasses")
 classpath = sourceSets.test.get().runtimeClasspath
 mainClass.set("io.github.mystery00.spw.navidrome.NativeStateTest")
 enableAssertions = true
}
