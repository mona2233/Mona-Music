const audio = document.getElementById("audio-player");
const searchInput = document.getElementById("search-input");
const trackList = document.getElementById("track-list");
const trackTitle = document.getElementById("track-title");
const trackArtist = document.getElementById("track-artist");
const trackQuality = document.getElementById("track-quality");
const lyricsContainer = document.getElementById("lyrics-container");
const metadataLoadingTip = document.getElementById("metadata-loading-tip");
const playButton = document.getElementById("play-button");
const prevButton = document.getElementById("prev-button");
const nextButton = document.getElementById("next-button");
const modeSequenceButton = document.getElementById("mode-sequence");
const modeLoopButton = document.getElementById("mode-loop");
const modeShuffleButton = document.getElementById("mode-shuffle");
const progress = document.getElementById("progress");
const currentTimeText = document.getElementById("current-time");
const totalTimeText = document.getElementById("total-time");
const volume = document.getElementById("volume");
const volumeToggleButton = document.getElementById("volume-toggle-btn");
const volumeIconHigh = document.getElementById("volume-icon-high");
const volumeIconMute = document.getElementById("volume-icon-mute");
const vinyl = document.getElementById("vinyl");
const coverImage = document.getElementById("cover-image");
const coverFallback = document.getElementById("cover-fallback");

const topbar = document.getElementById("window-topbar");
const minBtn = document.getElementById("window-min-btn");
const maxBtn = document.getElementById("window-max-btn");
const closeBtn = document.getElementById("window-close-btn");

const openSettingsButton = document.getElementById("open-settings-button");
const settingsPanel = document.getElementById("settings-panel");
const closeSettingsButton = document.getElementById("close-settings-button");
const addLibraryPathButton = document.getElementById("add-library-path-button");
const libraryPathList = document.getElementById("library-path-list");

const webdavProtocolInput = document.getElementById("webdav-protocol");
const webdavHostInput = document.getElementById("webdav-host");
const webdavPortInput = document.getElementById("webdav-port");
const webdavUsernameInput = document.getElementById("webdav-username");
const webdavPasswordInput = document.getElementById("webdav-password");
const webdavTestButton = document.getElementById("webdav-test-button");
const webdavBrowseButton = document.getElementById("webdav-browse-button");
const webdavAddButton = document.getElementById("webdav-add-button");
const webdavStatus = document.getElementById("webdav-status");
const webdavSelectedPath = document.getElementById("webdav-selected-path");
const webdavLibraryList = document.getElementById("webdav-library-list");
const webdavBrowserModal = document.getElementById("webdav-browser-modal");
const webdavBrowserClose = document.getElementById("webdav-browser-close");
const webdavBrowserPath = document.getElementById("webdav-browser-path");
const webdavBrowserUp = document.getElementById("webdav-browser-up");
const webdavBrowserSelect = document.getElementById("webdav-browser-select");
const webdavBrowserList = document.getElementById("webdav-browser-list");

let playlist = [];
let filteredPlaylist = [];
let libraryFolders = [];
let webdavLibraries = [];
let webdavCurrentPath = "/";
let webdavBrowsingPath = "/";
let currentIndex = -1;
let lyrics = [];
let lyricNodes = [];
let activeLyricIndex = -1;
let hasTimedLyrics = false;
let isSeeking = false;
let lyricFrameId = 0;
let lyricTopSpacer = null;
let lyricBottomSpacer = null;
let lyricTargetScrollTop = 0;
let lyricCurrentScrollTop = 0;
let playMode = "sequence";
let isMuted = false;
let lastNonZeroVolume = 80;
let shuffleRoundPlayed = new Set();
let shuffleRoundQueue = [];

const PLAY_ICON = `<img src="./icons/play.svg" class="icon-img" alt="" aria-hidden="true" />`;
const PAUSE_ICON = `<img src="./icons/pause.svg" class="icon-img" alt="" aria-hidden="true" />`;

function setMetadataLoading(loading) {
  metadataLoadingTip.classList.toggle("hidden", !loading);
}

function formatTime(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) return "00:00";
  const minute = Math.floor(seconds / 60);
  const second = Math.floor(seconds % 60);
  return `${String(minute).padStart(2, "0")}:${String(second).padStart(2, "0")}`;
}

