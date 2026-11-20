// Blink Pocket: the mobile web version. Touch, roaming, folders to hide in, outfits, and voice commands.
// Voice: Claude dictation + Claude voice when opened as a claude.ai artifact; the browser's own
// speech recognition + speech synthesis when opened as a plain file or web page; typing always works.
(function(){
"use strict";
const $=s=>document.querySelector(s);
const stage=$('#stage'),canvas=$('#c'),bubble=$('#bubble'),filesEl=$('#files'),mic=$('#mic'),micLabel=$('#micLabel'),live=$('#live'),liveText=$('#liveText');
const dock=document.querySelector('.dock'),topBar=document.querySelector('.top');
const store={get(k,d){try{const v=localStorage.getItem('blink.'+k);return v==null?d:JSON.parse(v);}catch(e){return d;}},set(k,v){try{localStorage.setItem('blink.'+k,JSON.stringify(v));}catch(e){}}};
const prefs={skin:store.get('skin','bubblegum'),size:store.get('size',1),auto:store.get('auto',true),talk:store.get('talk',true)};
if(!SKIN_BY[prefs.skin])prefs.skin='bubblegum';
const vib=ms=>{try{navigator.vibrate&&navigator.vibrate(ms);}catch(e){}};

if(!window.THREE){bubble.textContent='Blink needs three.js, which did not load. Check the connection and reload.';bubble.classList.add('on');return;}

/* ---------- 3D ---------- */
const renderer=new THREE.WebGLRenderer({canvas,antialias:true,alpha:true,powerPreference:'low-power'});
renderer.setPixelRatio(Math.min(2,window.devicePixelRatio||1));renderer.setClearColor(0x000000,0);
const scene=new THREE.Scene();addLights(scene);
const cam=new THREE.OrthographicCamera(0,1,1,0,.1,2000);cam.position.z=600;
const bot=new Bot({skin:prefs.skin});scene.add(bot.group);

/* ---------- folders ---------- */
const FOLDERS=[['Photos','#FF9EC7'],['Notes','#FFD447'],['Music','#8FD3FF'],['Games','#9FE8B0'],['Recipes','#FFB38A'],['Secrets','#C9B5FF'],['Homework','#7FDCCB'],['Memes','#FF8A8A']];
for(const [n,c] of FOLDERS){const b=document.createElement('button');b.className='file';b.type='button';b.dataset.n=n;
  b.innerHTML=`<svg viewBox="0 0 48 42" aria-hidden="true"><path d="M3 9a4 4 0 0 1 4-4h11l4 4h19a4 4 0 0 1 4 4v22a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4z" fill="${c}"/><path d="M3 16h42v19a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4z" fill="#fff" opacity=".35"/></svg>${n}`;
  b.addEventListener('click',()=>{touched();onFolderTap(b);});filesEl.appendChild(b);}
const folders=()=>[...filesEl.querySelectorAll('.file')];
function rectOf(el){const r=el.getBoundingClientRect(),s=stage.getBoundingClientRect();return{x:r.left-s.left,y:r.top-s.top,w:r.width,h:r.height,n:el.dataset.n,el};}

/* ---------- layout ---------- */
const HF=2.7;
let W=1,H=1,GROUND=1,TOP=60,base=40;
const st={x:0,y:-160,vx:0,vy:0,mode:'fall',target:null,fly:null,face:0,tilt:0,scale:1,vanish:1,vanishT:1,state:'FALL',t:0,
  nextWander:6,nextAntic:9,nextOutfit:0,bubbleUntil:0,lookAt:0,lastTouch:0,hiddenSince:0,puffAcc:0,first:true,lastAntic:'',inFolder:null};
function layout(){
  W=stage.clientWidth;H=stage.clientHeight;renderer.setSize(W,H,false);
  cam.left=0;cam.right=W;cam.top=H;cam.bottom=0;cam.updateProjectionMatrix();
  const s=stage.getBoundingClientRect();GROUND=dock.getBoundingClientRect().top-s.top-2;TOP=topBar.getBoundingClientRect().bottom-s.top;
  base=clamp(Math.min(W,H*.6)*.32,96,175)*prefs.size/HF;
  if(!st.x)st.x=W*.5;st.x=clamp(st.x,base*1.3,W-base*1.3);
  if(st.mode==='ground')st.y=GROUND;else st.y=Math.min(st.y,GROUND);
}
new ResizeObserver(layout).observe(stage);layout();
const hPx=()=>HF*base*st.scale;
const minFeet=()=>TOP+hPx()+8;

/* ---------- particles ---------- */
const PGEO=new THREE.SphereGeometry(1,10,8),pmats={},puffs=[];
const pmat=c=>pmats[c]||(pmats[c]=new THREE.MeshBasicMaterial({color:c}));
function puff(x,y,color,vx,vy,size,life){const m=new THREE.Mesh(PGEO,pmat(color));m.position.set(x,H-y,40);m.scale.setScalar(size);scene.add(m);puffs.push({m,vx,vy,life:0,max:life,size});}
function burst(x,y,n=24){if(REDUCE)return;const sk=bot.skin,cols=[sk.shell,sk.accent,sk.inner,'#FFFFFF',sk.trim,sk.blush];
  for(let i=0;i<n;i++){const a=rand(0,Math.PI*2),sp=rand(90,280);puff(x,y,cols[i%cols.length],Math.cos(a)*sp,Math.sin(a)*sp+80,rand(2.5,5)*base/35,rand(.5,.9));}}
function updatePuffs(dt){for(let i=puffs.length-1;i>=0;i--){const p=puffs[i];p.life+=dt;p.m.position.x+=p.vx*dt;p.m.position.y+=p.vy*dt;p.vx*=1-dt*2;p.vy=p.vy*(1-dt*2)-60*dt;
  p.m.scale.setScalar(Math.max(.01,p.size*(1-p.life/p.max)));if(p.life>=p.max){scene.remove(p.m);puffs.splice(i,1);}}}

/* ---------- timers and movement helpers ---------- */
let seq=0,timers=[];
const later=(s,fn)=>{const my=seq;timers.push({at:st.t+s,fn,my});};
const guard=fn=>{const my=seq;return()=>{if(my===seq&&fn)fn();};};
function restoreVisible(){if(st.mode==='infile'||st.vanishT<1){st.vanishT=1;st.vanish=Math.max(st.vanish,.05);if(st.mode==='infile')st.mode=st.y>=GROUND-2?'ground':'hover';if(st.state==='INFILE')st.state='IDLE';if(st.inFolder){st.inFolder.classList.remove('busy');st.inFolder=null;}}}
function interrupt(){seq++;timers=[];restoreVisible();st.target=null;if(st.fly){st.fly=null;st.mode=st.y>=GROUND-2?'ground':'hover';if(st.mode==='ground')st.y=GROUND;}if(bot.action&&bot.action!=='change')bot.stop();}
function idle(){st.state='IDLE';st.nextWander=st.t+rand(8,20);}
function walkTo(x,then){st.target={x:clamp(x,-base,W+base),then:guard(then)};}
function flyTo(x,y,then){st.target=null;st.mode='fly';st.fly={x:clamp(x,base*1.3,W-base*1.3),y:clamp(y,minFeet(),GROUND),then:guard(then)};}
function goTo(x,y,then){y=Math.min(y,GROUND);if(st.mode==='ground'&&y>=GROUND-4)walkTo(x,then);else flyTo(x,y,then);}
function say(text,s=2.8){bubble.textContent=text;bubble.classList.add('on');st.bubbleUntil=st.t+s;}
function reply(text,s=3){say(text,s);speak(text);}
function touched(){st.lastTouch=st.t;if(st.state==='SLEEP'&&!bot.action)wake('Oh! You woke me up.');}

/* ---------- outfits ---------- */
const skinName=$('#skinName');
function setStageSkin(sk){stage.style.setProperty('--skin',sk.shell);skinName.textContent=sk.name;document.querySelectorAll('.skin').forEach(b=>b.setAttribute('aria-pressed',b.dataset.id===sk.id));}
function scheduleOutfit(){st.nextOutfit=prefs.auto?st.t+rand(35,55):Infinity;}
function wear(id,announce=true){const sk=SKIN_BY[id];if(!sk)return;if(sk.id===bot.skin.id&&!bot.pendingSkin){if(announce)reply(`I'm already wearing ${sk.name}!`,1.8);return;}
  bot.sleeping=false;bot.wear(sk);prefs.skin=id;store.set('skin',id);setStageSkin(sk);vib(20);
  if(announce)later(.7,()=>reply(pick([`Ta-da! ${sk.name} time.`,`New outfit: ${sk.name}!`,`How do I look in ${sk.name}?`]),2.4));scheduleOutfit();}
const otherSkin=()=>pick(SKINS.filter(s=>s.id!==bot.skin.id)).id;
setStageSkin(bot.skin);scheduleOutfit();

/* ---------- folders: visit or dive in ---------- */
function vanishInto(x,y,f,then){bot.play('spin');burst(x,y);st.vanishT=0;later(.55,()=>{st.mode='infile';st.state='INFILE';st.inFolder=f.el;f.el.classList.add('busy');bubble.classList.remove('on');then&&then();});}
function appearAt(x,feetY){feetY=clamp(feetY,minFeet(),GROUND);x=clamp(x,base*1.3,W-base*1.3);st.x=x;st.y=feetY;st.mode=feetY>=GROUND-2?'ground':'hover';st.vanish=.05;st.vanishT=1;
  if(st.inFolder){st.inFolder.classList.remove('busy');st.inFolder=null;}burst(x,feetY-hPx()*.5);bot.play('jump',.6);vib([15,40,15]);}
function diveInto(f){st.state='ANTIC';const cx=f.x+f.w/2;
  say(`Let me peek inside ${f.n}…`,2);
  goTo(cx,f.y+f.h+hPx()*.35,()=>later(.4,()=>vanishInto(cx,f.y+f.h*.4,f,()=>later(rand(2.5,4.5),()=>{
    const others=folders().filter(e=>e!==f.el),out=Math.random()<.5&&others.length?rectOf(pick(others)):f;
    appearAt(out.x+out.w/2,out.y+out.h+hPx()*.35);bot.setEmotion(pick(['happy','surprised','love']),2.2);
    say(out.n===f.n?pick([`Nothing in ${f.n} but crumbs!`,`${f.n} is cozy.`,'Boo!','I tidied up a little. Kidding!']):`Boo! I took a shortcut through ${out.n}.`,2.8);
    later(3,endAntic);}))));}
function visitFolder(f){const cx=f.x+f.w/2;goTo(cx,f.y+f.h+hPx()*.35,()=>{bot.setEmotion('curious',2.4);bot.lookTarget={x:0,y:-.8};say(`Ooh, ${f.n}. What's in there?`,2.4);later(2.6,idle);});}
function onFolderTap(el){const f=rectOf(el);if(st.state==='DRAG')return;interrupt();st.state='PERFORM';if(Math.random()<.6)diveInto(f);else visitFolder(f);}
function diveSomewhere(){const all=folders();if(!all.length){ANTICS.peek();return;}diveInto(rectOf(pick(all)));}

/* ---------- antics ---------- */
const JOKES=['Why did the robot go on vacation? To recharge.','I would tell you a joke about RAM, but I forgot it.','My favourite workout is Ctrl plus Alt plus Stretch.','I tried to catch some fog this morning. I mist.','Why was the phone wearing glasses? It lost its contacts.','I asked the cloud for a joke. It was still loading.'];
const ANTICS={
  spin(){bot.play('spin');bot.setEmotion('surprised',1.2);later(1.35,()=>{bot.setEmotion('dizzy',1.8);say(pick(['Whoa. Room is spinning.','Wheee!','I meant to do that.']),1.8);later(2,endAntic);});},
  dance(){bot.play('dance');bot.setEmotion('happy',4.2);say(pick(['Tiny dance break.','Can\'t stop the groove.','Dance with me?']),2);later(4.3,endAntic);},
  wave(){bot.play('wave');bot.setEmotion('happy',2.4);say(pick(['Hi! Tap the mic and talk to me.','Psst, you\'re doing awesome.','Hello from in here!']),2.6);later(2.5,endAntic);},
  stretch(){bot.play('stretch');bot.setEmotion('happy',2.6);say(pick(['Stretch break? Roll those shoulders.','Sip some water?','Look up from the screen for 20 seconds.']),3);later(2.8,endAntic);},
  hop(){bot.play('jump');bot.setEmotion('surprised',1.1);later(1.1,()=>{bot.play('jump');bot.setEmotion('happy',1.2);later(1.2,endAntic);});},
  peek(){if(st.mode!=='ground'){flyTo(st.x,GROUND,()=>ANTICS.peek());return;}const right=st.x>W/2;walkTo(right?W+base*.3:-base*.3,()=>later(1,()=>walkTo(right?W-base*1.8:base*1.8,()=>{bot.play('wave');bot.setEmotion('happy',2.2);say('Peekaboo!',2);later(2.4,endAntic);})));},
  dive(){diveSomewhere();}
};
function doAntic(){st.state='ANTIC';const keys=Object.keys(ANTICS).filter(k=>k!==st.lastAntic);const k=Math.random()<.22&&st.lastAntic!=='dive'?'dive':pick(keys);st.lastAntic=k;ANTICS[k]();}
function endAntic(){idle();st.nextAntic=st.t+rand(14,26);}
function wander(){st.state='WANDER';
  if(Math.random()<.55){flyTo(rand(base*1.5,W-base*1.5),rand(minFeet()+40,GROUND-60),()=>{if(Math.random()<.3)say(pick(['Wheee!','Just floating by.','Nice view up here.']),1.8);idle();});return;}
  if(st.mode!=='ground'){flyTo(clamp(st.x+rand(-120,120),base*1.5,W-base*1.5),GROUND,idle);return;}
  walkTo(rand(base*1.5,W-base*1.5),idle);}

/* ---------- sleeping ---------- */
function enterSleep(){interrupt();st.state='SLEEP';if(st.mode!=='ground')flyTo(st.x,GROUND);bot.sleeping=true;bot.emoUntil=Infinity;}
function wake(msg){bot.sleeping=false;bot.play('stretch');bot.setEmotion('happy',2.5);if(msg)say(msg,2.4);idle();st.lastTouch=st.t;}

/* ---------- commands (typed, tapped or spoken) ---------- */
function begin(){interrupt();bot.sleeping=false;st.state='PERFORM';st.lastTouch=st.t;}
function grow(d){const v=clamp(Math.round((prefs.size+d*.2)*10)/10,.6,1.4);if(v===prefs.size){reply(d>0?"I'm as big as I get!":"I'm as small as I get!",2);return;}prefs.size=v;store.set('size',v);layout();reply(d>0?'Look how big I am!':'Fun size!',1.8);}
function come(){begin();say('Coming!',1.2);goTo(W/2,Math.min(GROUND,H*.62),()=>{bot.play('wave');bot.setEmotion('happy',2.2);reply('Here I am!',1.8);later(2.4,idle);});}
function perform(name){begin();const done=d=>later(d,idle);
  switch(name){
    case 'wave':bot.play('wave');bot.setEmotion('happy',2.4);reply('Hi there!');done(2.5);break;
    case 'dance':bot.play('dance');bot.setEmotion('happy',4.2);reply('Busting a tiny move!');done(4.3);break;
    case 'jump':bot.play('jump');bot.setEmotion('surprised',1.1);reply('Boing!',1.4);later(1.1,()=>bot.setEmotion('happy',1.5));done(1.3);break;
    case 'spin':bot.play('spin');bot.setEmotion('surprised',1.2);reply('Wheee!',1.4);later(1.3,()=>{bot.setEmotion('dizzy',1.8);say('Whoa. The room is spinning.',1.8);});done(2.8);break;
    case 'stretch':bot.play('stretch');bot.setEmotion('happy',2.6);reply('Stretch break! Arms up, shoulders back, twenty seconds.',3.5);done(2.7);break;
    case 'joke':bot.play('laugh');bot.setEmotion('laugh',2.4);reply(pick(JOKES),4.5);done(2.5);break;
    case 'time':bot.play('nod');reply(`It's ${new Date().toLocaleTimeString([],{hour:'numeric',minute:'2-digit'})}.`);done(1.5);break;
    case 'outfit':wear(otherSkin());done(1.4);break;
    case 'sleep':enterSleep();reply('Nap time. Tap me to wake me up.',2.4);break;
    case 'wake':wake();reply("I'm awake!",1.8);break;
    case 'come':come();break;
    case 'dive':st.state='ANTIC';diveSomewhere();break;
    case 'bigger':st.state='IDLE';grow(1);break;
    case 'smaller':st.state='IDLE';grow(-1);break;
    case 'help':bot.play('nod');reply('Try: dance, wave, jump, spin, tell me a joke, stretch, what time is it, come here, hide in a folder, wear the kitty, change outfit, bigger, smaller, go to sleep.',8);done(1.5);break;
    default:bot.play('shrug');bot.setEmotion('curious',2.2);reply("I don't know that one yet. Try: dance, joke, or wear the kitty.",3.5);done(1.9);
  }}
function emote(e){bot.setEmotion(e,3.5);if(e==='surprised')bot.play('jump');else if(e==='dizzy')bot.play('spin');else if(e==='curious')bot.play('shrug');else bot.play('giggle');}
const RULES=[[/\b(come here|come to me|over here|come back here)\b/,'come'],[/\b(folder|folders|file|files|dive|hide in|hide inside|explore)\b/,'dive'],[/\b(dance|party|groove|boogie)\b/,'dance'],[/\b(wave|hi|hello|hey|yo)\b/,'wave'],[/\b(jump|hop)\b/,'jump'],[/\b(spin|twirl|turn around)\b/,'spin'],[/\b(stretch|break|exercise)\b/,'stretch'],[/\b(joke|funny|laugh)\b/,'joke'],[/\b(time|clock)\b/,'time'],[/\b(wake|awake)\b/,'wake'],[/\b(sleep|nap|rest|good ?night)\b/,'sleep'],[/\b(bigger|grow|larger)\b/,'bigger'],[/\b(smaller|shrink|tiny)\b/,'smaller'],[/\b(help|what can you do|commands)\b/,'help']];
const EMO_WORDS=[[/\b(happy|smile)\b/,'happy'],[/\b(sad|cry)\b/,'sad'],[/\b(angry|grumpy|mad)\b/,'angry'],[/\b(love|heart)\b/,'love'],[/\b(surprise|surprised|wow|shock)\b/,'surprised'],[/\b(dizzy|confused)\b/,'dizzy'],[/\b(curious|think|hmm)\b/,'curious'],[/\b(wink|playful)\b/,'wink']];
function command(text){
  let s=String(text||'').toLowerCase().replace(/[^\p{L}\p{N}\s'-]/gu,' ').replace(/\s+/g,' ').trim();
  s=s.replace(/^(hey |ok |okay )?(blink|blinky|link)\b[ ,]*/,'').trim();
  if(!s)return;
  if(/\b(stop listening|stop voice|mic off|microphone off)\b/.test(s)){stopVoice();reply('Okay, I stopped listening.',2);return;}
  if(/\b(be quiet|mute|stop talking|silent)\b/.test(s)){setTalk(false);say('Okay, I will reply in text only.',2.4);return;}
  if(/\b(talk to me|unmute|speak|talk back)\b/.test(s)){setTalk(true);reply('Yay, I can talk again!',2);return;}
  const named=SKINS.find(sk=>sk.kw.some(k=>new RegExp('\\b'+k+'\\b').test(s)));
  if(/\b(wear|outfit|skin|dress|costume|clothes|change|be a|become|turn into|switch)\b/.test(s)){begin();named?wear(named.id):wear(otherSkin());later(1.4,idle);return;}
  if(named&&/\b(cat|kitty|bunny|rabbit|panda|bear)\b/.test(s)){begin();wear(named.id);later(1.4,idle);return;}
  for(const [re,a] of RULES)if(re.test(s))return perform(a);
  for(const [re,e] of EMO_WORDS)if(re.test(s)){begin();emote(e);reply(`Feeling ${e==='angry'?'grumpy':e==='wink'?'playful':e}!`,1.8);later(2,idle);return;}
  perform('unknown');}

/* ---------- speaking (Blink's voice replies) ---------- */
const talkBtn=$('#talkBtn');
const speaker={kind:'none',t:null,voice:null,blocked:false};
function setTalk(on){prefs.talk=on;store.set('talk',on);talkBtn.setAttribute('aria-pressed',on);talkBtn.textContent=on?'Voice replies on':'Voice replies off';if(!on)stopSpeaking();}
talkBtn.addEventListener('click',()=>{touched();setTalk(!prefs.talk);if(prefs.talk)reply('I can talk now!',1.8);});
setTalk(prefs.talk);
function pickNativeVoice(){try{const vs=speechSynthesis.getVoices(),lang=(navigator.language||'en').slice(0,2);
  speaker.voice=vs.find(v=>v.lang&&v.lang.startsWith(lang)&&/female|samantha|zira|aria|jenny|google us english|karen|moira|tessa/i.test(v.name))||vs.find(v=>v.lang&&v.lang.startsWith(lang))||null;}catch(e){}}
if('speechSynthesis' in window){speaker.kind='native';pickNativeVoice();try{speechSynthesis.addEventListener('voiceschanged',pickNativeVoice);}catch(e){}}
function stopSpeaking(){try{if(speaker.kind==='claude')speaker.t.stop().catch(()=>{});else if(speaker.kind==='native')speechSynthesis.cancel();}catch(e){}}
function speak(text){
  if(!prefs.talk||speaker.blocked||!text)return;text=String(text).slice(0,500);
  if(speaker.kind==='claude'){
    if(voice.kind==='claude'&&voice.listening)return; // the platform cannot speak while it is listening
    speaker.t.speak(text,{voice:'airy',rate:1.08,language:'en'}).catch(e=>{if(e&&(e.code==='not_granted'||e.code==='unavailable')){speaker.blocked=true;}});return;}
  if(speaker.kind==='native'){try{speechSynthesis.cancel();const u=new SpeechSynthesisUtterance(text);u.pitch=1.55;u.rate=1.05;if(speaker.voice)u.voice=speaker.voice;
    if(voice.kind==='native'&&voice.want){voice.pausedForSpeech=true;try{voice.rec.abort();}catch(e){}}
    u.onend=u.onerror=()=>{if(voice.pausedForSpeech){voice.pausedForSpeech=false;if(voice.want)setTimeout(startNative,200);}};
    speechSynthesis.speak(u);}catch(e){}}}

/* ---------- listening (voice mode) ---------- */
const voice={kind:'none',d:null,rec:null,want:false,listening:false,pausedForSpeech:false};
function showLive(text){live.hidden=false;liveText.textContent=text;}
function hideLive(){live.hidden=true;}
function updateMic(){
  const on=voice.listening||(voice.kind==='native'&&voice.want);
  mic.classList.toggle('on',on);mic.classList.toggle('off',voice.kind==='none');mic.setAttribute('aria-pressed',on);
  micLabel.textContent=voice.kind==='none'?'No voice here':on?'Listening…':'Tap to talk';
  if(on){if(live.hidden||/^Tap/.test(liveText.textContent))showLive('Listening… say "dance" or "wear the kitty"');bot.baseEmo='curious';}
  else{bot.baseEmo='neutral';if(!live.hidden&&/^Listening/.test(liveText.textContent))hideLive();}
}
function heard(text){showLive('“'+text.trim()+'”');clearTimeout(heard.t);heard.t=setTimeout(()=>{if(voice.listening||voice.want)showLive('Listening…');else hideLive();},2600);}
function onPhrase(text){if(!text||!text.trim())return;st.lastTouch=st.t;heard(text);
  if(voice.kind==='claude'&&prefs.talk&&speaker.kind==='claude'){voice.want=false;voice.d.stop().catch(()=>{}).then(()=>command(text));return;}
  command(text);}
function setupNative(){const SR=window.SpeechRecognition||window.webkitSpeechRecognition;if(!SR)return false;
  try{const r=new SR();r.lang=navigator.language||'en-US';r.continuous=true;r.interimResults=true;r.maxAlternatives=1;
    r.onstart=()=>{voice.listening=true;updateMic();};
    r.onresult=e=>{let interim='';for(let i=e.resultIndex;i<e.results.length;i++){const res=e.results[i];if(res.isFinal)onPhrase(res[0].transcript);else interim+=res[0].transcript;}if(interim)showLive(interim+'…');};
    r.onerror=e=>{if(['not-allowed','service-not-allowed','audio-capture'].includes(e.error)){voice.want=false;say(e.error==='audio-capture'?'I can\'t find a microphone.':'The microphone is blocked. Allow it for this page in your browser settings, or type in Actions.',4);}};
    r.onend=()=>{voice.listening=false;if(voice.want&&!voice.pausedForSpeech)setTimeout(()=>{if(voice.want&&!voice.pausedForSpeech)startNative();},250);updateMic();};
    voice.rec=r;voice.kind='native';return true;}catch(e){return false;}}
function startNative(){try{voice.rec.start();}catch(e){}}
function stopVoice(){voice.want=false;if(voice.kind==='claude'&&voice.d)voice.d.stop().catch(()=>{});else if(voice.kind==='native'&&voice.rec){try{voice.rec.stop();}catch(e){}}voice.listening=false;updateMic();hideLive();}
setupNative();updateMic();
mic.addEventListener('click',()=>{touched();
  if(voice.kind==='claude'){const d=voice.d;
    if(d.active()){stopVoice();return;}
    stopSpeaking();voice.want=true;
    d.start().catch(e=>{voice.want=false;const c=e&&e.code;
      if(c==='not_granted')say('Voice is turned off for this page. You can turn it on in the page\'s Permissions menu, or type in Actions.',4.5);
      else if(c==='unavailable'){say('Voice isn\'t available here. Type in Actions instead.',3.5);}
      else if(c==='rate_limited')say('Too many voice requests just now. Try again in a moment.',3);
      else if(c!=='stopped'&&c!=='dismissed')say('The microphone didn\'t start. Try again.',2.5);
      updateMic();});
    bot.setEmotion('curious',2);say('I\'m listening!',1.6);return;}
  if(voice.kind==='native'){if(voice.want){stopVoice();return;}stopSpeaking();voice.want=true;startNative();updateMic();bot.setEmotion('curious',2);say('I\'m listening!',1.6);return;}
  say('This browser can\'t listen. Open Blink in Chrome, or type a command in Actions.',4);openSheet('actSheet');});

// Inside a claude.ai artifact: prefer Claude's own dictation and voice.
if(window.claude&&typeof window.claude.use==='function'){
  window.claude.use('dictation').then(d=>{if(!d||voice.listening)return;voice.kind='claude';voice.d=d;
    d.onPartial(e=>{if(e.text)showLive(e.text+'…');});
    d.onFinal(e=>onPhrase(e.text));
    d.onState(e=>{voice.listening=e.state!=='idle';
      if(e.state==='idle'){voice.want=false;if(e.reason==='unavailable'){voice.kind='none';voice.d=null;if(setupNative())say('Using your browser\'s voice instead.',2);}
        if(prefs.talk&&speaker.kind==='claude'&&e.reason==='stopped')showLive('Tap the mic for your next command');}
      updateMic();});
    updateMic();}).catch(()=>{});
  window.claude.use('tts').then(t=>{if(!t)return;speaker.kind='claude';speaker.t=t;}).catch(()=>{});
}

/* ---------- sheets ---------- */
const scrim=$('#scrim');
function openSheet(id){closeSheets();$('#'+id).classList.add('open');scrim.classList.add('on');}
function closeSheets(){document.querySelectorAll('.sheet.open').forEach(s=>s.classList.remove('open'));scrim.classList.remove('on');}
scrim.addEventListener('click',closeSheets);
$('#outfitBtn').addEventListener('click',()=>{touched();openSheet('outfitSheet');});
$('#actBtn').addEventListener('click',()=>{touched();openSheet('actSheet');});
$('#closeAct').addEventListener('click',closeSheets);
const skinsEl=$('#skins');
for(const sk of SKINS){const b=document.createElement('button');b.className='skin';b.type='button';b.dataset.id=sk.id;
  b.innerHTML=`<canvas width="192" height="96"></canvas><span>${sk.name}</span>`;b.style.background=`linear-gradient(180deg,${sk.shell}33,#fff)`;
  drawFace(b.querySelector('canvas').getContext('2d'),192,96,'happy',1,0,0,0,sk);
  b.addEventListener('click',()=>{touched();closeSheets();interrupt();st.state='PERFORM';wear(sk.id);later(1.4,idle);});skinsEl.appendChild(b);}
setStageSkin(bot.skin);
const autoBtn=$('#autoBtn');
function setAuto(on){prefs.auto=on;store.set('auto',on);autoBtn.setAttribute('aria-pressed',on);autoBtn.textContent=on?'Auto change on':'Auto change off';scheduleOutfit();}
autoBtn.addEventListener('click',()=>setAuto(!prefs.auto));setAuto(prefs.auto);
const ACTS=[['dance','Dance'],['wave','Wave'],['jump','Jump'],['spin','Spin'],['joke','Joke'],['stretch','Stretch'],['come','Come here'],['dive','Hide'],['bigger','Bigger'],['smaller','Smaller'],['sleep','Nap'],['outfit','New outfit']];
const actsEl=$('#acts');
for(const [id,l] of ACTS){const b=document.createElement('button');b.className='act';b.type='button';b.textContent=l;b.addEventListener('click',()=>{touched();closeSheets();perform(id);});actsEl.appendChild(b);}
$('#cmdForm').addEventListener('submit',e=>{e.preventDefault();const inp=$('#cmdInput');const v=inp.value;inp.value='';inp.blur();closeSheets();touched();command(v);});

/* ---------- touch: tap to send Blink, poke, drag and throw ---------- */
let rect={l:-1,r:-1,t:-1,b:-1},pd=null,samples=[],pokes=[],lastTap=0;const pointer={x:-9999,y:-9999};
function hitRect(){if(st.mode==='infile')return{l:-1,r:-1,t:-1,b:-1};const b=new THREE.Box3().setFromObject(bot.root);return{l:b.min.x-14,r:b.max.x+14,t:H-b.max.y-14,b:H-b.min.y+10};}
const overBot=(x,y)=>x>=rect.l&&x<=rect.r&&y>=rect.t&&y<=rect.b;
const local=e=>{const s=stage.getBoundingClientRect();return{x:e.clientX-s.left,y:e.clientY-s.top};};
canvas.addEventListener('pointerdown',e=>{const p=local(e);pointer.x=p.x;pointer.y=p.y;touched();try{canvas.setPointerCapture(e.pointerId);}catch(_){}
  pd={x:p.x,y:p.y,t:performance.now(),onBot:overBot(p.x,p.y),offX:st.x-p.x,offY:st.y-p.y,drag:false};samples=[];});
canvas.addEventListener('pointermove',e=>{const p=local(e);pointer.x=p.x;pointer.y=p.y;if(!pd||!pd.onBot)return;
  if(!pd.drag&&Math.hypot(p.x-pd.x,p.y-pd.y)>8){pd.drag=true;interrupt();bot.sleeping=false;st.state='DRAG';st.mode='drag';bot.play('carried');bot.setEmotion('surprised',0);say(pick(['Whoa, where are we going?','Wheee!','Hold me gently!']),1.6);vib(15);}
  if(pd.drag){st.x=p.x+pd.offX;st.y=p.y+pd.offY;samples.push({x:st.x,y:st.y,t:performance.now()});if(samples.length>8)samples.shift();}});
canvas.addEventListener('pointerup',e=>{if(!pd)return;const p=local(e),d=pd;pd=null;
  if(d.drag){const a=samples[0],b=samples[samples.length-1];let vx=0,vy=0;if(a&&b&&b.t>a.t){const dt=(b.t-a.t)/1000;vx=(b.x-a.x)/dt;vy=(b.y-a.y)/dt;}
    st.vx=clamp(vx,-2600,2600);st.vy=clamp(vy,-2600,2600);bot.stop();
    if(Math.hypot(vx,vy)<250&&st.y<GROUND-hPx()*.5){st.mode='hover';bot.setEmotion('happy',1.5);say('I\'ll float here.',1.6);idle();return;}
    st.mode='fall';st.state='FALL';bot.setEmotion('surprised',3);return;}
  const quick=performance.now()-d.t<450&&Math.hypot(p.x-d.x,p.y-d.y)<12;if(!quick)return;
  if(d.onBot){const now=performance.now();if(now-lastTap<320){lastTap=0;begin();bot.play('jump');bot.setEmotion('happy',1.5);vib([10,30,10]);later(1.2,idle);return;}lastTap=now;poke();return;}
  // tap on empty space: Blink flies there
  if(st.state==='SLEEP')return;interrupt();st.state='PERFORM';say(pick(['On my way!','Coming!','There? Okay!']),1.2);
  const near=p.y>GROUND-hPx()*.9;goTo(p.x,near?GROUND:p.y+hPx()*.55,()=>{bot.play('giggle',.6);bot.setEmotion('happy',1.5);later(1,idle);});});
canvas.addEventListener('pointercancel',()=>{if(pd&&pd.drag){st.mode='fall';st.state='FALL';bot.stop();}pd=null;});
function poke(){vib(12);pokes.push(st.t);pokes=pokes.filter(t=>st.t-t<10);
  if(st.state==='SLEEP'){wake('Oh! I was just resting my eyes.');return;}
  if(pokes.length>=5){pokes=[];begin();bot.play('shrug');bot.setEmotion('angry',3);say('Hey! Personal space, please.',2.4);later(2,idle);return;}
  if(bot.action==='carried'||bot.action==='change')return;
  bot.play('giggle');bot.setEmotion(pick(['happy','happy','love']),1.6);say(pick(['Hehe, that tickles!','Boop received.','Again? Again!','You found me!']),1.6);}
function onLand(impact){bot.stop();
  if(st.first){st.first=false;bot.play('jump',.5);later(.5,()=>{bot.play('wave');bot.setEmotion('happy',2.6);});
    later(.6,()=>say('Hi! I\'m Blink. Tap the mic and say "dance", or tap anywhere and I\'ll fly there.',5));later(3,idle);st.state='PERFORM';return;}
  if(impact>1500){bot.play('giggle',.6);bot.setEmotion('dizzy',2.2);say(pick(['Whoa! Again! …or not.','Ouch, my circuits!','Stuck the landing. Mostly.']),2);vib(30);}
  else if(impact>300){bot.play('giggle',.6);bot.setEmotion('happy',1.5);}
  st.state='PERFORM';later(1.2,idle);}

/* ---------- main loop ---------- */
let last=performance.now(),acc=0;
document.addEventListener('visibilitychange',()=>{if(document.hidden){if(voice.kind==='native')stopVoice();}last=performance.now();});
function frame(now){requestAnimationFrame(frame);const raw=Math.min(.1,(now-last)/1000);last=now;acc+=raw;if(acc<1/30)return;const dt=Math.min(.1,acc);acc=0;tick(dt);}
function tick(dt){
  st.t+=dt;
  if(timers.length){const due=timers.filter(t=>t.at<=st.t);timers=timers.filter(t=>t.at>st.t);for(const t of due)if(t.my===seq){try{t.fn();}catch(err){console.error(err);}}}
  if(!isFinite(st.x)||!isFinite(st.y)){st.x=W/2;st.y=GROUND;st.mode='ground';st.fly=null;st.target=null;}
  if(st.vanishT<1||st.mode==='infile'){if(!st.hiddenSince)st.hiddenSince=st.t;if(st.t-st.hiddenSince>10){interrupt();appearAt(st.x,st.y);say("Ta-da! I'm back.",2);idle();st.hiddenSince=0;}}else st.hiddenSince=0;
  if(st.state==='IDLE'&&!REDUCE){
    if(st.t-st.lastTouch>120&&!voice.listening){enterSleep();}
    else if(st.t>st.nextOutfit){st.state='ANTIC';wear(otherSkin());later(2.4,endAntic);}
    else if(st.t>st.nextAntic)doAntic();
    else if(st.t>st.nextWander)wander();
  }
  // eyes follow the last touch
  const cy=st.y-hPx()*.6;
  if(pointer.x>-9000&&st.state!=='SLEEP'&&st.t-st.lastTouch<4){bot.lookTarget={x:clamp((pointer.x-st.x)/200,-1,1),y:clamp((pointer.y-cy)/200,-1,1)};st.lookAt=st.t+1.5;}
  else if(st.t>st.lookAt){st.lookAt=st.t+rand(1.5,3.5);bot.lookTarget={x:rand(-.8,.8),y:rand(-.4,.5)};}
  let flyPose=0,tiltT=0;
  if(st.mode==='fall'){st.vy+=2600*dt;st.x+=st.vx*dt;st.y+=st.vy*dt;st.vx*=1-dt*.5;
    const half=base*1.1;if(st.x<half){st.x=half;st.vx=Math.abs(st.vx)*.45;}if(st.x>W-half){st.x=W-half;st.vx=-Math.abs(st.vx)*.45;}
    if(st.y-hPx()<TOP&&st.vy<0&&!st.first){st.y=TOP+hPx();st.vy=-st.vy*.35;}
    st.face=clamp(st.vx/800,-.8,.8);
    if(st.y>=GROUND){st.y=GROUND;const impact=st.vy;st.vy=0;st.vx=0;st.mode='ground';onLand(impact);}}
  else if(st.mode==='fly'&&st.fly){const f=st.fly,dx=f.x-st.x,dy=f.y-st.y,dist=Math.hypot(dx,dy);
    const speed=Math.min(420,110+dist*1.8),step=speed*dt;flyPose=1;
    if(dist<=step+.5){st.x=f.x;st.y=f.y;st.fly=null;if(st.y>=GROUND-2){st.y=GROUND;st.mode='ground';bot.play('giggle',.5);}else st.mode='hover';f.then&&f.then();}
    else{st.x+=dx/dist*step;st.y+=dy/dist*step;st.face=clamp(dx/dist,-1,1)*.85;tiltT=clamp(dx/dist,-1,1)*-.28;
      st.puffAcc+=dt;while(st.puffAcc>.05){st.puffAcc-=.05;puff(st.x+rand(-base*.2,base*.2),st.y+rand(0,4),pick([bot.skin.accent,'#FFFFFF',bot.skin.inner]),rand(-20,20)-dx/dist*60,-rand(60,130),rand(2,3.6)*base/35,rand(.35,.6));}}}
  else if(st.mode==='hover'){flyPose=.6;st.face=0;st.puffAcc+=dt;while(st.puffAcc>.16){st.puffAcc-=.16;puff(st.x+rand(-base*.15,base*.15),st.y+2,pick(['#FFFFFF',bot.skin.accent]),rand(-15,15),-rand(40,90),rand(1.6,2.6)*base/35,rand(.3,.5));}}
  else if(st.mode==='ground'){
    if(st.target){const dx=st.target.x-st.x,step=base*2.6*dt;
      if(Math.abs(dx)<=step){st.x=st.target.x;const th=st.target.then;st.target=null;bot.walk=0;st.face=0;th&&th();}
      else{st.x+=Math.sign(dx)*step;bot.walk=1;st.face=Math.sign(dx)*.95;}}
    else{bot.walk=0;st.face=0;}
    st.y=GROUND;}
  if(st.mode!=='ground')bot.walk=0;
  if(st.mode==='infile')flyPose=0;
  bot.fly=lerp(bot.fly,flyPose,Math.min(1,dt*6));
  st.tilt=lerp(st.tilt,tiltT,Math.min(1,dt*6));
  st.vanish=lerp(st.vanish,st.vanishT,Math.min(1,dt*(st.vanishT>st.vanish?7:9)));
  const bob=st.mode==='hover'?Math.sin(st.t*2.2)*5:0;
  bot.group.rotation.y=lerp(bot.group.rotation.y,st.face,Math.min(1,dt*8));bot.group.rotation.z=st.tilt;
  bot.group.scale.setScalar(Math.max(.001,base*st.scale*st.vanish));bot.group.position.set(st.x,H-st.y+bob,0);
  bot.group.visible=st.mode!=='infile'||st.vanish>.02;
  bot.update(dt);updatePuffs(dt);
  renderer.render(scene,cam);
  rect=hitRect();
  if(bubble.classList.contains('on')){if(st.t>st.bubbleUntil)bubble.classList.remove('on');
    const bw=bubble.offsetWidth,bh=bubble.offsetHeight,top=rect.t>0?rect.t:st.y-hPx();
    let by=top-bh-10;if(by<TOP+4)by=Math.min(rect.b+10,GROUND-bh-8);
    bubble.style.transform=`translate(${clamp(st.x-bw*.3,12,W-bw-12)}px,${clamp(by,TOP+4,GROUND-bh-6)}px)`;}
}
requestAnimationFrame(frame);
})();
