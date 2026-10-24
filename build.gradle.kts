import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
 id("org.jetbrains.kotlin.jvm") version "2.4.20"
 id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}
java { sourceCompatibility = JavaVersion.VERSION_25; targetCompatibility = JavaVersion.VERSION_25 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_25) } }
dependencies {
 compileOnly(kotlin("stdlib"))
 if (providers.gradleProperty("standalone").orNull == "true") {
  // 使用官方多模块发布中的 API 模块，避免引入其 Gradle 插件。
  compileOnly("com.github.Moriafly.spw-workshop-api:spw-workshop-api:0.1.0-dev23") {
   isTransitive = false
  }
  compileOnly("org.pf4j:pf4j:3.12.0")
  testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
  testRuntimeOnly("net.java.dev.jna:jna-platform:5.17.0")
 }
 else compileOnly(files("tools/.cache/host-compile-only.jar"))
 compileOnly("org.jetbrains.compose.runtime:runtime-desktop:1.12.2")
 compileOnly("org.jetbrains.compose.foundation:foundation-desktop:1.12.2")
 compileOnly("org.jetbrains.compose.ui:ui-desktop:1.12.2")
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release.set(25) }

// 测试只加载编译依赖，不将这些依赖放入插件包。
configurations.testImplementation { extendsFrom(configurations.compileOnly.get()) }

tasks.register<JavaExec>("permissionLifecycleTest") {
 dependsOn("testClasses")
 classpath = sourceSets.test.get().runtimeClasspath
 providers.gradleProperty("spwHostJar").orNull?.let { classpath += files(it) }
 mainClass.set("vip.mystery0.spw.navidrome.PermissionLifecycleTest")
 enableAssertions = true
}

mapOf(
 "coreTest" to "vip.mystery0.spw.navidrome.CoreTest",
 "viewRequestTest" to "vip.mystery0.spw.navidrome.ui.ViewRequestTest",
 "credentialStoreTest" to "vip.mystery0.spw.navidrome.credentials.CredentialStoreTest"
).forEach { (taskName, entryPoint) ->
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