function parseLrc(content) {
  const lines = content.split(/\r?\n/);
  const parsed = [];
  for (const line of lines) {
    const timeReg = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]/g;
    const text = line.replace(timeReg, "").trim();
    if (!text) continue;
    let match;
    while ((match = timeReg.exec(line)) !== null) {
      const min = Number(match[1]);
      const sec = Number(match[2]);
      const fraction = match[3] || "0";
      const millis = Number(fraction.padEnd(3, "0"));
      parsed.push({ time: min * 60 + sec + millis / 1000, text, timed: true });
    }
  }
  parsed.sort((a, b) => a.time - b.time);
  return parsed;
}

function parsePlainLyrics(content) {
  return content
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((text) => ({ time: 0, text, timed: false }));
}

function setVinylPlaying(playing) {
  if (!vinyl.classList.contains("playing")) vinyl.classList.add("playing");
  vinyl.style.animationPlayState = playing ? "running" : "paused";
}

function playSwitchAnimation() {
  vinyl.classList.remove("switching");
  void vinyl.offsetWidth;
  vinyl.classList.add("switching");
}

function setCover(track) {
  if (track.embeddedCover) {
    coverImage.src = track.embeddedCover;
    coverImage.classList.remove("hidden");
    coverFallback.classList.add("hidden");
  } else {
    coverImage.removeAttribute("src");
    coverImage.classList.add("hidden");
    coverFallback.classList.remove("hidden");
  }
}

function renderTrackList() {
  const activeTrack = playlist[currentIndex];
  trackList.innerHTML = "";
  if (filteredPlaylist.length === 0) {
    const empty = document.createElement("p");
    empty.className = "empty-lyrics";
    empty.textContent = "未找到匹配歌曲";
    trackList.appendChild(empty);
    return;
  }
  for (const track of filteredPlaylist) {
    const button = document.createElement("button");
    button.className = "track-item";
    if (activeTrack && activeTrack.id === track.id) button.classList.add("active");
    button.innerHTML = `<span class="track-item-title">${track.title}</span><span class="track-item-artist">${track.artist}</span>`;
    button.addEventListener("click", () => {
      const index = playlist.findIndex((item) => item.id === track.id);
      if (index >= 0) loadTrack(index, true);
    });
    trackList.appendChild(button);
  }
  ensureActiveTrackVisibleInstant();
  ensureActiveTrackVisibleDeferred();
}

function ensureActiveTrackVisibleInstant() {
  const active = trackList.querySelector(".track-item.active");
  if (!active) return;
  const itemTop = active.offsetTop;
  const itemBottom = itemTop + active.offsetHeight;
  const viewTop = trackList.scrollTop;
  const viewBottom = viewTop + trackList.clientHeight;
  if (itemTop < viewTop) {
    trackList.scrollTop = itemTop;
  } else if (itemBottom > viewBottom) {
    trackList.scrollTop = itemBottom - trackList.clientHeight;
  }
}

function ensureActiveTrackVisibleDeferred() {
  requestAnimationFrame(() => {
    const active = trackList.querySelector(".track-item.active");
    if (!active) return;
    active.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "auto" });
  });
}

function filterTracks() {
  const q = searchInput.value.trim().toLowerCase();
  if (!q) filteredPlaylist = [...playlist];
  else filteredPlaylist = playlist.filter((track) => `${track.title} ${track.artist}`.toLowerCase().includes(q));
  renderTrackList();
}

function syncLyricSpacers() {
  if (!lyricTopSpacer || !lyricBottomSpacer) return;
  const spacerHeight = Math.max(0, Math.floor(lyricsContainer.clientHeight * 0.5));
  lyricTopSpacer.style.height = `${spacerHeight}px`;
  lyricBottomSpacer.style.height = `${spacerHeight}px`;
}

