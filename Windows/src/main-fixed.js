const { app, BrowserWindow, dialog, ipcMain } = require("electron");
const fs = require("fs/promises");
const fsSync = require("fs");
const path = require("path");
const { pathToFileURL } = require("url");
const http = require("http");
const https = require("https");
const { parseFile, parseBuffer } = require("music-metadata");

const AUDIO_EXTENSIONS = new Set([".mp3", ".flac", ".wav", ".m4a", ".ogg", ".aac"]);
const SETTINGS_FILE = "settings.json";
const MAX_WEBDAV_SCAN_FILES = 5000;
const APP_ICON_PATH = path.join(__dirname, "build", "icon.ico");
let webdavLibrariesCache = [];
let splashWindow = null;

function parseTrackInfo(filePath) {
  const baseName = path.basename(filePath, path.extname(filePath));
  const splitIndex = baseName.indexOf(" - ");
  if (splitIndex > 0) {
    return {
      artist: baseName.slice(0, splitIndex).trim(),
      title: baseName.slice(splitIndex + 3).trim(),
    };
  }
  return { artist: "Unknown Artist", title: baseName };
}

function toDataUrl(picture) {
  if (!picture || !picture.data || !picture.format) {
    return "";
  }
  return `data:${picture.format};base64,${Buffer.from(picture.data).toString("base64")}`;
}

function mimeFromExtension(ext) {
  switch (ext.toLowerCase()) {
    case ".jpg":
    case ".jpeg":
      return "image/jpeg";
    case ".png":
      return "image/png";
    case ".webp":
      return "image/webp";
    default:
      return "application/octet-stream";
  }
}

function sniffAudioMime(buffer) {
  if (!buffer || buffer.length < 4) {
    return "audio/mpeg";
  }
  const header = buffer.subarray(0, 4).toString("ascii");
  if (header === "fLaC") return "audio/flac";
  if (header === "RIFF") return "audio/wav";
  if (header === "OggS") return "audio/ogg";
  if (header.startsWith("ID3")) return "audio/mpeg";
  if (buffer[0] === 0xff && (buffer[1] & 0xe0) === 0xe0) return "audio/mpeg";
  return "audio/mpeg";
}

function guessQualityByExtension(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  switch (ext) {
    case ".flac":
      return "FLAC";
    case ".wav":
      return "WAV";
    case ".m4a":
      return "M4A";
    case ".ogg":
      return "OGG";
    case ".aac":
      return "AAC";
    case ".mp3":
      return "MP3";
    default:
      return "未知音质";
  }
}

function buildQualityLabel(metadata, filePath) {
  if (!metadata || !metadata.format) {
    return guessQualityByExtension(filePath);
  }
  const fmt = metadata.format;
  const container = (fmt.container || "").toUpperCase();
  const codec = (fmt.codec || "").toUpperCase();
  const lossless = fmt.lossless;
  const sampleRate = Number(fmt.sampleRate || 0);
  const bitDepth = Number(fmt.bitsPerSample || 0);
  const bitrate = Number(fmt.bitrate || 0);

  const base =
    container.includes("FLAC") || codec.includes("FLAC")
      ? "FLAC"
      : container.includes("WAVE") || container.includes("WAV") || codec.includes("PCM")
        ? "WAV"
        : container.includes("MPEG") || codec.includes("MP3")
          ? "MP3"
          : container.includes("MP4") || container.includes("M4A") || codec.includes("AAC")
            ? "M4A/AAC"
            : container.includes("OGG") || codec.includes("VORBIS") || codec.includes("OPUS")
              ? "OGG"
              : guessQualityByExtension(filePath);

  const parts = [base];
  if (sampleRate > 0) parts.push(`${(sampleRate / 1000).toFixed(sampleRate % 1000 === 0 ? 0 : 1)}kHz`);
  if (bitDepth > 0 && lossless) parts.push(`${bitDepth}bit`);
  if (bitrate > 0) parts.push(`${Math.round(bitrate / 1000)}kbps`);
  return parts.join(" · ");
}

