// Sweety's avatar: one controller for two bodies, the built-in robot Sweety (blink.js) or a VRM character the user
// designed (three-vrm). The Android side drives it through window.Avatar:
//   Avatar.state('idle'|'listening'|'thinking'|'speaking')   what she's doing
//   Avatar.level(0..1)                                       your voice level while she listens
//   Avatar.speak(amp[], zcr[], fps, delayMs)                 lip-sync to her real voice (loudness + brightness per frame)
//   Avatar.speakFor(ms)                                      lip-sync without the sound data (phone voice fallback)
//   Avatar.gesture(name) / Avatar.mood(name, seconds)        wave, dance, nod… / happy, sad, surprised…
(function () {
"use strict";
const log = m => { console.log("SweetyAvatar: " + m); if (window.AndroidAvatar && AndroidAvatar.log) AndroidAvatar.log(m); };
const q = new URLSearchParams(location.search);
const canvas = document.getElementById("c"), dots = document.getElementById("dots");
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
renderer.setClearColor(0x000000, 0);
const scene = new THREE.Scene();
let cam, bot = null, vrm = null, kind = "robot", W = 1, H = 1, headY = 1.4;

const S = { state: "idle", mood: "neutral", moodUntil: 0, moodW: 0, gesture: null, gT: 0, gDur: 0, t: 0, level: 0,
  env: null, zcr: null, fps: 30, talkStart: 0, talkUntil: 0, fake: false, mouth: 0, vis: { aa: 0, ih: 0, ou: 0, ee: 0, oh: 0 },
  blinkNext: 2, blinkT: -1, peak: 0, said: false };
const GDUR = { wave: 2.4, nod: 1.4, dance: 4.2, jump: 1.1, spin: 1.3, flip: 1.3, cartwheel: 1.7, laugh: 2.2, giggle: 1.2, shrug: 1.8, stretch: 2.6 };
const clamp01 = v => Math.max(0, Math.min(1, v));
const ease = p => p < 0 ? 0 : p > 1 ? 1 : p * p * (3 - 2 * p);
const inout = (p, a = .18) => ease(p / a) * ease((1 - p) / a); // fade in, hold, fade out

/* ------------------------------------------------------------ sizing */
function size() {
  W = innerWidth; H = innerHeight;
  renderer.setSize(W, H, false);
  if (!cam) return;
  if (kind === "robot") { cam.left = 0; cam.right = W; cam.top = H; cam.bottom = 0; cam.updateProjectionMatrix(); }
  else { cam.aspect = W / H; cam.updateProjectionMatrix(); frameVrm(); }
}
addEventListener("resize", size);

/* ------------------------------------------------------------ robot Sweety */
function startRobot(skin) {
  kind = "robot";
  THREE.ColorManagement.enabled = false;              // keep her colours exactly as with the old three.js
  renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
  cam = new THREE.OrthographicCamera(0, 1, 1, 0, .1, 2000); cam.position.z = 600;
  addLights(scene, Math.PI);                          // modern three.js lights need x PI to match the old look
  bot = new Bot({ skin: skin || "bubblegum" }); scene.add(bot.group);
  size();
  log("ready robot (" + bot.skin.id + ")");
  ready({ kind: "robot", skin: bot.skin.id });
}

/* ------------------------------------------------------------ VRM character */
function startVrm(url) {
  kind = "vrm";
  THREE.ColorManagement.enabled = true;
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  cam = new THREE.PerspectiveCamera(26, W / H, .05, 20);
  const key = new THREE.DirectionalLight(0xffffff, 2.2); key.position.set(1, 2, 2.5); scene.add(key);
  const fill = new THREE.DirectionalLight(0xffe4f2, .9); fill.position.set(-2, 1, 1); scene.add(fill);
  scene.add(new THREE.HemisphereLight(0xffffff, 0xb9a9d6, 1.2));
  const loader = new VRMKit.GLTFLoader();
  loader.register(p => new VRMKit.VRMLoaderPlugin(p));
  loader.load(url, gltf => {
    vrm = gltf.userData.vrm;
    if (!vrm) { fail("that file isn't a VRM character"); return; }
    try { VRMKit.VRMUtils.removeUnnecessaryVertices(gltf.scene); } catch (e) { }
    try { if (VRMKit.VRMUtils.combineSkeletons) VRMKit.VRMUtils.combineSkeletons(gltf.scene); } catch (e) { }
    VRMKit.VRMUtils.rotateVRM0(vrm);                  // old VRM 0.x models face the other way
    vrm.scene.traverse(o => { o.frustumCulled = false; });
    scene.add(vrm.scene);
    if (vrm.lookAt) vrm.lookAt.target = cam;          // her eyes follow you
    vrm.update(0);
    vrm.scene.updateMatrixWorld(true);
    const head = vrm.humanoid.getRawBoneNode("head") || vrm.humanoid.getNormalizedBoneNode("head");
    const p = new THREE.Vector3(); if (head) head.getWorldPosition(p); headY = p.y > .3 ? p.y : 1.4;
    size();
    const names = vrm.expressionManager ? vrm.expressionManager.expressions.map(e => e.expressionName) : [];
    const need = ["aa", "ih", "ou", "ee", "oh", "blink", "happy", "sad", "angry", "relaxed", "surprised"];
    const missing = need.filter(n => !names.includes(n));
    const meta = vrm.meta || {};
    const title = meta.name || meta.title || "character";
    log("ready vrm: " + title + ", height " + headY.toFixed(2) + " m, expressions " + names.length + (missing.length ? ", missing " + missing.join(" ") : ", all she needs"));
    ready({ kind: "vrm", title, missing });
  }, undefined, e => fail(e && e.message ? e.message : String(e)));
}

function fail(why) {
  log("VRM failed (" + why + "), using robot Sweety");
  if (vrm) { scene.remove(vrm.scene); vrm = null; }
  scene.clear();
  startRobot(q.get("skin"));
}

function frameVrm() {
  // Head and shoulders down to the waist, a little room above her head.
  const dist = 1.45 * Math.max(1, .75 / (W / H));
  cam.position.set(0, headY + .02, dist);
  cam.lookAt(0, headY - .1, 0);
}

function ready(info) { if (window.AndroidAvatar && AndroidAvatar.ready) AndroidAvatar.ready(JSON.stringify(info)); }

/* ------------------------------------------------------------ what the app tells her */
const ROBOT_MOOD = { happy: "happy", love: "love", curious: "curious", surprised: "surprised", sad: "sad", sleepy: "sleepy", laugh: "laugh", dizzy: "dizzy", angry: "angry", neutral: "neutral", sleep: "sleep" };
window.Avatar = {
  state(s) {
    S.state = s;
    dots.classList.toggle("on", s === "thinking");
    if (bot) {
      if (s === "listening") { bot.lookTarget = { x: 0, y: 0 }; bot.setEmotion("curious", 30); }
      else if (s === "thinking") { bot.lookTarget = { x: .55, y: -.55 }; bot.setEmotion("curious", 30); }
      else if (s === "idle") { bot.setEmotion(bot.baseEmo, .01); bot.listen = 0; }
    }
    if (s !== "listening") S.level = 0;
  },
  level(v) { S.level = clamp01(v); },
  mood(m, secs) {
    S.mood = m || "neutral"; S.moodUntil = S.t + (secs || 3);
    if (bot) { if (m === "sleep") { bot.sleeping = true; bot.emoUntil = Infinity; } else bot.setEmotion(ROBOT_MOOD[m] || "happy", secs || 3); }
  },
  gesture(g) {
    if (!g) return;
    if (g === "sleep") { Avatar.mood("sleep", 8); return; }
    if (g.startsWith("outfit:")) { if (bot) bot.wear(g.slice(7)); return; }
    if (bot) { bot.sleeping = false; bot.play(g === "giggle" ? "laugh" : g); }
    if (vrm) { S.gesture = g === "giggle" ? "laugh" : g; S.gT = 0; S.gDur = GDUR[S.gesture] || 2; }
  },
  speak(amp, zcr, fps, delayMs) {
    S.env = amp; S.zcr = zcr; S.fps = fps || 30; S.fake = false;
    S.talkStart = performance.now() + (delayMs || 0);
    S.talkUntil = S.talkStart + amp.length * 1000 / S.fps;
    S.peak = 0; S.said = false;
    Avatar.state("speaking");
  },
  speakFor(ms) {
    S.env = null; S.fake = true; S.talkStart = performance.now(); S.talkUntil = S.talkStart + ms; S.peak = 0; S.said = false;
    Avatar.state("speaking");
  },
  stop() { S.talkUntil = 0; S.env = null; if (S.state === "speaking") Avatar.state("idle"); },
  info() {
    const v = {}; for (const k in S.vis) v[k] = +S.vis[k].toFixed(2);
    return JSON.stringify({ kind, state: S.state, mouth: +S.mouth.toFixed(2), peak: +S.peak.toFixed(2), vrm: !!vrm, headY: +headY.toFixed(2), visemes: v, gesture: S.gesture, mood: S.mood });
  }
};

/* ------------------------------------------------------------ lip-sync */
function mouthNow(now) {
  if (now < S.talkStart || now > S.talkUntil) {
    if (S.state === "speaking" && now > S.talkUntil && S.talkUntil > 0) {
      if (!S.said) { S.said = true; log("spoke " + ((S.talkUntil - S.talkStart) / 1000).toFixed(1) + " s, mouth peak " + S.peak.toFixed(2)); }
      Avatar.state("idle");
    }
    return { open: 0, z: .2 };
  }
  if (S.fake) { const k = (now - S.talkStart) / 1000; return { open: .25 + .55 * Math.abs(Math.sin(k * 11)) * (.6 + .4 * Math.sin(k * 3.7)), z: .2 + .15 * Math.sin(k * 5) }; }
  const i = Math.floor((now - S.talkStart) / 1000 * S.fps);
  const a = S.env[Math.min(i, S.env.length - 1)] || 0, z = S.zcr ? (S.zcr[Math.min(i, S.zcr.length - 1)] || 0) : .2;
  return { open: clamp01(a * 4.2), z };
}

/* ------------------------------------------------------------ VRM body language */
const BONES = ["hips", "spine", "chest", "neck", "head", "leftShoulder", "rightShoulder", "leftUpperArm", "rightUpperArm", "leftLowerArm", "rightLowerArm", "leftHand", "rightHand"];
function vrmPose(dt, m) {
  const h = vrm.humanoid, t = S.t, br = Math.sin(t * 2.1);
  const P = {};
  for (const b of BONES) P[b] = { x: 0, y: 0, z: 0 };
  // relaxed standing pose (normalized bones: T-pose is zero), breathing and a gentle sway
  P.leftUpperArm.z = -1.2; P.rightUpperArm.z = 1.2;
  P.leftLowerArm.z = -.12; P.rightLowerArm.z = .12;
  P.spine.x = .02 * br; P.chest.x = .015 * br; P.spine.z = Math.sin(t * .8) * .02;
  P.head.z = Math.sin(t * .6) * .035; P.head.y = Math.sin(t * .45) * .05;
  let rootY = 0, rootRot = { x: 0, y: 0, z: 0 };
  // what she's doing
  if (S.state === "listening") { P.head.z += .1; P.head.x -= .04 + .06 * S.level; P.neck.x -= .03 * S.level; }
  if (S.state === "thinking") { P.head.x -= .14; P.head.y += .2; P.head.z -= .08; P.spine.z += .03; }
  if (S.state === "speaking") { P.head.x += Math.sin(t * 5.3) * .02 * m.open; P.head.y += Math.sin(t * 2.7) * .03; P.leftLowerArm.y = -.15 * Math.abs(Math.sin(t * 2)); }
  if (S.mood === "sleep" || S.mood === "sleepy") { P.head.x += .18; P.head.z += .12; }
  // gestures
  if (S.gesture) {
    S.gT += dt; const p = S.gT / S.gDur, w = inout(p);
    switch (S.gesture) {
      case "wave": P.rightUpperArm.z = 1.2 - 2.15 * w; P.rightUpperArm.x = -.15 * w; P.rightLowerArm.z = (.12 + Math.sin(S.gT * 11) * .45) * w; P.head.z -= .08 * w; break;
      case "nod": P.head.x += Math.sin(p * Math.PI * 4) * .18 * w; break;
      case "dance": { const s = S.gT * 6.5; rootY = Math.abs(Math.sin(s)) * .04 * w; P.hips.z = Math.sin(s) * .08 * w; P.spine.z -= Math.sin(s) * .1 * w;
        P.leftUpperArm.z = -1.2 + (.9 + Math.sin(s) * .5) * w; P.rightUpperArm.z = 1.2 - (.9 - Math.sin(s) * .5) * w; P.leftLowerArm.z = -.12 - .9 * w; P.rightLowerArm.z = .12 + .9 * w; P.head.z += Math.sin(s) * .12 * w; break; }
      case "jump": rootY = Math.sin(Math.min(1, p) * Math.PI) * .12; P.leftUpperArm.z = -1.2 + 1.6 * Math.sin(p * Math.PI); P.rightUpperArm.z = 1.2 - 1.6 * Math.sin(p * Math.PI); break;
      case "spin": rootRot.y = ease(p) * Math.PI * 2; break;
      case "flip": rootRot.x = -ease(p) * Math.PI * 2; rootY = Math.sin(p * Math.PI) * .25; break;
      case "cartwheel": rootRot.z = ease(p) * Math.PI * 2; rootY = Math.sin(p * Math.PI) * .2; break;
      case "laugh": P.chest.x += Math.abs(Math.sin(S.gT * 14)) * .05 * w; P.head.x -= .08 * w; P.leftShoulder.z = Math.sin(S.gT * 14) * .05 * w; P.rightShoulder.z = -Math.sin(S.gT * 14) * .05 * w; break;
      case "shrug": P.leftShoulder.z = -.18 * w; P.rightShoulder.z = .18 * w; P.leftLowerArm.y = -1.1 * w; P.rightLowerArm.y = 1.1 * w; P.head.z += .12 * w; break;
      case "stretch": P.leftUpperArm.z = -1.2 + 2.5 * w; P.rightUpperArm.z = 1.2 - 2.5 * w; P.spine.x -= .1 * w; P.head.x -= .2 * w; break;
      default: break;
    }
    if (p >= 1) S.gesture = null;
  }
  for (const b of BONES) { const n = h.getNormalizedBoneNode(b); if (n) n.rotation.set(P[b].x, P[b].y, P[b].z); }
  const hips = h.getNormalizedBoneNode("hips");
  vrm.scene.position.y = rootY;
  vrm.scene.rotation.set(rootRot.x, rootRot.y, rootRot.z);
}

const MOOD_EXPR = { happy: ["happy", 1], love: ["happy", 1], laugh: ["happy", 1], sad: ["sad", 1], angry: ["angry", .9], surprised: ["surprised", 1],
  dizzy: ["surprised", .6], curious: ["relaxed", .5], sleepy: ["relaxed", 1], sleep: ["relaxed", 1], neutral: [null, 0] };
function vrmFace(dt, m) {
  const em = vrm.expressionManager;
  if (!em) return;
  for (const n of ["aa", "ih", "ou", "ee", "oh", "happy", "sad", "angry", "relaxed", "surprised", "blink"]) em.setValue(n, 0);
  // mood, fading out when it's over
  const live = S.t < S.moodUntil;
  S.moodW += ((live ? 1 : 0) - S.moodW) * Math.min(1, dt * 5);
  const me = MOOD_EXPR[S.mood] || MOOD_EXPR.neutral;
  if (me[0]) em.setValue(me[0], me[1] * S.moodW * (S.state === "speaking" ? .7 : 1));
  if (S.state === "listening") em.setValue("relaxed", Math.max(em.getValue("relaxed") || 0, .25));
  // mouth shapes: loudness opens the mouth, brightness picks the vowel (ee/ih bright, aa middle, oh/ou dark)
  const z = m.z, o = m.open, k = Math.min(1, dt * 18);
  const target = { aa: 0, ih: 0, ou: 0, ee: 0, oh: 0 };
  if (o > .04) {
    if (z > .32) { target.ee = o * .8; target.ih = o * .4; }
    else if (z > .2) { target.ih = o * .5; target.aa = o * .6; }
    else if (z > .1) { target.aa = o; }
    else { target.oh = o * .8; target.ou = o * .4; }
  }
  for (const v in target) { S.vis[v] += (target[v] - S.vis[v]) * k; em.setValue(v, Math.min(1, S.vis[v])); }
  // blinking (eyes stay shut when asleep)
  let blink = 0;
  if (S.mood === "sleep" && live) blink = 1;
  else {
    if (S.blinkT < 0 && S.t > S.blinkNext) S.blinkT = 0;
    if (S.blinkT >= 0) { S.blinkT += dt; const b = S.blinkT / .16; blink = 1 - Math.abs(1 - 2 * Math.min(1, b)); if (b >= 1) { S.blinkT = -1; S.blinkNext = S.t + 2 + Math.random() * 4; } }
  }
  em.setValue("blink", blink);
}

/* ------------------------------------------------------------ the loop */
let last = performance.now();
function frame(now) {
  requestAnimationFrame(frame);
  const dt = Math.min(.1, (now - last) / 1000); last = now; S.t += dt;
  const m = mouthNow(now);
  S.mouth += (m.open - S.mouth) * Math.min(1, dt * 20);
  if (S.state === "speaking") S.peak = Math.max(S.peak, S.mouth);
  if (bot) {
    bot.talk = S.mouth;
    bot.listen += ((S.state === "listening" ? .25 + S.level * .9 : 0) - bot.listen) * Math.min(1, dt * 10);
    const u = Math.min(H / 3.1, W / 2.3);
    bot.group.scale.setScalar(u);
    bot.group.position.set(W / 2, H * .05, 0);
    bot.update(dt);
  }
  if (vrm) { vrmPose(dt, m); vrmFace(dt, m); vrm.update(dt); }
  renderer.render(scene, cam || new THREE.PerspectiveCamera());
}

if (q.get("kind") === "vrm" && q.get("src")) startVrm(q.get("src")); else startRobot(q.get("skin"));
requestAnimationFrame(frame);
})();
