plugins {
    application
}

application {
    mainClass.set("brut.apktool.web.ApktoolWebServer")
}

// 中文注释：DEX 编辑与实时预览引擎（jadx）要求 Java 11+，仅本模块提升版本，不影响 CLI 的 Java 8 目标。
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    // 中文注释：jadx 引擎（含本仓库修改版 jadx-core 的 reloadClassData 能力），jar 由 jadx-master 构建后拷入。
    implementation(fileTree("libs/jadx") { include("*.jar") })
    // 中文注释：apksig 用于导出 APK 的调试签名，来自 Google Maven。
    implementation("com.android.tools.build:apksig:8.5.2")
    // 中文注释：smali 汇编/反汇编与 dexlib2（dex 结构读写、DexPool 重建），版本与 jadx 对齐避免类冲突。
    implementation("com.android.tools.smali:smali:3.0.9")
    implementation("com.android.tools.smali:smali-baksmali:3.0.9")
    // 中文注释：jadx-core 依赖的 gson 与日志门面（nop 绑定避免本地控制台噪音）。
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("org.slf4j:slf4j-nop:2.0.13")
}

// 中文注释：显式指定 UTF-8，确保 Windows JDK 8 不会用系统 GBK 误读中文源码。
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// 中文注释：后端工作目录固定为项目根目录，确保相对路径 web-work 不会落到 apktool-web 模块内。
tasks.named<JavaExec>("run") {
    workingDir(rootProject.projectDir)
}
