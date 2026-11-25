// Blink Desk Buddy - main process.
// One transparent, always-on-top, non-activating overlay over the primary display's work area.
// Clicks pass through everywhere except Blink's own pixels. No admin rights, no hooks, no services.
const { app, BrowserWindow, screen, ipcMain, Tray, Menu, nativeImage, globalShortcut, powerMonitor } = require('electron');
const path = require('path');
const fs = require('fs');
const { execFile } = require('child_process');

// Portable mode: an empty file named "portable" next to Blink.exe keeps settings in .\data
const exeDir = path.dirname(process.execPath);
if (app.isPackaged && fs.existsSync(path.join(exeDir, 'portable'))) app.setPath('userData', path.join(exeDir, 'data'));
else app.setPath('userData', path.join(app.getPath('appData'), 'Blink'));

// Lets WebGL fall back to software rendering on machines or VMs without a usable GPU.
app.commandLine.appendSwitch('enable-unsafe-swiftshader');

const DEFAULTS = { on: true, skin: 'bubblegum', size: 'M', mode: 'lively', autoOutfit: 'sometimes', nightCap: true, roam: true, followClicks: true, lastDay: '' };
const SKINS = [['bubblegum', 'Bubblegum'], ['sunny', 'Sunny'], ['kitty', 'Mint Kitty'], ['bunny', 'Lavender Bunny'], ['panda', 'Panda'], ['sprout', 'Sprout'], ['classic', 'Classic'], ['snow', 'Snow Buddy'], ['nightcap', 'Sleepy Cap']];

let win = null, tray = null, menu = null, trayImg = null, powerTimer = null;
let snoozed = false, snoozeTimer = null, paletteOpen = false, interactive = false;
const settingsFile = () => path.join(app.getPath('userData'), 'settings.json');
let settings = { ...DEFAULTS };

function loadSettings() {
  try { settings = { ...DEFAULTS, ...JSON.parse(fs.readFileSync(settingsFile(), 'utf8')) }; } catch { settings = { ...DEFAULTS }; }
}
function saveSettings() {
  try { fs.mkdirSync(app.getPath('userData'), { recursive: true }); fs.writeFileSync(settingsFile(), JSON.stringify(settings, null, 2)); } catch (e) { console.error(e); }
}
const send = (ch, data) => { if (win && !win.isDestroyed()) win.webContents.send(ch, data); };

/* ---------- Tiny read-only Win32 probe: is the left button down, which top-level window is under a point ---------- */
let native = null;
try {
  const koffi = require('koffi');
  const user32 = koffi.load('user32.dll');
  const POINT = koffi.struct('POINT', { x: 'long', y: 'long' });
  native = {
    key: user32.func('short __stdcall GetAsyncKeyState(int vKey)'),
    fromPoint: user32.func('void * __stdcall WindowFromPoint(POINT pt)'),
    ancestor: user32.func('void * __stdcall GetAncestor(void *hwnd, uint32_t flags)'),
    className: user32.func('int __stdcall GetClassNameW(void *hwnd, void *buf, int max)')
  };
} catch (e) { console.log('[native probe disabled]', e.message); }
const classBuf = Buffer.alloc(512);
function rootClassAt(dip) {
  if (!native) return null;
  try {
    const p = screen.dipToScreenPoint(dip);
    const h = native.fromPoint({ x: Math.round(p.x), y: Math.round(p.y) });
    if (!h) return null;
    const root = native.ancestor(h, 2) || h;
    const n = native.className(root, classBuf, 256);
    return classBuf.toString('utf16le', 0, n * 2);
  } catch { return null; }
}
const isDesk = c => c === 'Progman' || c === 'WorkerW';

