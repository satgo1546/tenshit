plugins {
	kotlin("jvm") version "1.6.10"
	application
	id("com.github.johnrengelman.shadow") version "7.1.2"
}

repositories {
	mavenCentral()
}

dependencies {
	// 开发时使用mirai-core-api，运行时提供mirai-core。
	api(platform("net.mamoe:mirai-bom:2.10.0"))
	api("net.mamoe:mirai-core-api") // 编译代码使用
	runtimeOnly("net.mamoe:mirai-core") // 运行时使用
	api("net.mamoe:mirai-silk-converter:0.0.5") // 自动将不支持的音频格式转换为受支持的音频格式
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
	kotlinOptions.jvmTarget = "1.8"
	kotlinOptions.freeCompilerArgs = listOf("-Xjvm-default=all", "-Xopt-in=kotlin.RequiresOptIn")
}

application {
	mainClass.set("MainKt")
}

// 执行ShadowJar任务，以获得可执行JAR包。
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
	archiveVersion.set("")
	archiveClassifier.set("")
	minimize {
		exclude(dependency("net.mamoe:mirai-core:.*"))
	}
	// 打包一切源文件！
	// 我写的Kotlin源文件和构建脚本各只有一个，真是太便利了。
	from(".") {
		include("build.gradle.kts")
	}
	from("src/main/kotlin") {
		include("Main.kt")
	}
}
