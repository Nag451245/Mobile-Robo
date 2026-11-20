Blink Pocket (mobile web version)
=================================

Nothing to install. Blink runs in the phone's web browser.

Two ways to open it
-------------------
1. The claude.ai link (easiest, best voice)
   Open the Blink Pocket link in Chrome on your phone and sign in to claude.ai.
   Voice commands use Claude's own speech recognition and Blink answers in a Claude voice.
   Works in Chrome on Android. In Safari, Firefox or the Claude mobile app the voice buttons
   fall back to typing.

2. The single file  dist\blink-mobile.html  (works offline)
   Everything is inside this one file (about 650 KB). Put it on any web host with HTTPS
   (for example your company intranet, GitHub Pages or Netlify) and open the address on the phone.
   Voice uses the phone browser's own speech recognition (Chrome on Android, Safari on iPhone)
   and the phone's own voice for replies. Browsers only allow the microphone on https:// pages,
   so voice will not work if the file is opened straight from the phone's Downloads folder;
   everything else still works there.

Using it
--------
- Tap the big mic button, then say a command. Tap it again to stop listening.
- Tap anywhere: Blink flies there. Tap a folder: Blink peeks at it or dives inside.
- Tap Blink to poke it, double-tap to make it jump, drag and let go to throw it.
- Outfits (bottom left): 9 skins. "Auto change" swaps outfits every 35-55 seconds.
- Actions (bottom right): buttons for every action, plus a box to type commands.
- "Voice replies on/off" (top right): whether Blink talks back out loud.

Voice commands
--------------
dance · wave · hi · jump · spin · tell me a joke · stretch · what time is it
come here · hide in a folder · go to sleep · wake up · bigger · smaller · help
wear the kitty / bunny / panda / sunny / snow · change outfit
happy · sad · grumpy · love · surprised · dizzy · curious · wink
stop listening · be quiet (text replies only) · talk to me (voice replies back on)
You can start with "Hey Blink", for example "Hey Blink, dance".

Rebuild after editing src\
--------------------------
node build.js       writes dist\blink-mobile.html and dist\blink-mobile-artifact.html
node serve.js       serves dist\ on http://localhost:5177 for testing on this computer