function extractLyricsFromCommon(commonLyrics) {
  if (!Array.isArray(commonLyrics) || commonLyrics.length === 0) return "";
  const pad = (num, width = 2) => String(Math.max(0, Math.floor(Number(num) || 0))).padStart(width, "0");
  const toLrcTime = (timestampMs) => {
    const ts = Number(timestampMs);
    if (!Number.isFinite(ts) || ts < 0) return "";
    const minute = Math.floor(ts / 60000);
    const second = Math.floor((ts % 60000) / 1000);
    const millis = ts % 1000;
    return `[${pad(minute)}:${pad(second)}.${pad(millis, 3)}]`;
  };
  const parts = [];
  for (const item of commonLyrics) {
    if (!item) continue;
    if (typeof item === "string") {
      parts.push(item);
      continue;
    }
    if (typeof item !== "object") continue;

    if (Array.isArray(item.syncText) && item.syncText.length > 0) {
      const syncedLines = item.syncText
        .map((line) => {
          if (!line || typeof line.text !== "string") return "";
          const text = line.text.trim();
          if (!text) return "";
          const tag = toLrcTime(line.timestamp);
          return tag ? `${tag}${text}` : text;
        })
        .filter(Boolean)
        .join("\n");
      if (syncedLines) {
        parts.push(syncedLines);
        continue;
      }
    }

    if (typeof item.text === "string") {
      parts.push(item.text);
    }
  }
  return parts.join("\n").trim();
}

function extractLyricsFromNative(nativeMap) {
  if (!nativeMap || typeof nativeMap !== "object") return "";
  const sections = [];
  const pad = (num, width = 2) => String(Math.max(0, Math.floor(Number(num) || 0))).padStart(width, "0");
  const toLrcTime = (rawTimestamp) => {
    const ts = Number(rawTimestamp);
    if (!Number.isFinite(ts) || ts < 0) return "";
    const totalMs = Math.floor(ts);
    const minute = Math.floor(totalMs / 60000);
    const second = Math.floor((totalMs % 60000) / 1000);
    const millis = totalMs % 1000;
    return `[${pad(minute)}:${pad(second)}.${pad(millis, 3)}]`;
  };
  for (const tags of Object.values(nativeMap)) {
    if (!Array.isArray(tags)) continue;
    for (const tag of tags) {
      if (!tag || !tag.id) continue;
      const id = String(tag.id).toUpperCase();
      const value = tag.value;
      if (id === "USLT" || id === "LYRICS" || id === "UNSYNCEDLYRICS") {
        if (typeof value === "string") sections.push(value);
        else if (value && typeof value.text === "string") sections.push(value.text);
        else if (Array.isArray(value)) sections.push(value.join("\n"));
        continue;
      }
      if (id === "SYLT" && Array.isArray(value)) {
        const lines = value
          .map((item) => {
            if (!item || typeof item.text !== "string") return "";
            const text = item.text.trim();
            if (!text) return "";
            const ts =
              Number.isFinite(Number(item.timestamp)) ? Number(item.timestamp) : Number(item.timeStamp);
            const lrcTime = toLrcTime(ts);
            return lrcTime ? `${lrcTime}${text}` : text;
          })
          .filter(Boolean)
          .join("\n");
        if (lines) sections.push(lines);
      }
      if (id === "COMMENT" || id === "COMM") {
        if (typeof value === "string") sections.push(value);
        else if (value && typeof value.text === "string") {
          const desc = (value.description || "").toLowerCase();
          if (!desc || desc.includes("lyric")) sections.push(value.text);
        }
      }
    }
  }
  return sections.join("\n").trim();
}

async function findExternalCoverDataUrl(filePath) {
  const parsed = path.parse(filePath);
  const candidates = [
    `${parsed.name}.jpg`,
    `${parsed.name}.jpeg`,
    `${parsed.name}.png`,
    `${parsed.name}.webp`,
    "cover.jpg",
    "cover.jpeg",
    "cover.png",
    "folder.jpg",
    "folder.jpeg",
    "folder.png",
    "front.jpg",
    "front.jpeg",
    "front.png",
    "album.jpg",
    "album.jpeg",
    "album.png",
  ];
  for (const fileName of candidates) {
    const fullPath = path.join(parsed.dir, fileName);
    try {
      await fs.access(fullPath);
      const bytes = await fs.readFile(fullPath);
      const mime = mimeFromExtension(path.extname(fullPath));
      return `data:${mime};base64,${bytes.toString("base64")}`;
    } catch {
      continue;
    }
  }
  return "";
}