function renderLyrics() {
  lyricsContainer.innerHTML = "";
  lyricNodes = [];
  activeLyricIndex = -1;
  lyricTopSpacer = document.createElement("div");
  lyricTopSpacer.className = "lyrics-spacer";
  lyricBottomSpacer = document.createElement("div");
  lyricBottomSpacer.className = "lyrics-spacer";
  lyricsContainer.appendChild(lyricTopSpacer);

  if (lyrics.length === 0) {
    const empty = document.createElement("p");
    empty.className = "empty-lyrics";
    empty.textContent = "未找到歌词（内嵌歌词或同名 .lrc）";
    lyricsContainer.appendChild(empty);
    lyricsContainer.appendChild(lyricBottomSpacer);
    syncLyricSpacers();
    return;
  }

  for (const line of lyrics) {
    const div = document.createElement("div");
    div.className = `lyric-line${line.timed ? "" : " plain"}`;
    div.textContent = line.text;
    lyricsContainer.appendChild(div);
    lyricNodes.push(div);
  }
  lyricsContainer.appendChild(lyricBottomSpacer);
  syncLyricSpacers();
}

function setActiveLyric(index) {
  if (index === activeLyricIndex || index < 0 || index >= lyricNodes.length) return;
  if (activeLyricIndex >= 0) lyricNodes[activeLyricIndex].classList.remove("active");
  activeLyricIndex = index;
  const node = lyricNodes[activeLyricIndex];
  node.classList.add("active");
  const top = node.offsetTop - lyricsContainer.clientHeight * 0.5 + node.clientHeight * 0.5;
  lyricTargetScrollTop = Math.max(0, top);
}

function updateLyricsByTime(time) {
  if (!hasTimedLyrics || lyrics.length === 0) return;
  let idx = -1;
  for (let i = 0; i < lyrics.length; i += 1) {
    if (lyrics[i].time <= time + 0.08) idx = i;
    else break;
  }
  if (idx >= 0) setActiveLyric(idx);
}

function smoothLyricsScroll(force = false) {
  if (force) {
    lyricCurrentScrollTop = lyricTargetScrollTop;
    lyricsContainer.scrollTop = lyricCurrentScrollTop;
    return;
  }
  const delta = lyricTargetScrollTop - lyricCurrentScrollTop;
  lyricCurrentScrollTop = Math.abs(delta) < 0.2 ? lyricTargetScrollTop : lyricCurrentScrollTop + delta * 0.16;
  lyricsContainer.scrollTop = lyricCurrentScrollTop;
}

function stopLyricSync() {
  if (!lyricFrameId) return;
  cancelAnimationFrame(lyricFrameId);
  lyricFrameId = 0;
}

function startLyricSync() {
  if (!hasTimedLyrics) return;
  stopLyricSync();
  const tick = () => {
    updateLyricsByTime(audio.currentTime);
    smoothLyricsScroll();
    lyricFrameId = requestAnimationFrame(tick);
  };
  lyricFrameId = requestAnimationFrame(tick);
}

function setPlayMode(mode) {
  playMode = mode;
  modeSequenceButton.classList.toggle("active", mode === "sequence");
  modeLoopButton.classList.toggle("active", mode === "loop");
  modeShuffleButton.classList.toggle("active", mode === "shuffle");

  if (mode === "shuffle") {
    resetShuffleRound(true);
  } else {
    shuffleRoundPlayed = new Set();
    shuffleRoundQueue = [];
  }
}

function randomIndexExceptCurrent() {
  if (playlist.length <= 1) return currentIndex;
  let next = currentIndex;
  while (next === currentIndex) next = Math.floor(Math.random() * playlist.length);
  return next;
}

function shuffleInPlace(list) {
  for (let i = list.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1));
    [list[i], list[j]] = [list[j], list[i]];
  }
}

function refillShuffleRoundQueue() {
  const pool = [];
  for (let i = 0; i < playlist.length; i += 1) {
    if (!shuffleRoundPlayed.has(i)) pool.push(i);
  }
  shuffleInPlace(pool);
  shuffleRoundQueue = pool;
}

function resetShuffleRound(markCurrentAsPlayed = true) {
  shuffleRoundPlayed = new Set();
  if (
    markCurrentAsPlayed &&
    playlist.length > 1 &&
    currentIndex >= 0 &&
    currentIndex < playlist.length
  ) {
    shuffleRoundPlayed.add(currentIndex);
  }
  refillShuffleRoundQueue();
}

