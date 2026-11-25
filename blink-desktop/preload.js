const { contextBridge, ipcRenderer } = require('electron');

const CHANNELS = ['cursor', 'sense', 'palette', 'command', 'wear', 'settings', 'snooze', 'desk-click', 'size', 'shortcuts'];

contextBridge.exposeInMainWorld('blink', {
  getSettings: () => ipcRenderer.invoke('get-settings'),
  icons: () => ipcRenderer.invoke('icons'),
  probe: points => ipcRenderer.invoke('probe', points),
  shortcuts: () => ipcRenderer.invoke('shortcuts'),
  saveSettings: patch => ipcRenderer.send('save-settings', patch),
  setInteractive: on => ipcRenderer.send('interactive', on),
  paletteClosed: () => ipcRenderer.send('palette-closed'),
  trayIcon: dataUrl => ipcRenderer.send('tray-icon', dataUrl),
  showMenu: () => ipcRenderer.send('show-menu'),
  snooze: ms => ipcRenderer.send('snooze', ms),
  quit: () => ipcRenderer.send('quit'),
  power: on => ipcRenderer.send('power', on),
  on: (channel, fn) => { if (CHANNELS.includes(channel)) ipcRenderer.on(channel, (e, data) => fn(data)); }
});
