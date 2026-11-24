# Sweety: requirements

Sweety is a real-time personal voice assistant with a 3D avatar, on the phone (Android) and the desktop (Windows).
She is not a chat toy: you call her by name from anywhere, talk to her like a person, and she gets things done.
The model to measure against is a real assistant (Google Assistant / Gemini Live, Alexa, Siri): wake word,
a natural back-and-forth conversation, fast spoken answers, and actions on the device.

Status legend: **Done** (in the app and tested in CI), **Built** (in the app on the test branch, CI check added, not yet on a real phone), **Partial**, **To do**.

## 1. How a conversation works

### 1.1 Waking her
| ID | Requirement | Status |
|---|---|---|
| W1 | "Hey Sweety" wakes her from any app while the screen is on and unlocked, without touching the phone. | Done |
| W2 | **"Hey Buddy" is a second wake word**, equally reliable. Each wake word can be switched on or off in settings. | To do |
| W3 | Each wake word has its own trained model (same pipeline as "Hey Sweety": `blink-android/wakeword/`), shipped only if it passes the gate: at least 80% of clearly spoken samples caught, at most 2 false wake-ups per hour of ordinary speech. | Done for Hey Sweety, to do for Hey Buddy |
| W4 | Voice Match: the user records each wake word 4 times; her detector then also accepts the user's own way of saying it. | Done for Hey Sweety, to do for Hey Buddy |
| W5 | One breath: "Hey Sweety, what time is it?" said without pausing works; the request isn't cut off. | Done |
| W6 | Wake response under 300 ms: a short time-of-day chirp, her card pops up, the mic stays open (never reopened). | Done |
| W7 | Mic only while the screen is on and unlocked, with a notification showing; closes when the screen turns off or locks. | Done |

### 1.2 Continuous conversation (new)
Once woken, Sweety stays in the conversation. The user does not say the wake word again for each question.

| ID | Requirement | Status |
|---|---|---|
| C1 | After each answer she **listens again automatically** (the mic reopens as soon as she stops speaking). Follow-up questions use the conversation so far ("And tomorrow?", "Call her"). | Built |
| C2 | The conversation lasts until the user ends it: "stop", "that's all", "thanks, that's it", "bye", "go to sleep", "never mind", "cancel", "shut up". She says a short goodbye and goes back to waiting for the wake word. | Built |
| C3 | If the user says nothing for a while (default 20 s, settable 8 s to "until I say stop"), she quietly ends the conversation. Saying the wake word again starts a new one. | Built (settings: 10 s, 20 s, 1 min, only when I say stop) |
| C4 | Stopping her mid-answer: saying "stop" or the wake word while she talks cuts her off and she listens (barge-in). This needs echo cancellation so she doesn't hear herself; on phones without it, tapping her or the card stops her. | To do |
| C5 | She never hears her own voice as the user: the mic pauses while she speaks (until C4 is in place) and the chirp is never taken as speech. | Done |
| C6 | The card shows the state at all times: listening (ears glow), thinking, speaking, and "conversation on". A small "End" button ends the conversation. | Built |
| C7 | Background noise, TV or other people talking don't keep the conversation going forever: speech that isn't directed at her (empty or nonsense transcripts twice in a row) ends the conversation. | To do |
| C8 | Actions that leave the app (calls, maps, opening an app) end the conversation after she confirms ("Calling Mom"). | Built |

### 1.3 Speed and voice
| ID | Requirement | Status |
|---|---|---|
| S1 | With Gemini: first spoken words within 1.5 s of the user finishing (streamed sentence by sentence). | Done (0.7–1.2 s measured) |
| S2 | Google's busy models never stall her: short timeouts, skip overloaded models, fall back to Lite models within the 20 s budget, and plain-words errors. | Done (on the test branch) |
| S3 | Natural voice (Kokoro, 85 MB download) with no squeaks, no long pauses between sentences, numbers and times read as words. | Done |
| S4 | Offline: first words within 2.5 s on a phone with a GPU (see B2). | Built (streams; speed not yet measured on a phone) |

## 2. Brains

| ID | Requirement | Status |
|---|---|---|
| B1 | Gemini with the user's own key (stays on the phone): current Flash models, function calling for actions, memory of facts the user shares. | Done |
| B2 | **Big offline brain: Gemma 4 E2B** (Google, Apache-2.0, about 2.6 GB, one-time download on Wi-Fi). Offered on phones with 6 GB+ RAM and 3 GB free storage. Runs on the GPU when possible, else on the CPU. Streams its answer so she starts speaking at the first sentence. Used when there's no internet or no Gemini key. | Built (not yet run on a phone; too big for the CI emulator) |
| B3 | Small offline brain (Qwen3-0.6B, 330 MB) stays as the option for low-memory phones. The user can have either or both; the big one is used when present. | Done (small) |
| B4 | Each offline brain runs in its own process; if it crashes on a phone, Sweety keeps working, and after repeated crashes she says so instead of trying forever. A GPU crash falls back to the CPU first. | Done (small), built (big) |
| B5 | Current information (weather, news, scores, "what's happening") answered by Gemini with Google Search grounding. | To do |
| B6 | Clear commands ("set an alarm for 7") run on the phone without any brain, instantly and offline. | Done |

## 3. Actions