async function parseMetadataRobust(filePath) {
  try {
    return await parseFile(filePath, { skipCovers: false });
  } catch {
    const buffer = fsSync.readFileSync(filePath);
    const mimeType = sniffAudioMime(buffer);
    return parseBuffer(buffer, { mimeType }, { skipCovers: false });
  }
}

async function readEmbeddedMetadata(filePath) {
  try {
    const metadata = await parseMetadataRobust(filePath);
    const fallback = parseTrackInfo(filePath);
    const lyricsFromCommon = extractLyricsFromCommon(metadata.common.lyrics);
    const lyricsFromNative = extractLyricsFromNative(metadata.native);
    const embeddedCover = toDataUrl(metadata.common.picture?.[0]);
    const externalCover = embeddedCover ? "" : await findExternalCoverDataUrl(filePath);
    return {
      title: metadata.common.title || fallback.title,
      artist: metadata.common.artist || fallback.artist,
      embeddedCover: embeddedCover || externalCover || "",
      embeddedLyrics: lyricsFromCommon || lyricsFromNative || "",
      qualityLabel: buildQualityLabel(metadata, filePath),
    };
  } catch {
    const fallback = parseTrackInfo(filePath);
    return {
      title: fallback.title,
      artist: fallback.artist,
      embeddedCover: "",
      embeddedLyrics: "",
      qualityLabel: guessQualityByExtension(filePath),
    };
  }
}

async function scanMusicFiles(rootDir) {
  const songs = [];
  const audioFiles = [];

  async function walk(currentDir) {
    let entries = [];
    try {
      entries = await fs.readdir(currentDir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      const fullPath = path.join(currentDir, entry.name);
      if (entry.isDirectory()) {
        await walk(fullPath);
        continue;
      }
      if (!entry.isFile()) continue;
      const ext = path.extname(entry.name).toLowerCase();
      if (!AUDIO_EXTENSIONS.has(ext)) continue;
      audioFiles.push(fullPath);
    }
  }

  await walk(rootDir);

  const maxWorkers = Math.max(1, Math.min(8, audioFiles.length));
  let cursor = 0;
  const workers = Array.from({ length: maxWorkers }, async () => {
    while (true) {
      const index = cursor;
      cursor += 1;
      if (index >= audioFiles.length) break;
      const fullPath = audioFiles[index];
      try {
        const metadata = await readEmbeddedMetadata(fullPath);
        songs.push({
          id: fullPath,
          path: fullPath,
          fileUrl: pathToFileURL(fullPath).href,
          title: metadata.title,
          artist: metadata.artist,
          embeddedCover: metadata.embeddedCover,
          embeddedLyrics: metadata.embeddedLyrics,
          qualityLabel: metadata.qualityLabel || guessQualityByExtension(fullPath),
        });
      } catch {
        // skip broken file
      }
    }
  });
  await Promise.all(workers);
  songs.sort((a, b) => a.title.localeCompare(b.title, "zh-Hans-CN"));
  return songs;
}

async function scanMusicFilesFromFolders(folders) {
  const allSongs = [];
  const dedup = new Set();
  for (const folder of folders) {
    const songs = await scanMusicFiles(folder);
    for (const song of songs) {
      if (dedup.has(song.path)) continue;
      dedup.add(song.path);
      allSongs.push(song);
    }
  }
  allSongs.sort((a, b) => a.title.localeCompare(b.title, "zh-Hans-CN"));
  return allSongs;
}

async function readExternalLyrics(trackPath) {
  const parsed = path.parse(trackPath);
  const lrcPath = path.join(parsed.dir, `${parsed.name}.lrc`);
  try {
    return await fs.readFile(lrcPath, "utf8");
  } catch {
    return "";
  }
}

function decodeLyricsBuffer(buffer) {
  if (!buffer || buffer.length === 0) return "";
  const utf8 = buffer.toString("utf8");
  if (!utf8.includes("\ufffd")) return utf8;
  try {
    return new TextDecoder("gb18030").decode(buffer);
  } catch {
    return utf8;
  }
}

function getSettingsPath() {
  return path.join(app.getPath("userData"), SETTINGS_FILE);
}

async function readSettings() {
  try {
    const raw = await fs.readFile(getSettingsPath(), "utf8");
    return JSON.parse(raw);
  } catch {
    return {};
  }
}

async function writeSettings(partial) {
  const current = await readSettings();
  const next = { ...current, ...partial };
  await fs.writeFile(getSettingsPath(), JSON.stringify(next, null, 2), "utf8");
}

function normalizeFolder(folder) {
  return path.resolve(String(folder || "").trim());
}

async function getValidLibraryFolders() {
  const settings = await readSettings();
  const rawFolders = Array.isArray(settings.libraryFolders) ? settings.libraryFolders : [];
  const unique = [...new Set(rawFolders.map(normalizeFolder).filter(Boolean))];
  const valid = [];
  for (const folder of unique) {
    try {
      const stat = await fs.stat(folder);
      if (stat.isDirectory()) valid.push(folder);
    } catch {
      // ignore invalid folder
    }
  }
  return valid;
}

async function saveLibraryFolders(folders) {
  const unique = [...new Set(folders.map(normalizeFolder).filter(Boolean))];
  await writeSettings({ libraryFolders: unique });
}

function normalizeWebdavConfig(config) {
  const protocol = String(config?.protocol || "https").toLowerCase() === "http" ? "http" : "https";
  const host = String(config?.host || "").trim();
  const username = String(config?.username || "").trim();
  const password = String(config?.password || "");
  const port = Number(config?.port || (protocol === "https" ? 443 : 80));
  const folderPathRaw = String(config?.folderPath || "/").trim() || "/";
  const folderPath = folderPathRaw.startsWith("/") ? folderPathRaw : `/${folderPathRaw}`;
  return { protocol, host, port, username, password, folderPath };
}

function buildWebdavBaseUrl(config) {
  const cfg = normalizeWebdavConfig(config);
  return `${cfg.protocol}://${cfg.host}:${cfg.port}`;
}

function buildWebdavAuthHeader(config) {
  const cfg = normalizeWebdavConfig(config);
  const token = Buffer.from(`${cfg.username}:${cfg.password}`).toString("base64");
  return `Basic ${token}`;
}

function decodeXmlEntities(value) {
  return String(value || "")
    .replaceAll("&amp;", "&")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&quot;", "\"")
    .replaceAll("&#39;", "'");
}

