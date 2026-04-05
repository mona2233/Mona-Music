# Mona Music Android 端说明

## 1. 项目简介

Android 端基于 Kotlin + WebView + Media3（ExoPlayer）实现，目标是与 Windows 端保持一致的播放体验与核心规则。

主要能力：

- 本地媒体库读取（MediaStore）
- 本地目录读取（DocumentTree）
- WebDAV 曲库接入（连接测试、目录浏览、扫描合并）
- 原生播放内核（播放/暂停/切歌/进度控制）
- 播放模式（顺序、单曲循环、随机按轮）
- 封面与歌词解析（内嵌优先，外部兜底）

## 2. 目录结构（核心）

- `app/src/main/java/com/monamusic/android/MainActivity.kt`
- `app/src/main/java/com/monamusic/android/PlaybackModeController.kt`
- `app/src/main/assets/www/*`（主界面与资源）
- `app/src/main/res/*`（原生资源）

## 3. 构建工具链位置（外置）

为保持仓库干净，Android 构建工具链不放在项目目录内，统一放在：

- `D:/ProgramData/testprogram/BuildTools/Android/toolchain/jdk21`
- `D:/ProgramData/testprogram/BuildTools/Android/toolchain/android-sdk`

## 4. 环境与配置

### 4.1 local.properties

项目内使用：

```properties
sdk.dir=D:\\ProgramData\\testprogram\\BuildTools\\Android\\toolchain\\android-sdk
```

可参考 `local.properties.example`。

### 4.2 签名配置（Release）

1. 复制 `signing.properties.template` 为 `signing.properties`
2. 填写：
   - `storeFile`
   - `storePassword`
   - `keyAlias`
   - `keyPassword`

## 5. 构建命令

推荐一键脚本：

```bat
build-release.bat
```

或直接 Gradle：

```bat
gradlew.bat assembleRelease
gradlew.bat bundleRelease
```

产物目录：

- APK：`app/build/outputs/apk/release/app-release.apk`
- AAB：`app/build/outputs/bundle/release/`

## 6. 使用说明

### 6.1 首次启动

- 授权音频读取权限
- 进入主页面后自动加载曲库

### 6.2 本地目录管理

- 设置页可添加/移除本地目录
- 添加后会触发曲库刷新

### 6.3 WebDAV 曲库管理

- 填写连接配置并测试
- 浏览远程目录后添加
- 支持多 WebDAV 曲库并行

### 6.4 随机模式

与 Windows 端一致：

- 每轮把列表歌曲尽量随机播完且不重复
- 一轮结束后再开始下一轮随机

## 7. 发布建议

- 开发/联调可用测试签名
- 应用商店发布必须使用正式签名并重新打包
- 发布包统一归档到 `../releases/<日期>/`
