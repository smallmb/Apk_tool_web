## Automatic Remapping of ResourceId
We currently prevent resourceIds from changing, by utilizing the `public.xml` file which makes the resources public, but
then prevents them to be used in some locations (`android:scheme`). The correct fix would be to record the resourceIds
and use dexlib2 (no regular expressions) to rewrite them to the new resourceId after the `resources.arsc` is built.

This would be a lookup table of old->new resourceIds leveraging the API of dexlib2 to do the replacement. Doing this
properly would nullify the need to do [#191](https://github.com/iBotPeaches/Apktool/issues/191)

Suggestions: [#244](https://github.com/iBotPeaches/Apktool/issues/244)
Discussions: [#2062](https://github.com/iBotPeaches/Apktool/issues/2062)

## Implicit Qualifiers Cleanup
Currently we have a mismatch between reading the folders and reading the qualifiers which leads to a mismatch between
implicit qualifiers like version (-v4, v13, etc).

This was first spotted in bug [#1272](https://github.com/iBotPeaches/Apktool/issues/1272).

This was attempted to be fixed in [!1758](https://github.com/iBotPeaches/Apktool/pull/1758/files), but had to be
reverted due to [this](https://github.com/iBotPeaches/Apktool/issues/1272#issuecomment-379345005).

Suggestions: [#2237](https://github.com/iBotPeaches/Apktool/issues/2237)

## Qualifier Plugin System
For some OEMs, past and present. They re-use qualifiers that AOSP ends up using. This with CTS is becoming very
rare and pretty much a problem of the past, but now custom modifications and more "off the cuff" OEMs are doing
it.

Apktool can't do anything because it stays true to AOSP. It would need a plugin system that controls how to
read the qualifiers. Or even an override file.

Suggestions: [#1420](https://github.com/iBotPeaches/Apktool/issues/1420), [#2474](https://github.com/iBotPeaches/Apktool/issues/2474)

## Non-reference Resources
Some applications may shove resources into the /res folder, but have no references to them. Apktool follows
the resource table, so these files are effectively abandoned.

Crawling the filesystem for non-checked files would be slow especially having to cross check with already
parsed files.

Suggestions: [#1366](https://github.com/iBotPeaches/Apktool/issues/1366)

## Android Support
Folks have requested running Apktool on device itself. This has been a challenge due to the arch requirements
that would be placed on the aapt2/aapt binaries.

Suggestions: [#2811](https://github.com/iBotPeaches/Apktool/issues/2811)

## Split APK Support
Applications are further getting split on qualifiers. Apktool has been built on the assumption of one apk.

Suggestions: [#2283](https://github.com/iBotPeaches/Apktool/issues/2283), [#2218](https://github.com/iBotPeaches/Apktool/issues/2218), [#2880](https://github.com/iBotPeaches/Apktool/issues/2880)

## Web 文件预览与 DEX 编辑

- [x] 项目文件左右双卡片预览布局与固定高度。
- [x] 文本、图片和 DEX 文件类型识别。
- [x] 20MB 以内文件预览与内部滚动。
- [x] 接入内嵌 jadx 引擎（修改版 jadx-core + smali 3.0.9）：DEX 类列表、Java/Smali 预览、Smali 编辑 -> 内存汇编 -> 实时重反编译、补丁 DEX/APK 导出。
- [ ] DEX 工作台持久化补丁会话（服务重启后恢复编辑状态）。
- [ ] 导出 APK 自动调试签名（apksig），并引入 D8 重建 multidex 前置校验。

## Version relevant framework files
Applications are built against a specific framework version. Apktool ships with the latest framework file at that
time of release. So an application built with API 33 may be recompiled with API 36 which leads to issues.

Apktool packing 15+ older framework files would bloat the distribution. A better solution would be to have a
central repository of framework files that Apktool can download on demand, but that would require hosting
and maintenance.

Suggestions: [#3946](https://github.com/iBotPeaches/Apktool/issues/3946)