function parseWebdavResponses(xmlText) {
  const responses = [];
  const blocks = xmlText.match(/<[^>]*response[^>]*>[\s\S]*?<\/[^>]*response>/gi) || [];
  for (const block of blocks) {
    const hrefMatch = block.match(/<[^>]*href[^>]*>([\s\S]*?)<\/[^>]*href>/i);
    if (!hrefMatch) continue;
    const href = decodeXmlEntities(hrefMatch[1].trim());
    const isCollection =
      /<[^>]*collection\s*\/?>/i.test(block) || href.endsWith("/");
    const displayMatch = block.match(/<[^>]*displayname[^>]*>([\s\S]*?)<\/[^>]*displayname>/i);
    const displayName = displayMatch ? decodeXmlEntities(displayMatch[1].trim()) : "";
    responses.push({ href, isCollection, displayName });
  }
  return responses;
}

function extractPathFromHref(href) {
  try {
    const url = new URL(href, "https://dummy.local");
    return decodeURIComponent(url.pathname);
  } catch {
    return href;
  }
}

async function webdavPropfind(config, folderPath, depth = 1) {
  const cfg = normalizeWebdavConfig(config);
  const targetPath = folderPath.startsWith("/") ? folderPath : `/${folderPath}`;
  const xml = await new Promise((resolve, reject) => {
    const body = `<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/></d:prop></d:propfind>`;
    const transport = cfg.protocol === "https" ? https : http;
    const agent = cfg.protocol === "https" ? new https.Agent({ rejectUnauthorized: false }) : undefined;
    const req = transport.request(
      {
        protocol: `${cfg.protocol}:`,
        hostname: cfg.host,
        port: cfg.port,
        path: targetPath,
        method: "PROPFIND",
        agent,
        headers: {
          Authorization: buildWebdavAuthHeader(cfg),
          Depth: String(depth),
          "Content-Type": "application/xml; charset=utf-8",
          "Content-Length": Buffer.byteLength(body),
        },
      },
      (res) => {
        let data = "";
        res.setEncoding("utf8");
        res.on("data", (chunk) => {
          data += chunk;
        });
        res.on("end", () => {
          const code = Number(res.statusCode || 0);
          if (code !== 207 && (code < 200 || code >= 300)) {
            reject(new Error(`WebDAV 请求失败: ${code}`));
            return;
          }
          resolve(data);
        });
      },
    );
    req.on("error", (error) => reject(error));
    req.write(body);
    req.end();
  });

  const parsed = parseWebdavResponses(xml);
  const requested = decodeURIComponent(targetPath).replace(/\/+$/, "");
  return parsed
    .map((item) => {
      const p = extractPathFromHref(item.href);
      return {
        path: p,
        isCollection: item.isCollection,
        name: item.displayName || path.basename(p),
      };
    })
    .filter((item) => decodeURIComponent(item.path).replace(/\/+$/, "") !== requested);
}

