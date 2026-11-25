# Sweety

A cute 3D robot helper with big sparkly eyes. On your phone she lives in a nature scene that matches her outfit, floats on top of your apps, listens when you talk, answers out loud, and gets things done: alarms, reminders, calls, messages, the torch, music and more. Everything runs offline on the phone: [Needle 3](https://huggingface.co/Cactus-Compute/needle3) understands what you ask, [Whistle](https://huggingface.co/Cactus-Compute/whistle) hears you, and the phone speaks her replies. No cloud and no API key.

| Where | Folder | What it is | Get it |
|---|---|---|---|
| Android phone | [`blink-android`](blink-android) | **Sweety**: voice assistant + floating 3D robot over your apps + live wallpaper with animated nature scenes | [Latest build](../../releases/tag/latest): `Sweety.apk` |
| Windows PC | [`blink-desktop`](blink-desktop) | Transparent desktop pet: walks the taskbar, flies around the desktop, hides in files, keyboard shortcuts | [Latest build](../../releases/tag/latest): `Blink-portable-win64.zip` |
| Any phone browser | [`blink-mobile`](blink-mobile) | Web version with voice commands; nothing to install | GitHub Pages (see below) |
| Design | [`docs/requirements.md`](docs/requirements.md), [`blink-prd.html`](blink-prd.html), [`blink-moments`](blink-moments) | Requirements (what she must do, and its status), the older product page and the menu of phone moments | Open in a browser |

Every push to `main` rebuilds the APK and the Windows zip with GitHub Actions and attaches them to the **Latest build** release.

## Android: install on your phone

1. On the phone, open this repo's **Releases → Latest build** in Chrome and tap `Sweety.apk`.
2. When Android asks, allow Chrome to **install unknown apps**, then tap **Install**. If Play Protect shows "Unsafe app blocked", tap **More details → Install anyway**.
3. Open **Sweety**. Tap **Talk** and allow the microphone, then say things like:
   - "set an alarm for 7 am", "remind me in 10 minutes to drink water", "timer for 5 minutes"
   - "call mom", "text dad saying I'll be late", "send a WhatsApp to Ravi saying good morning"
   - "turn on the torch", "next song", "volume up", "what's my battery", "what time is it"
   - "take a note buy milk", "read my notes", "tell me a joke", "wear the bunny outfit"
   - "open YouTube", "take me home", "restaurants near me", "search for cheap flights to Goa", "play Believer by Imagine Dragons", "play lofi on Spotify"
   - "add milk and eggs to my shopping list", "what's on my to-do list", "start the stopwatch", "turn on Wi-Fi" / "Bluetooth" (opens the switch), "turn on do not disturb", "find my phone", "take a selfie"
   - "dance", "do a backflip", "do a cartwheel", "giggle", "spin", "jump": she does the move with a little robot sound (or tap the **Tricks** chips). She waves back when you say hi.
   - Pick her voice under **Natural voice** (Bella, Sky, Nicole, Sarah or Emma) after the one-time download, or **Her voice** for the phone's own voice.
   - She knows the time of day: "good morning" gets a stretch (or a tease if it's 11 pm), "good night" makes her doze off, and she's dancy in the afternoon and yawny after midnight. Switch it off under **Time of day** with the other moments.
   - Sums like "what is 7 plus 5" are worked out exactly.
   - "Catch the butterfly" / "catch the bird": one flutters in, Sweety chases it, misses once, catches it with a giggle and lets it fly free.
   - "Hide" / "disappear" makes her vanish completely (card and all) for 5 minutes, then she pops back by herself; "come back" (or the notification's Come back button) brings her out sooner. The time is settable.
   - Hold the floating Sweety to pick her up, then flick to toss her around; she tumbles and lands.
4. **Hey Sweety or Hey Buddy (hands-free):** both wake words can be switched on or off; "Hey Buddy" works through Voice Match until its own trained model ships. Tick **Listen for "Hey Sweety"** and allow the microphone and "display over other apps". While the screen is on and unlocked, say "Hey Sweety" from any app: she wakes with a sound that fits the time of day (birds in the morning, sparrows at lunch, a songbird and crickets in the evening, crickets and an owl at night), her card pops up with her 3D self and a fresh one-liner, and you say what you need. Her greetings are funny, fit the time of day, and don't repeat until she has used every line. A small on-phone wake-word detector listens only for her name ("hey / hi / hello / okay Sweety"); the mic closes when the screen turns off or locks, and a notification shows while it's on. **Make Sweety my assistant** lets you call her by holding the power or home button, even on the lock screen, like Gemini.
5. **Smart brain (Gemini):** paste a free key from [Google AI Studio](https://aistudio.google.com/apikey) under **Smart brain (Gemini)**. Sweety then answers properly, follows the conversation, remembers what you tell her and does phone actions from natural requests. Clear commands still run on the phone, and without internet she uses her offline brain. The key and memories stay on the phone; your words go to Google only when Gemini answers.
6. **Hearing:** Sweety uses your phone's own speech recogniser (Google's on most phones), which copes with accents and noise; the offline Whistle model is only a backup.
7. **Her look:** the card shows her 3D self with lip-sync, listening and thinking states, moods and gestures. She can be the robot or a VRM character you design yourself: see [Designing Sweety as a VRM character](docs/design-sweety-vrm.md).
8. **Offline brain (optional):** for chatting without internet, download **Gemma 4 E2B** (Google, 2.6 GB, for phones with 6 GB+ memory; uses the GPU when it can) or the small **Qwen3-0.6B** (330 MB, any phone), both on [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM). Answers stream, so she starts speaking at the first sentence. The brain runs in its own process, so if it ever crashes on a phone, Sweety keeps working. **Remove** frees the space.
9. **Conversation:** once you've called her, she keeps listening after each answer, so you just keep talking. Say "stop", "that's all" or "bye" to end it (or tap End); she also stops after 20 seconds of quiet (settable, or only when you say stop).
10. Tap **Start floating Sweety** and allow **Display over other apps**. On the home screen: drag her anywhere, tap to poke, double-tap for a new outfit, **long-press to talk**.
11. Optional: **Set Sweety as my wallpaper** (choose Home and lock screens) to see her nature scenes.

Calls and messages always open your dialer or messaging app with everything filled in; you press call or send. The first time you say a name, Sweety shows the contact picker once and remembers your choice, so she never needs access to all your contacts.

Permissions: microphone (in the talk window, and for Hey Sweety only while the screen is on, if you turn it on), display over other apps, notifications for the floating service, setting alarms, and internet (to download the voice and offline brain, for Gemini answers if you add a key, and for the phone's speech recogniser unless it has an offline pack). No SMS, notification, contacts or accessibility access. The wake-word detector, the natural voice and your imported character stay on the phone; what you say after waking her goes to your phone's speech recogniser, and to Gemini only if you added a key.

Needs Android 8 or later on a 64-bit phone. Hearing (Whistle) supports English, German, French, Spanish, Italian, Dutch and Polish; if it can't load, Sweety falls back to the phone's speech service.

## Windows: run without installing

Download `Blink-portable-win64.zip` from the Latest build, unzip anywhere and run `Blink.exe`. Click its icon in the system tray to turn Blink off or on (or press `Ctrl+Alt+O`). `Ctrl+Alt+/` lists all keyboard shortcuts. See [`blink-desktop/README.txt`](blink-desktop/README.txt).

## Mobile web

One-time setup: **Settings → Pages → Build and deployment → Source: GitHub Actions**. After the next push, the app is at `https://<your-user>.github.io/<this-repo>/`.

## Credits

Sweety's offline brain: [Cactus runtime](https://huggingface.co/Cactus-Compute/cactus), [Needle 3](https://huggingface.co/Cactus-Compute/needle3) and [Whistle](https://huggingface.co/Cactus-Compute/whistle) by Cactus Compute, Apache-2.0. The build downloads them automatically; they are not stored in this repo. Wake word and natural voice: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0) with a [keyword-spotting model](https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models) and [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) (Apache-2.0). Avatar: [three.js](https://threejs.org) and [three-vrm](https://github.com/pixiv/three-vrm) (MIT). Smart brain: Google's Gemini API with your own key. Offline brains: [Gemma 4 E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) (Apache-2.0) and [Qwen3-0.6B](https://huggingface.co/litert-community/Qwen3-0.6B-int4) (Apache-2.0) on [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) (Apache-2.0), downloaded inside the app.