Calls and messages always open the dialer or messaging app with everything filled in; the user presses call or send.
Anything needing a new permission is opt-in and explained first.

| Area | Actions | Status |
|---|---|---|
| Time | Alarms, timers, reminders | Done |
| | List and cancel alarms, stopwatch, "how long is left on my timer" | To do |
| People | Call, text, WhatsApp a contact | Done |
| Phone | Torch, media controls, volume, battery, time | Done |
| | Open any app by name ("open YouTube"), Wi-Fi / Bluetooth / mobile data panels, Do Not Disturb on/off, brightness, rotation lock, battery saver, "find my phone" (rings loudly) | To do |
| Out and about | Navigate to a place, find nearby places, "how long to get home" (opens Maps) | To do |
| Look things up | Web search, define a word, translate, unit and currency conversion (exact for units) | To do |
| Media | Play a song, artist or playlist on YouTube Music / Spotify / YouTube, take a photo or selfie (opens the camera) | To do |
| Calendar | "What's on today", add an event (opt-in calendar permission) | To do |
| Notes and lists | Take a note, read notes | Done |
| | Shopping and to-do lists: add, read back, clear | To do |
| Notifications | Read out new messages and reply by voice (opt-in notification access) | To do |
| Routines | "Good morning": time, weather, today's events, first reminder. "Good night": alarm for tomorrow, Do Not Disturb on | To do |
| Fun | Dances, tricks, outfits, catch the butterfly or bird | Done |

## 4. Hiding

| ID | Requirement | Status |
|---|---|---|
| H1 | "Hide", "disappear", "go away" makes Sweety **completely invisible**: the floating Sweety, her speech bubble and the talk card all disappear. | Built |
| H2 | She comes back when called ("come back", "where are you", or a wake word, which still works while she's hidden) or **by herself after 5 minutes** (settable: 5, 15, 30, 60 minutes). She pops back with a little "Peekaboo!". | Built |
| H3 | The notification shows "Sweety is hiding, back at 10:42" with a "Come back" button. | Built |

## 5. Desktop (Windows)

The desktop Sweety (`blink-desktop`, Electron) is the same assistant on the PC, not only a desktop pet.

| ID | Requirement | Status |
|---|---|---|
| D1 | Transparent desktop pet: walks the taskbar, flies around, hides, keyboard shortcuts. | Done |
| D2 | Wake words "Hey Sweety" and "Hey Buddy" with the same ONNX models as the phone (onnxruntime), mic only while the PC is unlocked, tray icon shows the mic state. | To do |
| D3 | Continuous conversation exactly as in 1.2, with the same stop phrases. | To do |
| D4 | Same brains: Gemini with the user's key; offline Gemma 4 (E4B on PCs with 16 GB+ RAM, E2B otherwise). | To do |
| D5 | Same natural voice (Kokoro via sherpa-onnx) and lip-synced 3D avatar. | To do |
| D6 | PC actions: open apps, files and folders, web search, media keys, volume, timers and reminders, screenshots, read the selected text aloud, type what you say, lock the PC, system info. | To do |
| D7 | One shared definition of actions (the function list Gemini gets) for phone and desktop, so both understand the same requests. | To do |
| D8 | Later: phone and desktop share memories and can hand over ("send this to my phone"). | Later |

## 6. Quality bars

- False wake-ups: at most 2 an hour of ordinary speech; missed wakes: at most 1 in 5.
- Battery: wake-word listening uses at most 3% an hour; the mic is off when the screen is off.
- Privacy: wake-word detection, Voice Match, the offline brains and the voice stay on the device. Audio is never stored. What the user says after waking her goes to the phone's speech recogniser, and to Gemini only if they added a key.
- Phones: Android 8+ on 64-bit; the big offline brain needs 6 GB+ RAM.
- Every requirement marked Done has an automated check in CI (host tests, the trained-model test, or the emulator tests in `blink-android/test/`). New requirements get one when they are built:
  - C1–C3: two questions in a row without a wake word; "stop" ends it; silence ends it.
  - W2: "Hey Buddy" opens her card at least 3 of 4 times; ordinary speech never does.
  - H1–H2: after "hide" nothing of hers is on screen; she is back after the hide time (shortened in the test).
  - B2: Gemma 4 answers a question in the emulator build (when the CI machine has the memory for it).

## 7. Build order

1. **Now (built, on the test branch):** continuous conversation (C1–C3, C6, C8), hiding (H1–H3), Gemma 4 E2B offline brain (B2, B4).
2. **Next:** "Hey Buddy" model and Voice Match (W2–W4), with "sweets" / "sweet tea" style near misses as hard negatives for both wake words (CI once saw "I love eating sweets" in noise wake her); actions batch 1: open apps, Maps, web search, settings panels, Do Not Disturb, stopwatch, find my phone, lists; Gemini with Google Search (B5).
3. **Then:** barge-in with echo cancellation (C4, C7), calendar, notifications, routines.
4. **Desktop:** D2–D7, reusing the phone's wake models, brains and action list.

## 8. Open decisions

- Default end of a conversation after silence: 20 s, or never (only "stop")? The doc assumes 20 s, settable.
- Should "Hey Buddy" be the same Sweety, or a second character (for example a boy robot with his own voice)? The doc assumes the same Sweety.
- Listening with the screen off or locked: Android allows it only with an always-on notification and costs battery. The doc keeps it off.