function createWebdavTrack(config, filePath) {
  const cfg = normalizeWebdavConfig(config);
  const info = parseTrackInfo(filePath);
  const normalizedPath = filePath.startsWith("/") ? filePath : `/${filePath}`;
  const encodedPath = encodeURI(normalizedPath);
  const fileUrl = `${cfg.protocol}://${cfg.host}:${cfg.port}${encodedPath}`;
  return {
    id: `webdav:${cfg.host}:${cfg.port}:${filePath}`,
    path: `webdav:${filePath}`,
    fileUrl,
    title: info.title,
    artist: info.artist,
    embeddedCover: "",
    embeddedLyrics: "",
    qualityLabel: guessQualityByExtension(filePath),
  };
}

async function scanWebdavLibrary(libraryConfig) {
  const cfg = normalizeWebdavConfig(libraryConfig);
  const queue = [cfg.folderPath];
  const visited = new Set();
  const tracks = [];

  while (queue.length > 0 && tracks.length < MAX_WEBDAV_SCAN_FILES) {
    const current = queue.shift();
    if (visited.has(current)) continue;
    visited.add(current);

    let items = [];
    try {
      items = await webdavPropfind(cfg, current, 1);
    } catch {
      continue;
    }

    for (const item of items) {
      if (item.isCollection) {
        queue.push(item.path);
      } else {
        const ext = path.extname(item.path).toLowerCase();
        if (AUDIO_EXTENSIONS.has(ext)) tracks.push(createWebdavTrack(cfg, item.path));
      }
      if (tracks.length >= MAX_WEBDAV_SCAN_FILES) break;
    }
  }
  return tracks;
}

async function getWebdavLibraries() {
  const settings = await readSettings();
  const list = Array.isArray(settings.webdavLibraries) ? settings.webdavLibraries : [];
  webdavLibrariesCache = list;
  return list;
}

async function saveWebdavLibraries(libraries) {
  const next = Array.isArray(libraries) ? libraries : [];
  await writeSettings({ webdavLibraries: next });
  webdavLibrariesCache = next;
}

function getWebdavAuthForUrl(targetUrl) {
  try {
    const url = new URL(targetUrl);
    const protocol = url.protocol.replace(":", "");
    const hostname = url.hostname;
    const port = Number(url.port || (protocol === "https" ? 443 : 80));
    return webdavLibrariesCache.find(
      (item) =>
        item &&
        item.protocol === protocol &&
        item.host === hostname &&
        Number(item.port) === port,
    );
  } catch {
    return null;
  }
}

function isTrustedHttpsWebdavHost(hostname, port) {
  const host = String(hostname || "").trim().toLowerCase();
  if (!host) return false;
  const normalizedPort = Number(port || 443);
  return webdavLibrariesCache.some((item) => {
    if (!item) return false;
    const protocol = String(item.protocol || "").toLowerCase();
    const itemHost = String(item.host || "").trim().toLowerCase();
    const itemPort = Number(item.port || 443);
    return protocol === "https" && itemHost === host && itemPort === normalizedPort;
  });
}

