# Blink Desk Buddy

A cute 3D robot with big sparkly eyes that lives on your screens, does funny things, changes outfits and listens to commands, without getting in the way of your work.

| Where | Folder | What it is | Get it |
|---|---|---|---|
| Android phone | [`blink-android`](blink-android) | **Floating Blink**: the 3D desktop-style Blink on top of your home screen and apps (drag, throw, poke, outfits). Plus an optional live wallpaper for the lock screen | [Latest build](../../releases/tag/latest): `Blink-wallpaper.apk` |
| Windows PC | [`blink-desktop`](blink-desktop) | Transparent desktop pet: walks the taskbar, flies around the desktop, hides in files, global keyboard shortcuts | [Latest build](../../releases/tag/latest): `Blink-portable-win64.zip` |
| Any phone browser | [`blink-mobile`](blink-mobile) | Blink Pocket web app with voice commands; nothing to install | GitHub Pages (see below) |
| Design | [`blink-prd.html`](blink-prd.html), [`blink-moments`](blink-moments) | Product requirements and the menu of phone moments | Open in a browser |

Every push to `main` rebuilds the APK and the Windows zip with GitHub Actions and attaches them to the **Latest build** release.

## Android: install on your phone

1. On the phone, open this repo's **Releases → Latest build** and tap `Blink-wallpaper.apk`.
2. When Android asks, allow your browser to **install unknown apps**, then tap **Install**.
3. Open **Blink** and tap **Start floating Blink**. Android opens a settings page: turn on **Allow display over other apps** for Blink, go back, and tap **Start floating Blink** again. Allow notifications if asked (the notification has Hide and Stop buttons).
4. Go to your home screen. Drag Blink anywhere, flick to throw, tap to poke, double-tap for a new outfit, long-press to open settings. Blink hides itself in landscape (videos and games).
5. Optional: tap **Set Blink as my wallpaper** and pick **Home and lock screens** to also have Blink on the lock screen.

If Google Play Protect shows **"Unsafe app blocked"** or **"Unknown app"**, tap **More details → Install anyway**. Blink asks for no sensitive permissions (no SMS, notifications, accessibility or contacts), so it isn't caught by Play Protect's fraud block.

Moments included (starter pack): sleepy wake-up, unlock hello, missed you, unlock counter, page-swipe walk, tap and hop, see you soon, good morning, bedtime nightcap, charging snack, low battery yawn, headphone groove, shake it, pet and purr, outfit of the day, screen-time nudge. Each can be switched off in the app.

Notes: live wallpapers run on Android 8 and later. Some phones (many Samsung models) only allow live wallpapers on the home screen. Page-swipe walking needs a launcher that reports page scrolling.

## Windows: run without installing

Download `Blink-portable-win64.zip` from the Latest build, unzip anywhere and run `Blink.exe`. `Ctrl+Alt+/` lists all keyboard shortcuts. See [`blink-desktop/README.txt`](blink-desktop/README.txt).

Build from source: `cd blink-desktop && npm install && npm start`.

## Mobile web (Blink Pocket)

One-time setup: **Settings → Pages → Build and deployment → Source: GitHub Actions**. After the next push, the app is at `https://<your-user>.github.io/<this-repo>/`. HTTPS lets the phone's microphone work for voice commands.

Build locally: `cd blink-mobile && node build.js` writes `dist/blink-mobile.html`.