/* ---------- Desktop icon names and positions via the accessibility API (read-only, on demand, cached) ---------- */
let ICON_PS = '';
try { ICON_PS = fs.readFileSync(path.join(__dirname, 'desktop-icons.ps1'), 'utf8'); } catch { }
let iconCache = { t: 0, list: [] }, iconJob = null;
function fetchIcons() {
  if (Date.now() - iconCache.t < 20000) return Promise.resolve(iconCache.list);
  if (iconJob) return iconJob;
  if (!ICON_PS) return Promise.resolve([]);
  iconJob = new Promise(resolve => {
    const enc = Buffer.from(ICON_PS, 'utf16le').toString('base64');
    execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-EncodedCommand', enc], { windowsHide: true, timeout: 15000 }, (err, out) => {
      const list = String(out || '').split('\n').map(l => l.split('\t')).filter(p => p.length === 5)
        .map(([n, x, y, w, h]) => ({ n, x: +x, y: +y, w: +w, h: +h })).filter(i => i.w > 0 && i.w < 400);
      iconCache = { t: Date.now(), list }; iconJob = null; resolve(list);
    });
  });
  return iconJob;
}
async function visibleIcons() {
  const list = await fetchIcons();
  if (!win) return [];
  const b = win.getBounds();
  return list.filter(i => { const c = rootClassAt({ x: i.x + i.w / 2, y: i.y + i.h / 2 }); return c === null || isDesk(c); })
    .map(i => ({ n: i.n, x: i.x - b.x, y: i.y - b.y, w: i.w, h: i.h }));
}

function createWindow() {
  const wa = screen.getPrimaryDisplay().workArea;
  win = new BrowserWindow({
    x: wa.x, y: wa.y, width: wa.width, height: wa.height,
    transparent: true, frame: false, resizable: false, movable: false, minimizable: false, maximizable: false,
    fullscreenable: false, skipTaskbar: true, alwaysOnTop: true, focusable: false, hasShadow: false, show: false,
    backgroundColor: '#00000000', title: 'Blink',
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, backgroundThrottling: false, spellcheck: false }
  });
  win.setIgnoreMouseEvents(true, { forward: true });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.once('ready-to-show', () => { if (settings.on) win.showInactive(); else send('snooze', true); });
  win.on('closed', () => { win = null; });
  win.webContents.on('console-message', (e, level, message, line, source) => {
    if (level < 2 || /GPU stall/.test(message)) return;
    const msg = `[renderer] ${message} (${path.basename(source || '')}:${line})`;
    console.log(msg);
    // Keep a small error log next to the settings so problems can be diagnosed later.
    if (level >= 3) try { const f = path.join(app.getPath('userData'), 'blink.log'); if (!fs.existsSync(f) || fs.statSync(f).size < 200000) fs.appendFileSync(f, `${new Date().toISOString()} ${msg}\n`); } catch { }
  });
  win.webContents.on('render-process-gone', (e, d) => console.log('[renderer gone]', d.reason));
  // Debug only: BLINK_SNAPSHOT=<file.png> saves a capture of Blink's own transparent window (not the rest of the screen).
  if (process.env.BLINK_SNAPSHOT) for (let i = 0; i < Number(process.env.BLINK_SNAPSHOT_COUNT || 1); i++) setTimeout(async () => {
    try { const img = await win.webContents.capturePage(); fs.writeFileSync(process.env.BLINK_SNAPSHOT.replace('.png', `-${i}.png`), img.toPNG()); console.log('[snapshot saved]', i); } catch (err) { console.log('[snapshot failed]', err.message); }
  }, Number(process.env.BLINK_SNAPSHOT_DELAY || 7000) + i * 1500);
}

function fitToScreen() { if (win) win.setBounds(screen.getPrimaryDisplay().workArea); }

function openPalette() {
  if (!win || !settings.on) return;
  if (snoozed) setSnooze(false);
  paletteOpen = true;
  win.setFocusable(true);
  win.setIgnoreMouseEvents(false);
  win.focus();
  win.webContents.focus();
  send('palette', true);
}
function closePalette() {
  if (!win) return;
  paletteOpen = false;
  win.blur();
  win.setFocusable(false);
  win.setIgnoreMouseEvents(!interactive, { forward: true });
}