function takeNextShuffleIndex() {
  if (playlist.length === 0) return -1;
  if (playlist.length === 1) return 0;

  if (currentIndex >= 0 && currentIndex < playlist.length) {
    shuffleRoundPlayed.add(currentIndex);
    shuffleRoundQueue = shuffleRoundQueue.filter((idx) => idx !== currentIndex);
  }

  if (shuffleRoundQueue.length === 0) {
    // 当前轮全部播放完，开启下一轮随机
    resetShuffleRound(true);
  }

  if (shuffleRoundQueue.length === 0) {
    return randomIndexExceptCurrent();
  }

  const next = shuffleRoundQueue.shift();
  shuffleRoundPlayed.add(next);
  return next;
}

function updatePlayButton(playing) {
  if (playing) {
    playButton.innerHTML = PAUSE_ICON;
    playButton.title = "暂停";
    playButton.setAttribute("aria-label", "暂停");
  } else {
    playButton.innerHTML = PLAY_ICON;
    playButton.title = "播放";
    playButton.setAttribute("aria-label", "播放");
  }
}

function applyVolumeState() {
  audio.muted = isMuted;
  const value = Number(volume.value);
  audio.volume = Math.max(0, Math.min(1, value / 100));
  volumeIconHigh.classList.toggle("hidden", isMuted || value === 0);
  volumeIconMute.classList.toggle("hidden", !(isMuted || value === 0));
}

async function loadLyrics(track) {
  const embedded = (track.embeddedLyrics || "").trim();
  const external = ((await window.musicApi.loadLyrics(track)) || "").trim();

  const timedFromExternal = parseLrc(external);
  const timedFromEmbedded = parseLrc(embedded);

  if (timedFromExternal.length > 0) {
    hasTimedLyrics = true;
    lyrics = timedFromExternal;
  } else if (timedFromEmbedded.length > 0) {
    hasTimedLyrics = true;
    lyrics = timedFromEmbedded;
  } else {
    hasTimedLyrics = false;
    const merged = embedded || external || "";
    lyrics = parsePlainLyrics(merged);
  }
  renderLyrics();
  lyricTargetScrollTop = 0;
  lyricCurrentScrollTop = 0;
  lyricsContainer.scrollTop = 0;
  if (lyrics.length > 0) {
    setActiveLyric(0);
    smoothLyricsScroll(true);
  }
}

async function loadTrack(index, autoPlay) {
  if (index < 0 || index >= playlist.length) return;
  stopLyricSync();
  currentIndex = index;
  if (playMode === "shuffle") {
    shuffleRoundPlayed.add(currentIndex);
    shuffleRoundQueue = shuffleRoundQueue.filter((idx) => idx !== currentIndex);
  }
  const track = playlist[index];
  if (
    track &&
    String(track.id || "").startsWith("webdav:") &&
    !track._metadataLoaded &&
    (!track.embeddedCover || !track.embeddedLyrics)
  ) {
    setMetadataLoading(true);
    try {
      const metadata = await window.musicApi.getWebdavTrackMetadata(track);
      track.title = metadata.title || track.title;
      track.artist = metadata.artist || track.artist;
      track.embeddedCover = metadata.embeddedCover || track.embeddedCover;
      track.embeddedLyrics = metadata.embeddedLyrics || track.embeddedLyrics;
      track.qualityLabel = metadata.qualityLabel || track.qualityLabel;
    } catch {
      // ignore metadata enrichment failure for webdav
    } finally {
      setMetadataLoading(false);
    }
    track._metadataLoaded = true;
  } else {
    setMetadataLoading(false);
  }
  audio.src = track.fileUrl;
  trackTitle.textContent = track.title;
  trackArtist.textContent = track.artist;
  trackQuality.textContent = track.qualityLabel || "未知音质";
  setCover(track);
  progress.value = "0";
  progress.style.setProperty("--progress", "0%");
  currentTimeText.textContent = "00:00";
  totalTimeText.textContent = "00:00";
  playSwitchAnimation();
  await loadLyrics(track);
  renderTrackList();
  ensureActiveTrackVisibleInstant();
  ensureActiveTrackVisibleDeferred();
  if (autoPlay) await audio.play();
}

