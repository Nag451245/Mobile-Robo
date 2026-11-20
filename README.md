# Sweety

A cute 3D robot helper with big sparkly eyes. On your phone she lives in a nature scene that matches her outfit, floats on top of your apps, listens when you talk, answers out loud, and gets things done: alarms, reminders, calls, messages, the torch, music and more. Everything runs offline on the phone: [Needle 3](https://huggingface.co/Cactus-Compute/needle3) understands what you ask, [Whistle](https://huggingface.co/Cactus-Compute/whistle) hears you, and the phone speaks her replies. No cloud and no API key.

| Where | Folder | What it is | Get it |
|---|---|---|---|
| Android phone | [`blink-android`](blink-android) | **Sweety**: voice assistant + floating 3D robot over your apps + live wallpaper with animated nature scenes | [Latest build](../../releases/tag/latest): `Sweety.apk` |
| Windows PC | [`blink-desktop`](blink-desktop) | Transparent desktop pet: walks the taskbar, flies around the desktop, hides in files, keyboard shortcuts | [Latest build](../../releases/tag/latest): `Blink-portable-win64.zip` |
| Any phone browser | [`blink-mobile`](blink-mobile) | Web version with voice commands; nothing to install | GitHub Pages (see below) |
| Design | [`blink-prd.html`](blink-prd.html), [`blink-moments`](blink-moments) | Product requirements and the menu of phone moments | Open in a browser |

Every push to `main` rebuilds the APK and the Windows zip with GitHub Actions and attaches them to the **Latest build** release.

## Android: install on your phone

1. On the phone, open this repo's **Releases → Latest build** in Chrome and tap `Sweety.apk`.
2. When Android asks, allow Chrome to **install unknown apps**, then tap **Install**. If Play Protect shows "Unsafe app blocked", tap **More details → Install anyway**.
3. Open **Sweety**. Tap **Talk** and allow the microphone, then say things like:
   - "set an alarm for 7 am", "remind me in 10 minutes to drink water", "timer for 5 minutes"
   - "call mom", "text dad saying I'll be late", "send a WhatsApp to Ravi saying good morning"
   - "turn on the torch", "next song", "volume up", "what's my battery", "what time is it"
   - "take a note buy milk", "read my notes", "dance", "tell me a joke", "wear the bunny outfit"
4. Tap **Start floating Sweety** and allow **Display over other apps**. On the home screen: drag her anywhere, tap to poke, double-tap for a new outfit, **long-press to talk**.
5. Optional: **Set Sweety as my wallpaper** (choose Home and lock screens) to see her nature scenes.

Calls and messages always open your dialer or messaging app with everything filled in; you press call or send. The first time you say a name, Sweety shows the contact picker once and remembers your choice, so she never needs access to all your contacts.

Permissions: microphone (only while the talk window is open), display over other apps, notifications for the floating service, and setting alarms. No SMS, notification, contacts or accessibility access.

Needs Android 8 or later on a 64-bit phone. Hearing (Whistle) supports English, German, French, Spanish, Italian, Dutch and Polish; if it can't load, Sweety falls back to the phone's speech service.

## Windows: run without installing

Download `Blink-portable-win64.zip` from the Latest build, unzip anywhere and run `Blink.exe`. `Ctrl+Alt+/` lists all keyboard shortcuts. See [`blink-desktop/README.txt`](blink-desktop/README.txt).

## Mobile web

One-time setup: **Settings → Pages → Build and deployment → Source: GitHub Actions**. After the next push, the app is at `https://<your-user>.github.io/<this-repo>/`.

## Credits

Sweety's offline brain: [Cactus runtime](https://huggingface.co/Cactus-Compute/cactus), [Needle 3](https://huggingface.co/Cactus-Compute/needle3) and [Whistle](https://huggingface.co/Cactus-Compute/whistle) by Cactus Compute, Apache-2.0. The build downloads them automatically; they are not stored in this repo.