function setSnooze(on, ms) {
  if (!settings.on && !on) return; // turned off: only the on/off switch brings her back
  snoozed = on;
  clearTimeout(snoozeTimer);
  if (on && ms) snoozeTimer = setTimeout(() => setSnooze(false), ms);
  send('snooze', on);
  buildMenu();
}
/* ---------- On/off: off means Blink is gone (window hidden, nothing running) until turned on again ---------- */
function setPower(on) {
  if (!win) return;
  clearTimeout(powerTimer);
  settings.on = on; saveSettings();
  if (paletteOpen) { send('palette', false); closePalette(); }
  if (on) {
    win.showInactive();
    setSnooze(false);              // she drops back onto the taskbar
  } else {
    snoozed = true; clearTimeout(snoozeTimer);
    send('snooze', true);          // a little wave goodbye, then gone
    powerTimer = setTimeout(() => { if (!settings.on && win) win.hide(); }, 900);
  }
  updateTray();
  buildMenu();
}
const togglePower = () => setPower(!settings.on);
function updateTray() {
  if (!tray) return;
  tray.setToolTip(settings.on ? 'Blink is on · click to turn off · Ctrl+Alt+O' : 'Blink is off · click to turn on · Ctrl+Alt+O');
  if (trayImg) tray.setImage(settings.on ? trayImg : dimmed(trayImg));
}
// The tray icon at 35% opacity while Blink is off
function dimmed(img) {
  const size = img.getSize(), bmp = Buffer.from(img.toBitmap());
  for (let i = 3; i < bmp.length; i += 4) bmp[i] = Math.round(bmp[i] * 0.35);
  return nativeImage.createFromBitmap(bmp, { width: size.width, height: size.height });
}

function setSetting(patch) { Object.assign(settings, patch); saveSettings(); send('settings', settings); buildMenu(); }

const loginOpts = () => (app.isPackaged ? {} : { path: process.execPath, args: [path.resolve(app.getAppPath())] });
function getLogin() { try { return app.getLoginItemSettings(loginOpts()).openAtLogin; } catch { return false; } }
function setLogin(on) { app.setLoginItemSettings({ openAtLogin: on, ...loginOpts() }); buildMenu(); }

/* ---------- Keyboard shortcuts (global hotkeys; work from any app) ---------- */
const cmd = id => () => { if (!settings.on) return; if (snoozed) setSnooze(false); send('command', id); };
const SHORTCUTS = [
  ['Control+Alt+O', 'Turn Blink on / off', togglePower],
  ['Control+Alt+B', 'Talk to Blink (command box)', openPalette],
  ['Control+Alt+Shift+B', 'Hide for 1 hour / show again', () => { if (settings.on) setSnooze(!snoozed, snoozed ? 0 : 3600e3); }],
  ['Control+Alt+D', 'Dance', cmd('dance')],
  ['Control+Alt+W', 'Wave', cmd('wave')],
  ['Control+Alt+J', 'Jump', cmd('jump')],
  ['Control+Alt+S', 'Spin', cmd('spin')],
  ['Control+Alt+K', 'Tell a joke', cmd('joke')],
  ['Control+Alt+R', 'Stretch break', cmd('stretch')],
  ['Control+Alt+N', 'Nap / wake up', cmd('napToggle')],
  ['Control+Alt+G', 'Come to my mouse pointer', cmd('come')],
  ['Control+Alt+F', 'Hide in a desktop file', cmd('dive')],
  ['Control+Alt+0', 'Random outfit', cmd('outfit')],
  ...SKINS.map(([id, name], i) => [`Control+Alt+${i + 1}`, `Wear ${name}`, () => { if (!settings.on) return; if (snoozed) setSnooze(false); send('wear', id); }]),
  ['Control+Alt+=', 'Bigger', () => send('size', 1)],
  ['Control+Alt+-', 'Smaller', () => send('size', -1)],
  ['Control+Alt+/', 'Show this list', () => send('shortcuts', shortcutList())]
];
const failedKeys = new Set();
const pretty = acc => acc.replace('Control', 'Ctrl');
const shortcutList = () => SHORTCUTS.map(([acc, label]) => ({ key: pretty(acc), label, ok: !failedKeys.has(acc) }));
const accFor = label => { const s = SHORTCUTS.find(x => x[1] === label); return s ? { accelerator: s[0], registerAccelerator: false } : {}; };
function registerShortcuts() {
  for (const [acc, , fn] of SHORTCUTS) {
    let ok = false; try { ok = globalShortcut.register(acc, fn); } catch { ok = false; }
    if (!ok) { failedKeys.add(acc); console.log(`[shortcut taken] ${pretty(acc)}`); }
  }
}