async function loadAllLibraries() {
  const songs = await window.musicApi.scanAllLibraries();
  playlist = songs;
  shuffleRoundPlayed = new Set();
  shuffleRoundQueue = [];
  filteredPlaylist = [...playlist];
  renderTrackList();
  if (playlist.length > 0) await loadTrack(0, false);
  else {
    trackTitle.textContent = "请在设置中添加曲库";
    trackArtist.textContent = "本地目录 + WebDAV 目录";
    trackQuality.textContent = "--";
    lyrics = [];
    renderLyrics();
  }
}

function renderLibraryPathList() {
  libraryPathList.innerHTML = "";
  if (libraryFolders.length === 0) {
    const empty = document.createElement("p");
    empty.className = "empty-lyrics";
    empty.textContent = "还没有添加本地目录";
    libraryPathList.appendChild(empty);
    return;
  }
  for (const folder of libraryFolders) {
    const item = document.createElement("div");
    item.className = "library-path-item";
    item.innerHTML = `<div class="library-path-text">${folder}</div>`;
    const removeButton = document.createElement("button");
    removeButton.className = "library-path-remove";
    removeButton.textContent = "删除";
    removeButton.addEventListener("click", async () => {
      libraryFolders = await window.musicApi.removeLibraryFolder(folder);
      renderLibraryPathList();
      await loadAllLibraries();
    });
    item.appendChild(removeButton);
    libraryPathList.appendChild(item);
  }
}

function renderWebdavLibraryList() {
  webdavLibraryList.innerHTML = "";
  if (webdavLibraries.length === 0) {
    const empty = document.createElement("p");
    empty.className = "empty-lyrics";
    empty.textContent = "还没有添加 WebDAV 曲库";
    webdavLibraryList.appendChild(empty);
    return;
  }
  for (const lib of webdavLibraries) {
    const item = document.createElement("div");
    item.className = "library-path-item";
    item.innerHTML = `<div class="library-path-text">${lib.protocol}://${lib.host}:${lib.port}${lib.folderPath}</div>`;
    const removeButton = document.createElement("button");
    removeButton.className = "library-path-remove";
    removeButton.textContent = "删除";
    removeButton.addEventListener("click", async () => {
      webdavLibraries = await window.musicApi.removeWebdavLibrary(lib.id);
      renderWebdavLibraryList();
      await loadAllLibraries();
    });
    item.appendChild(removeButton);
    webdavLibraryList.appendChild(item);
  }
}

function getWebdavFormConfig() {
  return {
    protocol: webdavProtocolInput.value,
    host: webdavHostInput.value.trim(),
    port: Number(webdavPortInput.value || 0),
    username: webdavUsernameInput.value.trim(),
    password: webdavPasswordInput.value,
    folderPath: webdavCurrentPath || "/",
  };
}

async function browseWebdavDirectory() {
  webdavBrowsingPath = webdavCurrentPath || "/";
  webdavBrowserModal.classList.remove("hidden");
  webdavBrowserModal.setAttribute("aria-hidden", "false");
  await refreshWebdavBrowserList();
}

function closeWebdavBrowser() {
  webdavBrowserModal.classList.add("hidden");
  webdavBrowserModal.setAttribute("aria-hidden", "true");
}

async function refreshWebdavBrowserList() {
  const baseConfig = getWebdavFormConfig();
  webdavBrowserPath.textContent = webdavBrowsingPath;
  webdavBrowserList.innerHTML = "";
  const dirs = await window.musicApi.listWebdavDirectory({
    ...baseConfig,
    path: webdavBrowsingPath,
  });
  if (!Array.isArray(dirs) || dirs.length === 0) {
    const empty = document.createElement("p");
    empty.className = "empty-lyrics";
    empty.textContent = "当前目录没有子文件夹";
    webdavBrowserList.appendChild(empty);
    return;
  }
  for (const dir of dirs) {
    const button = document.createElement("button");
    button.className = "webdav-browser-item";
    button.textContent = dir.name || dir.path;
    button.addEventListener("click", async () => {
      webdavBrowsingPath = dir.path;
      await refreshWebdavBrowserList();
    });
    webdavBrowserList.appendChild(button);
  }
}

