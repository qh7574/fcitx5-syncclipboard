# Fcitx5 Android 剪贴板同步插件 (SyncClipboard)

这是一个为 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 开发的插件，用于实现与 [SyncClipboard](https://github.com/Jeric-X/SyncClipboard)服务的剪贴板同步功能。

注意：fcitx5-android目前只允许同签名插件注入并运行，请到 [Fcitx5-Android-ResignforSyncclipboard](https://github.com/qh7574/Fcitx5-Android-ResignforSyncclipboard) 下载重新签名后的主程序以及插件APK并安装，注意备份数据！

## 功能特性

*   **双向同步**：支持将手机剪贴板推送到服务器，以及从服务器拉取最新剪贴板内容到手机。
*   **智能防抖**：避免本地与远程内容的循环更新。
*   **功耗优化**：息屏或省电模式下动态降低频率或关闭轮询，以减少电池消耗，同时使用兼容 WebDAV ETag 减少流量消耗。
*   **格式支持**：支持同步文本、图片、文件等格式，智能复制 uri 到剪贴板。
*   **断网重试**：后台轮询机制包含错误处理和重试逻辑。

## 编译指南

本项目为独立的 Android Studio 项目。

1.  使用 Android Studio 打开 `fcitx5-syncclipboard` 文件夹。
2.  等待 Gradle 同步完成。
3.  点击菜单栏 `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`。
4.  编译生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk` (调试版) 或 `release` 目录下。

## 安装与配置

1.  **安装插件**：
    *   将编译好的 APK 安装到已安装 Fcitx5 Android 的设备上。
2.  **启用插件**：
    *   打开 Fcitx5 设置 -> 插件 -> 确保 "Clipboard Sync" 已启用。
3.  **配置服务**：
    *   在应用列表找到 "Clipboard Sync" (或通过 Fcitx5 插件设置进入)。
    *   **服务器地址**：输入 SyncClipboard 服务器地址（兼容WebDAV） (例如 `http://192.168.1.50:5000/clipboard`)。
    *   **用户名/密码**：输入 Basic Auth 认证信息。
    *   **同步间隔**：设置后台轮询间隔（默认为 3 秒）。
    *   **下载目录**：设置同步非文本文件（如图片、文件）时的保存目录。
    *   点击“测试连接”按钮验证配置是否正确。
    *   **注意**：快速同步开关仍在调试中，请保持开启。
4.  **注意事项**：
    *   请开放插件的后台运行、自启动以及后台写入剪贴板权限，授予后点击fcitx设置-右上角开发者-重启fcitx实例，确保插件正确连接。



## 技术架构

*   **语言**：Kotlin
*   **通信**：AIDL (与 Fcitx5 交互), HTTP/JSON (与服务器交互)
*   **依赖**：OkHttp, Kotlinx Serialization, AndroidX Preference

## 日志调试

使用 Logcat 查看插件运行状态：
```bash
adb logcat -s FcitxClipboardSync
```
日志将包含连接状态、上传/下载进度及错误信息。


## 感谢

*   [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 项目提供了输入法框架。
*   [SyncClipboard](https://github.com/Jeric-X/SyncClipboard) 项目提供了剪贴板同步 API。
