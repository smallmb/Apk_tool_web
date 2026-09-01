plugins {
    application
}

application {
    mainClass.set("brut.apktool.web.ApktoolWebServer")
}

dependencies {
    implementation(project(":brut.apktool:apktool-lib"))
}

// 中文注释：显式指定 UTF-8，确保 Windows JDK 8 不会用系统 GBK 误读中文源码。
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// 中文注释：后端工作目录固定为项目根目录，确保相对路径 web-work 不会落到 apktool-web 模块内。
tasks.named<JavaExec>("run") {
    workingDir(rootProject.projectDir)
}