function nextTrack(autoPlay) {
  if (playlist.length === 0) return;
  let nextIndex = currentIndex + 1;
  if (playMode === "shuffle") nextIndex = takeNextShuffleIndex();
  else if (nextIndex >= playlist.length) nextIndex = 0;
  loadTrack(nextIndex, autoPlay);
}

function prevTrack(autoPlay) {
  if (playlist.length === 0) return;
  let prevIndex = currentIndex - 1;
  if (playMode === "shuffle") prevIndex = randomIndexExceptCurrent();
  else if (prevIndex < 0) prevIndex = playlist.length - 1;
  loadTrack(prevIndex, autoPlay);
}

function updateMaxButtonState(isMaximized) {
  maxBtn.textContent = isMaximized ? "❐" : "□";
  maxBtn.title = isMaximized ? "还原" : "最大化";
  maxBtn.setAttribute("aria-label", isMaximized ? "还原" : "最大化");
}

async function initWindowControls() {
  minBtn.addEventListener("click", () => window.musicApi.window.minimize());
  maxBtn.addEventListener("click", () => window.musicApi.window.toggleMaximize());
  closeBtn.addEventListener("click", () => window.musicApi.window.close());
  topbar.addEventListener("dblclick", () => window.musicApi.window.toggleMaximize());
  const isMaximized = await window.musicApi.window.isMaximized();
  updateMaxButtonState(isMaximized);
  window.musicApi.window.onMaximizeChanged(updateMaxButtonState);
}

function openSettings() {
  settingsPanel.classList.remove("hidden");
  settingsPanel.setAttribute("aria-hidden", "false");
}

function closeSettings() {
  settingsPanel.classList.add("hidden");
  settingsPanel.setAttribute("aria-hidden", "true");
}

openSettingsButton.addEventListener("click", openSettings);
closeSettingsButton.addEventListener("click", closeSettings);
settingsPanel.addEventListener("click", (event) => {
  if (event.target === settingsPanel) closeSettings();
});

addLibraryPathButton.addEventListener("click", async () => {
  libraryFolders = await window.musicApi.addLibraryFolder();
  renderLibraryPathList();
  await loadAllLibraries();
});

webdavTestButton.addEventListener("click", async () => {
  const result = await window.musicApi.testWebdavConnection(getWebdavFormConfig());
  webdavStatus.textContent = result.message || "";
});

webdavBrowseButton.addEventListener("click", async () => {
  try {
    await browseWebdavDirectory();
  } catch (error) {
    webdavStatus.textContent = `浏览失败: ${error.message}`;
  }
});

webdavBrowserClose.addEventListener("click", closeWebdavBrowser);
webdavBrowserModal.addEventListener("click", (event) => {
  if (event.target === webdavBrowserModal) {
    closeWebdavBrowser();
  }
});
webdavBrowserUp.addEventListener("click", async () => {
  const parent = webdavBrowsingPath.split("/").filter(Boolean);
  parent.pop();
  webdavBrowsingPath = `/${parent.join("/")}`;
  if (!webdavBrowsingPath || webdavBrowsingPath === "//") {
    webdavBrowsingPath = "/";
  }
  await refreshWebdavBrowserList();
});
webdavBrowserSelect.addEventListener("click", () => {
  webdavCurrentPath = webdavBrowsingPath || "/";
  webdavSelectedPath.textContent = `已选择: ${webdavCurrentPath}`;
  closeWebdavBrowser();
});

webdavAddButton.addEventListener("click", async () => {
  try {
    webdavLibraries = await window.musicApi.addWebdavLibrary(getWebdavFormConfig());
    renderWebdavLibraryList();
    await loadAllLibraries();
    webdavStatus.textContent = "WebDAV 曲库已添加";
  } catch (error) {
    webdavStatus.textContent = `添加失败: ${error.message}`;
  }
});

searchInput.addEventListener("input", filterTracks);

playButton.addEventListener("click", async () => {
  if (playlist.length === 0) return;
  if (audio.paused) {
    if (currentIndex === -1) await loadTrack(0, true);
    else await audio.play();
  } else audio.pause();
});

nextButton.addEventListener("click", () => nextTrack(true));
prevButton.addEventListener("click", () => prevTrack(true));
modeSequenceButton.addEventListener("click", () => setPlayMode("sequence"));
modeLoopButton.addEventListener("click", () => setPlayMode("loop"));
modeShuffleButton.addEventListener("click", () => setPlayMode("shuffle"));

