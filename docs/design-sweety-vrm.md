# Designing Sweety as a VRM character

Sweety can appear as the built-in robot or as a 3D character you design yourself. The character file format is
**VRM**, an open standard for avatars (VTubers use it). This guide shows how to make Sweety in the free
**VRoid Studio** and put her in the app.

Sweety is your original character, so you can design her any way you like. Don't copy existing anime, game or brand
characters: they are copyrighted.

## 1. Install VRoid Studio

Free for Windows and macOS (vroid.com/en/studio, or on Steam). It works like a character creator: sliders for
face and body, hair you draw with a pen, clothes from templates you can recolour.

## 2. Make Sweety

Ideas that keep her recognisably *Sweety*:

| Part | Suggestion |
|---|---|
| Colours | Bubblegum pink `#FFB3D4`, teal trim `#56C9BA`, sunny accent `#FFD447`, soft white `#FFF0F7` |
| Eyes | Big and sparkly, cyan `#7DF9FF` with white highlights (VRoid: Eye → Iris, add a highlight) |
| Hair | Pink twin tails or a bob; twin tails bounce nicely (VRoid adds "spring bones" to hair automatically) |
| Robot touches | A small antenna or headphone-style ear discs (hair accessory), a heart on the chest (draw it on the top's texture) |
| Body | Smaller and rounder reads cuter on a phone: height about 1.3–1.45 m, slightly bigger head |
| Outfit | A simple top and skirt, or a jumpsuit, in her colours |

Keep the **standard expressions** VRoid creates: mouth shapes **A I U E O**, **Blink**, **Joy**, **Angry**,
**Sorrow**, **Fun** and **Surprised**. Sweety's lip-sync uses A I U E O, and her moods use the rest. If you tweak
them in VRoid's Face → Expression editor, just don't delete them.

## 3. Export for a phone

In VRoid Studio: **Camera/Exporter → Export → VRM**. Version **VRM 1.0** (0.x also works). Then:

- **Polygon reduction:** on, aim for **under 40,000 triangles**.
- **Texture atlas size:** 2048 (or 1024 for older phones).
- **Reduce materials:** about 8 or fewer.
- Fill in the info: title "Sweety", you as the author, and who may use the model (for example "only the author").

A good file is **10–25 MB**. Sweety accepts up to 80 MB, but smaller loads faster and saves battery.

## 4. Put her in the app

1. Copy the `.vrm` file to your phone (Google Drive, a USB cable, or email it to yourself).
2. Open Sweety → **Sweety's look (avatar)** → **Import VRM character (.vrm)** → pick the file.
3. Tap **Try it: call Sweety**. Her card pops up with your character, who blinks, looks at you, listens, talks with
   lip-sync and shows her moods and gestures (wave, dance, nod, jump, spin, stretch, shrug, giggle).
4. Switch back any time with **Robot Sweety**.

If the file can't be read as a VRM, Sweety tells you and keeps the robot.

## What the app does with your character

| Moment | What your character does |
|---|---|
| Waiting | Breathes, blinks, sways a little, eyes follow you |
| Listening | Tilts her head toward you, relaxed smile, reacts to how loud you speak |
| Thinking | Looks up and to the side, "•••" above her |
| Speaking | Mouth shapes (A I U E O) follow her natural voice; small head movements |
| Moods | Joy, Sorrow, Angry, Surprised, Fun from what she says |
| Gestures | Wave (hi), dance, nod, jump, spin, flip, cartwheel, stretch, shrug, giggle |

The file stays on your phone. Sweety doesn't upload it anywhere.
