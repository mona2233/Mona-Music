 # 简要说明
 代码小白，依靠codex+gpt-5.3-codex给自己开发的一个纯本地音乐播放器
 主要是厌倦了一堆乱七八糟的广告和个人登录选项等等
 利用假期空闲时间和家里多余的绿联nas做这么个小玩意儿
 所有功能和说明都是ai开发编写，我一行代码不会，功能都是我自己用得上的，后续应该也不会再维护了，有需要的自己拿去二次开发吧

 <img width="1750" height="1124" alt="PixPin_2026-04-06_04-37-52" src="https://github.com/user-attachments/assets/84809383-6bf3-443f-96f2-76a2c4928cef" />
<img width="705" height="1205" alt="PixPin_2026-04-06_04-38-51" src="https://github.com/user-attachments/assets/5feb3dac-4558-419c-ab4c-bde48b735053" />

 
 # Mona Music

Mona Music 是一个双端音乐播放器项目，包含：

- Windows 桌面端（Electron）
- Android 移动端（Kotlin + WebView + Media3）

项目目标是提供统一的播放体验：本地曲库 + WebDAV 曲库、歌词显示、封面读取、多播放模式，以及可持续迭代的双端架构。

## 项目效果（概要）

- 统一视觉风格：主播放区、歌词区、底部控制栏
- 曲库能力：
  - 本地目录/媒体库扫描
  - WebDAV 曲库接入、目录浏览与添加
- 播放能力：
  - 播放/暂停、上一首/下一首、进度拖动、音量/静音
  - 顺序播放、单曲循环、随机按轮
- 元数据能力：
  - 优先读取内嵌封面与歌词
  - 同名 `.lrc` 歌词兜底
- 随机按轮规则：一轮内尽量不重复，列表播完后再开启下一轮随机

## 仓库结构

- `Windows/src`：Windows 桌面端源码
- `Windows/releases`：Windows 版本归档产物
- `Android/src`：Android 端源码
- `Android/releases`：Android 版本归档产物
- `../BuildTools`：双端构建工具目录（项目外置，不放在 `Mona Music` 内）

## 构建环境说明

- Windows 打包：需要 `Node.js + npm`
- Android 打包：需要 JDK + Android SDK（已统一外置到 `D:/ProgramData/testprogram/BuildTools/Android/toolchain`）

## 快速开始

### Windows

1. 进入 `Windows/src`
2. 执行 `npm install`
3. 开发运行：`npm run start`
4. 打包：
   - 便携版：`npm run build:portable`
   - 安装版：`npm run build:win`

### Android

1. 进入 `Android/src`
2. 检查 `local.properties` 的 `sdk.dir` 是否指向 BuildTools 下 SDK
3. 运行打包：`build-release.bat`（推荐）

## 迭代规范

- 后续所有源码修改仅在 `Mona Music` 目录内进行
- 后续所有打包产物仅放在各端 `releases` 子目录
- 构建工具统一放在 `D:/ProgramData/testprogram/BuildTools`
- 不提交本机缓存目录与私密签名文件