volume.addEventListener("input", () => {
  const value = Number(volume.value);
  if (value > 0) lastNonZeroVolume = value;
  if (isMuted && value > 0) isMuted = false;
  applyVolumeState();
});

volumeToggleButton.addEventListener("click", () => {
  if (isMuted || Number(volume.value) === 0) {
    if (Number(volume.value) === 0) volume.value = String(lastNonZeroVolume || 80);
    isMuted = false;
  } else isMuted = true;
  applyVolumeState();
});

progress.addEventListener("pointerdown", () => {
  isSeeking = true;
});

progress.addEventListener("pointerup", () => {
  if (!Number.isFinite(audio.duration) || audio.duration <= 0) {
    isSeeking = false;
    return;
  }
  const ratio = Number(progress.value) / 1000;
  audio.currentTime = ratio * audio.duration;
  isSeeking = false;
});

audio.addEventListener("play", () => {
  updatePlayButton(true);
  setVinylPlaying(true);
  startLyricSync();
});

audio.addEventListener("pause", () => {
  updatePlayButton(false);
  setVinylPlaying(false);
  stopLyricSync();
});

audio.addEventListener("loadedmetadata", () => {
  totalTimeText.textContent = formatTime(audio.duration);
});

audio.addEventListener("timeupdate", () => {
  if (!isSeeking && Number.isFinite(audio.duration) && audio.duration > 0) {
    const ratio = audio.currentTime / audio.duration;
    progress.value = String(Math.round(ratio * 1000));
    progress.style.setProperty("--progress", `${ratio * 100}%`);
  }
  currentTimeText.textContent = formatTime(audio.currentTime);
  updateLyricsByTime(audio.currentTime);
});

audio.addEventListener("ended", () => {
  stopLyricSync();
  if (playMode === "loop") {
    audio.currentTime = 0;
    audio.play();
    return;
  }
  if (playMode === "shuffle") {
    nextTrack(true);
    return;
  }
  if (currentIndex < playlist.length - 1) loadTrack(currentIndex + 1, true);
  else {
    updatePlayButton(false);
    setVinylPlaying(false);
  }
});

window.addEventListener("resize", () => {
  syncLyricSpacers();
  if (activeLyricIndex >= 0) {
    setActiveLyric(activeLyricIndex);
    smoothLyricsScroll(true);
  }
});

lyricsContainer.addEventListener("wheel", (event) => event.preventDefault(), { passive: false });

vinyl.addEventListener("animationend", (event) => {
  if (event.animationName === "trackSwitch") {
    vinyl.classList.remove("switching");
    vinyl.style.animationPlayState = audio.paused ? "paused" : "running";
  }
});

document.addEventListener("dragstart", (event) => event.preventDefault());
document.addEventListener("selectstart", (event) => {
  const target = event.target;
  if (!(target instanceof HTMLElement)) return;
  if (
    target.closest(".window-actions") ||
    target.closest(".settings-panel") ||
    target.closest(".search-input")
  ) {
    event.preventDefault();
  }
});

async function initApp() {
  setPlayMode("sequence");
  updatePlayButton(false);
  applyVolumeState();
  await initWindowControls();

  libraryFolders = await window.musicApi.getLibraryFolders();
  webdavLibraries = await window.musicApi.getWebdavLibraries();
  renderLibraryPathList();
  renderWebdavLibraryList();
  trackTitle.textContent = "正在扫描曲库...";
  trackArtist.textContent = "请稍候";
  trackQuality.textContent = "--";
  loadAllLibraries().catch((error) => {
    trackTitle.textContent = "曲库加载失败";
    trackArtist.textContent = error?.message || "请检查目录或 WebDAV 配置";
    trackQuality.textContent = "--";
  });
  setMetadataLoading(false);

  // Prefill with user-provided WebDAV sample (editable)
  if (!webdavHostInput.value) {
    webdavProtocolInput.value = "https";
    webdavHostInput.value = "127.0.0.1";
    webdavPortInput.value = "5006";
    webdavUsernameInput.value = "";
  }
}

initApp();
