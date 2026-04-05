# Mona Music Windows 端说明

## 1. 项目简介

Windows 端基于 Electron 实现，提供本地音乐与 WebDAV 音乐的统一播放体验。

主要能力：

- 本地目录扫描（递归）
- WebDAV 曲库接入（测试连接、浏览目录、添加/移除）
- 播放控制（播放/暂停、上一首/下一首、进度、音量）
- 播放模式（顺序、单曲循环、随机按轮）
- 歌词显示（内嵌歌词优先，其次同名 `.lrc`）
- 封面显示（内嵌封面优先，外部封面兜底）

## 2. 目录结构（核心）

- `main-fixed.js`：主进程（窗口、IPC、曲库扫描、WebDAV）
- `preload.js`：安全桥接
- `renderer-fixed/index.html`：页面结构
- `renderer-fixed/renderer.js`：前端交互逻辑
- `renderer-fixed/styles.css`：样式
- `build/icon.ico`：应用图标

## 3. 环境要求

- Node.js 18+（建议 LTS）
- npm 9+
- Windows 10/11

## 4. 开发运行

```bash
npm install
npm run start
```

## 5. 打包命令

便携版（单文件 exe）：

```bash
npm run build:portable
```

安装版（NSIS）：

```bash
npm run build:win
```

产物默认在：

- `dist/Mona Music 1.0.0.exe`
- `dist/Mona Music Setup 1.0.0.exe`
- `dist/Mona Music Setup 1.0.0.exe.blockmap`

## 6. 使用说明

### 6.1 添加本地曲库

1. 点击底部左侧设置按钮
2. 在“音乐目录”中点击“添加目录”
3. 选择本地音乐文件夹后自动扫描

### 6.2 添加 WebDAV 曲库

1. 打开设置页，进入 WebDAV 区域
2. 填写协议、主机、端口、账号、密码
3. 点击“测试连接”
4. 点击“选择远程目录”并确认
5. 点击“添加 WebDAV 曲库”

### 6.3 播放模式

- 顺序播放：按列表顺序
- 单曲循环：重复当前歌曲
- 随机按轮：一轮内不重复，播完一轮再开启下一轮随机

## 7. 常见问题

- 首次便携版启动稍慢：属于解压初始化行为
- 部分歌曲显示“未知音质”：文件元数据不足或异常
- 歌词不滚动：歌词缺少时间轴标签（仅纯文本歌词）