function guessAudioMimeFromPath(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  switch (ext) {
    case ".flac":
      return "audio/flac";
    case ".wav":
      return "audio/wav";
    case ".ogg":
      return "audio/ogg";
    case ".m4a":
      return "audio/mp4";
    default:
      return "audio/mpeg";
  }
}

async function webdavDownloadBytes(config, filePath, maxBytes = 4 * 1024 * 1024) {
  const cfg = normalizeWebdavConfig(config);
  const requestPath = encodeURI(filePath.startsWith("/") ? filePath : `/${filePath}`);
  const transport = cfg.protocol === "https" ? https : http;
  const agent = cfg.protocol === "https" ? new https.Agent({ rejectUnauthorized: false }) : undefined;

  return new Promise((resolve, reject) => {
    const req = transport.request(
      {
        protocol: `${cfg.protocol}:`,
        hostname: cfg.host,
        port: cfg.port,
        path: requestPath,
        method: "GET",
        agent,
        headers: {
          Authorization: buildWebdavAuthHeader(cfg),
          Range: `bytes=0-${maxBytes - 1}`,
        },
      },
      (res) => {
        const code = Number(res.statusCode || 0);
        if (code < 200 || code >= 300) {
          reject(new Error(`获取文件失败: ${code}`));
          res.resume();
          return;
        }
        const chunks = [];
        let size = 0;
        res.on("data", (chunk) => {
          if (size >= maxBytes) {
            return;
          }
          const safeChunk =
            size + chunk.length > maxBytes ? chunk.subarray(0, maxBytes - size) : chunk;
          chunks.push(safeChunk);
          size += safeChunk.length;
        });
        res.on("end", () => resolve(Buffer.concat(chunks)));
      },
    );
    req.on("error", (error) => reject(error));
    req.setTimeout(12000, () => {
      req.destroy(new Error("WebDAV 请求超时"));
    });
    req.end();
  });
}

async function readWebdavExternalLyrics(config, audioFilePath) {
  const parsed = path.posix.parse(audioFilePath);
  const lrcPath = path.posix.join(parsed.dir || "/", `${parsed.name}.lrc`);
  try {
    const bytes = await webdavDownloadBytes(config, lrcPath, 512 * 1024);
    return decodeLyricsBuffer(bytes).trim();
  } catch {
    return "";
  }
}

async function readWebdavEmbeddedMetadata(config, filePath, fallbackTitle, fallbackArtist) {
  const fallback = {
    title: fallbackTitle || parseTrackInfo(filePath).title,
    artist: fallbackArtist || parseTrackInfo(filePath).artist,
    embeddedCover: "",
    embeddedLyrics: "",
    qualityLabel: guessQualityByExtension(filePath),
  };
  try {
    const bytes = await webdavDownloadBytes(config, filePath);
    const metadata = await parseBuffer(
      bytes,
      { mimeType: guessAudioMimeFromPath(filePath) },
      { skipCovers: false },
    );
    const lyricsFromCommon = extractLyricsFromCommon(metadata.common.lyrics);
    const lyricsFromNative = extractLyricsFromNative(metadata.native);
    return {
      title: metadata.common.title || fallback.title,
      artist: metadata.common.artist || fallback.artist,
      embeddedCover: toDataUrl(metadata.common.picture?.[0]),
      embeddedLyrics: lyricsFromCommon || lyricsFromNative || "",
      qualityLabel: buildQualityLabel(metadata, filePath),
    };
  } catch {
    return fallback;
  }
}

function closeSplashWindow() {
  if (!splashWindow || splashWindow.isDestroyed()) return;
  splashWindow.close();
  splashWindow = null;
}

