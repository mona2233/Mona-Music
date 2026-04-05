const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("musicApi", {
  getLibraryFolders: () => ipcRenderer.invoke("music:get-library-folders"),
  addLibraryFolder: () => ipcRenderer.invoke("music:add-library-folder"),
  removeLibraryFolder: (folder) => ipcRenderer.invoke("music:remove-library-folder", folder),
  scanAllLibraries: () => ipcRenderer.invoke("music:scan-all-libraries"),
  getWebdavLibraries: () => ipcRenderer.invoke("webdav:get-libraries"),
  getWebdavTrackMetadata: (track) => ipcRenderer.invoke("webdav:get-track-metadata", track),
  testWebdavConnection: (config) => ipcRenderer.invoke("webdav:test-connection", config),
  listWebdavDirectory: (config) => ipcRenderer.invoke("webdav:list-directory", config),
  addWebdavLibrary: (config) => ipcRenderer.invoke("webdav:add-library", config),
  removeWebdavLibrary: (id) => ipcRenderer.invoke("webdav:remove-library", id),
  loadLyrics: (trackPath) => ipcRenderer.invoke("music:load-lyrics", trackPath),
  window: {
    minimize: () => ipcRenderer.send("window:minimize"),
    toggleMaximize: () => ipcRenderer.send("window:toggle-maximize"),
    close: () => ipcRenderer.send("window:close"),
    isMaximized: () => ipcRenderer.invoke("window:is-maximized"),
    onMaximizeChanged: (callback) => {
      const handler = (_event, value) => callback(value);
      ipcRenderer.on("window:maximized-changed", handler);
      return () => ipcRenderer.removeListener("window:maximized-changed", handler);
    },
  },
});