function buildMenu() {
  const radio = (list, key) => list.map(([v, l]) => ({ label: l, type: 'radio', checked: settings[key] === v, click: () => setSetting({ [key]: v }) }));
  const act = (id, label, sc) => ({ label, click: cmd(id), ...accFor(sc || label) });
  menu = Menu.buildFromTemplate([
    { label: 'Blink Desk Buddy', enabled: false },
    { label: settings.on ? 'Blink is on' : 'Blink is off', type: 'checkbox', checked: !!settings.on, click: togglePower, ...accFor('Turn Blink on / off') },
    { type: 'separator' },
    { type: 'separator' },
    { label: 'Talk to Blink…', click: openPalette, ...accFor('Talk to Blink (command box)') },
    { label: 'Actions', submenu: [act('dance', 'Dance'), act('wave', 'Wave'), act('jump', 'Jump'), act('spin', 'Spin'), act('joke', 'Tell a joke'), act('stretch', 'Stretch break'), act('napToggle', 'Nap / wake up'), { type: 'separator' }, act('come', 'Come to my mouse pointer'), act('dive', 'Hide in a desktop file')] },
    { label: 'Outfits', submenu: [...SKINS.map(([id, l]) => ({ label: l, type: 'radio', checked: settings.skin === id, click: () => send('wear', id), ...accFor(`Wear ${l}`) })), { type: 'separator' }, act('outfit', 'Random outfit')] },
    { label: 'Auto outfit change', submenu: radio([['off', 'Off'], ['sometimes', 'Sometimes (every 10–20 min)'], ['often', 'Often (every 1–2 min)']], 'autoOutfit') },
    { label: 'Sleepy Cap at night (9 pm – 6 am)', type: 'checkbox', checked: settings.nightCap, click: m => setSetting({ nightCap: m.checked }) },
    { type: 'separator' },
    { label: 'Roam around the desktop', type: 'checkbox', checked: settings.roam, click: m => setSetting({ roam: m.checked }) },
    { label: 'Follow my clicks on the desktop', type: 'checkbox', checked: settings.followClicks, enabled: !!native, click: m => setSetting({ followClicks: m.checked }) },
    { label: 'Size', submenu: radio([['S', 'Small (64 px)'], ['M', 'Medium (96 px)'], ['L', 'Large (128 px)']], 'size') },
    { label: 'Activity', submenu: radio([['calm', 'Calm (antic every 6–12 min)'], ['normal', 'Normal (every 2–4 min)'], ['lively', 'Lively (every 20–40 s)']], 'mode') },
    { label: 'Keyboard shortcuts…', click: () => send('shortcuts', shortcutList()), ...accFor('Show this list') },
    { type: 'separator' },
    { label: snoozed ? 'Show Blink' : 'Hide for 1 hour', enabled: !!settings.on, click: () => setSnooze(!snoozed, snoozed ? 0 : 3600e3), ...accFor('Hide for 1 hour / show again') },
    { label: 'Start with Windows', type: 'checkbox', checked: getLogin(), click: m => setLogin(m.checked) },
    { type: 'separator' },
    { label: 'Quit Blink', click: () => app.quit() }
  ]);
  if (tray) tray.setContextMenu(menu);
}