function createSplashWindow() {
  splashWindow = new BrowserWindow({
    width: 980,
    height: 620,
    frame: false,
    resizable: false,
    movable: true,
    minimizable: false,
    maximizable: false,
    alwaysOnTop: true,
    show: true,
    backgroundColor: "#000000",
    autoHideMenuBar: true,
    icon: APP_ICON_PATH,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  splashWindow.on("closed", () => {
    splashWindow = null;
  });

  splashWindow.loadFile(path.join(__dirname, "renderer-fixed", "splash.html"));
}

function createWindow() {
  const mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 1360,
    minHeight: 760,
    frame: false,
    titleBarStyle: "hidden",
    backgroundColor: "#0f1017",
    autoHideMenuBar: true,
    show: false,
    icon: APP_ICON_PATH,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  const emitMaxState = () => {
    mainWindow.webContents.send("window:maximized-changed", mainWindow.isMaximized());
  };
  mainWindow.on("maximize", emitMaxState);
  mainWindow.on("unmaximize", emitMaxState);
  mainWindow.setAspectRatio(1400 / 900);

  const fallbackTimer = setTimeout(() => {
    closeSplashWindow();
    if (!mainWindow.isDestroyed()) {
      mainWindow.show();
    }
  }, 5000);

  mainWindow.once("ready-to-show", () => {
    clearTimeout(fallbackTimer);
    closeSplashWindow();
    if (!mainWindow.isDestroyed()) {
      mainWindow.show();
    }
  });

  mainWindow.loadFile(path.join(__dirname, "renderer-fixed", "index.html"));
  return mainWindow;
}

function openMainWithSplash() {
  createSplashWindow();
  return createWindow();
}

app.whenReady().then(() => {
  app.on("login", (event, _webContents, request, authInfo, callback) => {
    if (!authInfo || authInfo.isProxy) {
      return;
    }
    const matched = getWebdavAuthForUrl(request.url || "");
    if (!matched) {
      return;
    }
    event.preventDefault();
    callback(matched.username || "", matched.password || "");
  });

  app.on("certificate-error", (event, _webContents, url, _error, _certificate, callback) => {
    try {
      const parsed = new URL(url);
      if (
        parsed.protocol === "https:" &&
        isTrustedHttpsWebdavHost(parsed.hostname, Number(parsed.port || 443))
      ) {
        event.preventDefault();
        callback(true);
        return;
      }
    } catch {
      // ignore
    }
    callback(false);
  });

  const { session } = require("electron");
  const ses = session.defaultSession;
  ses.webRequest.onBeforeSendHeaders((details, callback) => {
    const matched = getWebdavAuthForUrl(details.url || "");
    if (matched) {
      const token = Buffer.from(`${matched.username}:${matched.password}`).toString("base64");
      details.requestHeaders.Authorization = `Basic ${token}`;
    }
    callback({ requestHeaders: details.requestHeaders });
  });

  ses.setCertificateVerifyProc((request, callback) => {
    if (isTrustedHttpsWebdavHost(request.hostname, Number(request.port || 443))) {
      callback(0);
      return;
    }
    callback(-3);
  });

  openMainWithSplash();
  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) openMainWithSplash();
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});

ipcMain.handle("music:get-library-folders", async () => {
  const folders = await getValidLibraryFolders();
  await saveLibraryFolders(folders);
  return folders;
});

ipcMain.handle("music:add-library-folder", async () => {
  const result = await dialog.showOpenDialog({
    title: "添加音乐文件夹",
    properties: ["openDirectory"],
  });
  if (result.canceled || result.filePaths.length === 0) return getValidLibraryFolders();
  const selected = normalizeFolder(result.filePaths[0]);
  const folders = await getValidLibraryFolders();
  const nextFolders = [...new Set([...folders, selected])];
  await saveLibraryFolders(nextFolders);
  return nextFolders;
});

ipcMain.handle("music:remove-library-folder", async (_event, folder) => {
  const target = normalizeFolder(folder);
  const folders = await getValidLibraryFolders();
  const nextFolders = folders.filter((item) => normalizeFolder(item) !== target);
  await saveLibraryFolders(nextFolders);
  return nextFolders;
});

ipcMain.handle("music:scan-all-libraries", async () => {
  const localFolders = await getValidLibraryFolders();
  await saveLibraryFolders(localFolders);
  const localSongs = await scanMusicFilesFromFolders(localFolders);
  const webdavLibraries = await getWebdavLibraries();
  const webdavSongs = [];
  for (const lib of webdavLibraries) {
    try {
      const songs = await scanWebdavLibrary(lib);
      webdavSongs.push(...songs);
    } catch {
      // ignore broken webdav source
    }
  }
  return [...localSongs, ...webdavSongs].sort((a, b) => a.title.localeCompare(b.title, "zh-Hans-CN"));
});

ipcMain.handle("webdav:get-libraries", async () => getWebdavLibraries());

ipcMain.handle("webdav:get-track-metadata", async (_event, track) => {
  if (!track || !track.fileUrl) {
    return {
      title: track?.title || "",
      artist: track?.artist || "",
      embeddedCover: "",
      embeddedLyrics: "",
      qualityLabel: track?.qualityLabel || "未知音质",
    };
  }
  const matched = getWebdavAuthForUrl(track.fileUrl);
  if (!matched) {
    return {
      title: track.title || "",
      artist: track.artist || "",
      embeddedCover: "",
      embeddedLyrics: "",
      qualityLabel: track.qualityLabel || "未知音质",
    };
  }
  const url = new URL(track.fileUrl);
  const filePath = decodeURIComponent(url.pathname);
  return readWebdavEmbeddedMetadata(matched, filePath, track.title, track.artist);
});

ipcMain.handle("webdav:test-connection", async (_event, config) => {
  const cfg = normalizeWebdavConfig(config);
  if (!cfg.host || !cfg.username) {
    return { ok: false, message: "请填写主机和账号" };
  }
  try {
    const items = await webdavPropfind(cfg, cfg.folderPath || "/", 1);
    return { ok: true, message: "连接成功", count: items.length };
  } catch (error) {
    return { ok: false, message: `连接失败: ${error.message}` };
  }
});

ipcMain.handle("webdav:list-directory", async (_event, config) => {
  const cfg = normalizeWebdavConfig(config);
  const targetPath = config?.path || cfg.folderPath || "/";
  const items = await webdavPropfind(cfg, targetPath, 1);
  return items
    .filter((item) => item.isCollection)
    .map((item) => ({ path: item.path, name: item.name || path.basename(item.path) || "/" }));
});

ipcMain.handle("webdav:add-library", async (_event, config) => {
  const cfg = normalizeWebdavConfig(config);
  if (!cfg.host || !cfg.username || !cfg.folderPath) {
    throw new Error("WebDAV 信息不完整");
  }
  const list = await getWebdavLibraries();
  const id = `${cfg.protocol}://${cfg.host}:${cfg.port}${cfg.folderPath}`.toLowerCase();
  const next = [
    ...list.filter((item) => item.id !== id),
    { id, ...cfg },
  ];
  await saveWebdavLibraries(next);
  return next;
});

ipcMain.handle("webdav:remove-library", async (_event, id) => {
  const list = await getWebdavLibraries();
  const next = list.filter((item) => item.id !== id);
  await saveWebdavLibraries(next);
  return next;
});

ipcMain.handle("music:load-lyrics", async (_event, trackPath) => {
  if (!trackPath) return "";
  if (typeof trackPath === "string") {
    return readExternalLyrics(trackPath);
  }

  const maybeTrack = trackPath;
  const maybePath = String(maybeTrack.path || "");
  const maybeUrl = String(maybeTrack.fileUrl || "");
  if (maybePath.startsWith("webdav:") || maybeUrl.startsWith("http://") || maybeUrl.startsWith("https://")) {
    const matched = getWebdavAuthForUrl(maybeUrl);
    if (!matched) return "";
    try {
      const filePath = decodeURIComponent(new URL(maybeUrl).pathname);
      return readWebdavExternalLyrics(matched, filePath);
    } catch {
      return "";
    }
  }

  return readExternalLyrics(maybePath);
});

ipcMain.on("window:minimize", (event) => {
  const win = BrowserWindow.fromWebContents(event.sender);
  if (win) win.minimize();
});

ipcMain.on("window:toggle-maximize", (event) => {
  const win = BrowserWindow.fromWebContents(event.sender);
  if (!win) return;
  if (win.isMaximized()) win.unmaximize();
  else win.maximize();
});

ipcMain.on("window:close", (event) => {
  const win = BrowserWindow.fromWebContents(event.sender);
  if (win) win.close();
});

ipcMain.handle("window:is-maximized", (event) => {
  const win = BrowserWindow.fromWebContents(event.sender);
  return win ? win.isMaximized() : false;
});