/* ---------- Coarse sensors: cursor, seconds since last input, left-button clicks on the bare desktop ---------- */
let lastCur = { x: -1, y: -1 }, lastMove = 0, busyUntil = 0, lastBusy = null, lastAway = null, btnDown = null;
function startSensors() {
  setInterval(() => {
    if (!win || !settings.on) return; // off: nothing is watched
    const c = screen.getCursorScreenPoint(), now = Date.now();
    if (c.x !== lastCur.x || c.y !== lastCur.y) { lastMove = now; lastCur = c; }
    const b = win.getBounds();
    send('cursor', { x: c.x - b.x, y: c.y - b.y });
  }, 50);
  setInterval(() => {
    const idle = powerMonitor.getSystemIdleTime(), now = Date.now();
    // Input in the last second while the mouse stood still = typing, clicking or scrolling: the user is busy.
    if (!paletteOpen && !interactive && idle === 0 && now - lastMove > 1200) busyUntil = now + 3000;
    const busy = now < busyUntil, away = idle >= 600;
    if (busy !== lastBusy || away !== lastAway) { lastBusy = busy; lastAway = away; send('sense', { busy, away }); }
  }, 400);
  if (native) setInterval(() => {
    if (!win || snoozed || !settings.on) { btnDown = null; return; }
    let down = false; try { down = (native.key(0x01) & 0x8000) !== 0; } catch { return; }
    const c = screen.getCursorScreenPoint();
    if (down && !btnDown) btnDown = { x: c.x, y: c.y, t: Date.now(), desk: !interactive && !paletteOpen && isDesk(rootClassAt(c)) };
    else if (!down && btnDown) {
      const d = btnDown; btnDown = null;
      if (d.desk && settings.followClicks && Math.hypot(c.x - d.x, c.y - d.y) < 6 && Date.now() - d.t < 700) {
        const b = win.getBounds(); send('desk-click', { x: d.x - b.x, y: d.y - b.y });
      }
    }
  }, 30);
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => { if (!settings.on) setPower(true); else openPalette(); }); // starting Blink.exe again turns her on
  app.whenReady().then(() => {
    loadSettings();
    createWindow();
    registerShortcuts();
    buildMenu();
    startSensors();
    screen.on('display-metrics-changed', fitToScreen);
    screen.on('display-added', fitToScreen);
    screen.on('display-removed', fitToScreen);
    if (process.env.BLINK_TEST) setTimeout(async () => { const vi = await visibleIcons(); const msg = `native=${!!native} icons=${(await fetchIcons()).length} visible=${vi.length} probe=${rootClassAt({ x: 300, y: 300 })}`; console.log('[test]', msg); try { fs.writeFileSync(path.join(app.getPath('userData'), 'test.log'), msg); } catch { } console.log('[test probe]', rootClassAt({ x: 300, y: 300 })); }, 4000);
    if (process.env.BLINK_TEST === 'click') setTimeout(() => { const b = win.getBounds(); send('desk-click', { x: Math.round(b.width * .35), y: Math.round(b.height * .35) }); console.log('[test] simulated desktop click'); }, 8000);
    if (process.env.BLINK_TEST === 'dive') setTimeout(() => { send('command', 'dive'); console.log('[test] dive command'); }, 7000);
    if (process.env.BLINK_TEST === 'power') { // off, then on again: the window hides, sensors stop, settings remember it
      setTimeout(() => console.log(`[test] at start: on=${settings.on} visible=${win.isVisible()}`), 5000);
      setTimeout(() => { setPower(false); console.log('[test] turned off'); }, 6000);
      setTimeout(() => console.log(`[test] while off: visible=${win.isVisible()} saved on=${JSON.parse(fs.readFileSync(settingsFile(), 'utf8')).on} tooltip ok=${!!tray}`), 8000);
      setTimeout(() => { setPower(true); console.log('[test] turned on'); }, 9000);
      setTimeout(() => console.log(`[test] while on: visible=${win.isVisible()} saved on=${JSON.parse(fs.readFileSync(settingsFile(), 'utf8')).on}`), 10500);
      setTimeout(() => app.quit(), 13000);
    }
    if (/^test-/.test(process.env.BLINK_TEST || '')) setTimeout(() => send('command', process.env.BLINK_TEST), 7000);
    if (process.env.BLINK_TEST === 'divespam') setTimeout(() => {
      const iv = setInterval(() => send('command', 'dive'), 700);
      setTimeout(() => { clearInterval(iv); console.log('[test] spam over'); }, 15000);
    }, 6000);
  });

  ipcMain.handle('get-settings', () => settings);
  ipcMain.handle('icons', () => visibleIcons());
  ipcMain.handle('shortcuts', () => shortcutList());
  ipcMain.handle('probe', (e, pts) => {
    if (!win || !native) return pts.map(() => false);
    const b = win.getBounds();
    return pts.map(p => isDesk(rootClassAt({ x: p.x + b.x, y: p.y + b.y })));
  });
  ipcMain.on('save-settings', (e, patch) => { Object.assign(settings, patch); saveSettings(); buildMenu(); });
  ipcMain.on('interactive', (e, on) => { interactive = on; if (win && !paletteOpen) win.setIgnoreMouseEvents(!on, { forward: true }); });
  ipcMain.on('palette-closed', closePalette);
  ipcMain.on('show-menu', () => { if (menu && win) menu.popup({ window: win }); });
  ipcMain.on('snooze', (e, ms) => setSnooze(true, ms));
  ipcMain.on('quit', () => app.quit());
  ipcMain.on('power', (e, on) => setPower(!!on));
  ipcMain.on('tray-icon', (e, url) => {
    const img = nativeImage.createFromDataURL(url);
    trayImg = img;
    if (!tray) {
      tray = new Tray(img);
      tray.on('click', togglePower); // the tray icon is her on/off button; right-click for the menu
      buildMenu();
    }
    updateTray();
  });

  app.on('will-quit', () => globalShortcut.unregisterAll());
  app.on('window-all-closed', () => app.quit());
}
