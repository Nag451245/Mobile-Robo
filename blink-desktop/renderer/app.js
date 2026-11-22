// Blink desktop behaviour: walks the taskbar edge, flies around the visible desktop, follows desktop clicks,
// hides in desktop files, antics, outfits, guards, drag-and-throw, commands and keyboard shortcuts.
(async function(){
"use strict";
const api=window.blink;
let settings=await api.getSettings();
const fx=document.getElementById('fx'),fxg=fx.getContext('2d');
let critter=null,fxDirty=false;
const canvas=document.getElementById('c'),bubble=document.getElementById('bubble'),palette=document.getElementById('palette'),input=document.getElementById('cmd');

const renderer=new THREE.WebGLRenderer({canvas,antialias:true,alpha:true,powerPreference:'low-power'});
renderer.setPixelRatio(Math.min(1.5,window.devicePixelRatio||1));renderer.setClearColor(0x000000,0);
const scene=new THREE.Scene();addLights(scene);
const cam=new THREE.OrthographicCamera(0,1,1,0,.1,2000);cam.position.z=600;
const bot=new Bot({skin:settings.skin});scene.add(bot.group);

const HF=2.7,SIZES={S:64,M:96,L:128};
const MODE={calm:[360,720],normal:[120,240],lively:[20,40]};
const OUTFIT={off:null,sometimes:[600,1200],often:[60,120]};
let W=innerWidth,H=innerHeight,base=(SIZES[settings.size]||96)/HF;
// mode: ground | fall | drag | fly | hover | infile
const st={x:W*.78,y:-120,vx:0,vy:0,mode:'fall',target:null,fly:null,face:0,tilt:0,scale:1,scaleT:1,op:1,opT:1,vanish:1,vanishT:1,state:'FALL',t:0,
  nextWander:8,nextAntic:12,nextOutfit:0,bubbleUntil:0,lookAt:0,quietUntil:0,stayUntil:0,nextProbe:0,puffAcc:0,firstLanding:true,snoozed:false,sleepByCmd:false,nightApplied:false,dayskin:null,lastAntic:''};
const sense={busy:false,away:false};
const mouse={x:-9999,y:-9999};
let paletteOpen=false,interactive=false,pd=null,dragSamples=[],pokes=[],lastClick=0;
const scheduleOutfit=()=>{const r=OUTFIT[settings.autoOutfit];st.nextOutfit=r?st.t+rand(r[0],r[1]):Infinity;};
const scheduleAntic=()=>{const r=MODE[settings.mode]||MODE.lively;st.nextAntic=st.t+rand(r[0],r[1]);};
scheduleOutfit();scheduleAntic();

function resize(){W=innerWidth;H=innerHeight;fx.width=W;fx.height=H;fxDirty=true;renderer.setSize(W,H,false);cam.left=0;cam.right=W;cam.top=H;cam.bottom=0;cam.updateProjectionMatrix();if(st.mode==='ground')st.y=H;st.x=clamp(st.x,30,W-30);st.y=Math.min(st.y,H);}
addEventListener('resize',resize);resize();

/* ---------- particles in screen space (jet puffs, vanish bursts) ---------- */
const PGEO=new THREE.SphereGeometry(1,10,8),pmats={},puffs=[];
const pmat=c=>pmats[c]||(pmats[c]=new THREE.MeshBasicMaterial({color:c}));
function puff(xCss,yCss,color,vx,vy,size,life){const m=new THREE.Mesh(PGEO,pmat(color));m.position.set(xCss,H-yCss,40);m.scale.setScalar(size);scene.add(m);puffs.push({m,vx,vy,life:0,max:life,size});}
function burst(xCss,yCss,n=22){if(REDUCE)return;const sk=bot.skin,cols=[sk.shell,sk.accent,sk.inner,'#FFFFFF',sk.trim,sk.blush];
  for(let i=0;i<n;i++){const a=rand(0,Math.PI*2),sp=rand(80,260);puff(xCss,yCss,cols[i%cols.length],Math.cos(a)*sp,Math.sin(a)*sp+80,rand(2.5,5)*base/35,rand(.5,.9));}}
function updatePuffs(dt){for(let i=puffs.length-1;i>=0;i--){const p=puffs[i];p.life+=dt;p.m.position.x+=p.vx*dt;p.m.position.y+=p.vy*dt;p.vx*=1-dt*2;p.vy=p.vy*(1-dt*2)-60*dt;
  p.m.scale.setScalar(Math.max(.01,p.size*(1-p.life/p.max)));if(p.life>=p.max){scene.remove(p.m);puffs.splice(i,1);}}}

/* ---------- small helpers ---------- */
let seq=0,timers=[];
const later=(s,fn)=>{const my=seq;timers.push({at:st.t+s,fn,my});};
const guard=fn=>{const my=seq;return ()=>{if(my===seq&&fn)fn();};};
function restoreVisible(){if(st.mode==='infile'||st.vanishT<1){st.vanishT=1;st.vanish=Math.max(st.vanish,.05);if(st.mode==='infile'){st.mode=st.y>=H-2?'ground':'hover';if(st.mode==='ground')st.y=H;}if(st.state==='INFILE')st.state='IDLE';}}
function interrupt(){seq++;timers=[];if(critter){critter=null;fxDirty=true;}restoreVisible();st.target=null;if(st.fly){st.fly=null;st.mode=st.y>=H-2?'ground':'hover';if(st.mode==='ground')st.y=H;}if(bot.action&&bot.action!=='change')bot.stop();}
const hPx=()=>HF*base*st.scale;
const short=n=>{n=String(n||'that file');return n.length>26?n.slice(0,24)+'…':n;};
function say(text,s=2.8,list=false){bubble.classList.toggle('list',list);bubble.textContent=text;bubble.classList.add('on');st.bubbleUntil=st.t+s;}
function idle(){st.state='IDLE';st.nextWander=st.t+rand(10,28);}
function walkTo(x,then){st.target={x:clamp(x,-base,W+base),then:guard(then)};}
function flyTo(x,y,then){st.target=null;st.mode='fly';st.fly={x:clamp(x,base*1.3,W-base*1.3),y:clamp(y,hPx()+10,H),then:guard(then)};}
function goTo(x,y,then){y=Math.min(y,H);if(st.mode==='ground'&&y>=H-4)walkTo(x,then);else flyTo(x,y,then);}
function trayIcon(){const c=document.createElement('canvas');c.width=c.height=32;const x=c.getContext('2d');const sk=bot.skin;
  x.fillStyle=sk.shell;rrect(x,2,4,28,24,10);x.fill();x.fillStyle=sk.bg[0];rrect(x,6,8,20,15,6);x.fill();
  x.fillStyle=sk.eye;rrect(x,9.5,10.5,4.5,8,2.2);x.fill();rrect(x,18,10.5,4.5,8,2.2);x.fill();x.fillStyle=sk.blush;x.fillRect(4,21,3,2);x.fillRect(25,21,3,2);return c.toDataURL('image/png');}
bot.onSkin=()=>api.trayIcon(trayIcon());
api.trayIcon(trayIcon());

/* ---------- outfits ---------- */
function wear(id,{save=true,announce=true}={}){const sk=SKIN_BY[id];if(!sk)return;if(sk.id===bot.skin.id&&!bot.pendingSkin){if(announce)say(`I'm already wearing ${sk.name}!`,1.8);return;}
  bot.sleeping=false;bot.wear(sk);if(save){settings.skin=id;api.saveSettings({skin:id});}
  if(announce)later(.7,()=>say(pick([`Ta-da! ${sk.name} time.`,`New outfit: ${sk.name}!`,`How do I look in ${sk.name}?`]),2.4));scheduleOutfit();}
const otherSkin=()=>pick(SKINS.filter(s=>s.id!==bot.skin.id&&s.id!=='nightcap')).id;
const today=()=>new Date().toISOString().slice(0,10);
function seasonalOrRandom(){const d=new Date(),m=d.getMonth(),day=d.getDate();if((m===11)||(m===0&&day<=6))return 'snow';return otherSkin();}
function nightCheck(){if(!settings.nightCap){if(st.nightApplied){st.nightApplied=false;wear(st.dayskin||'bubblegum',{save:false});}return;}
  const h=new Date().getHours(),night=h>=21||h<6;
  if(night&&!st.nightApplied&&bot.skin.id!=='nightcap'){st.nightApplied=true;st.dayskin=bot.skin.id;wear('nightcap',{save:false,announce:false});later(.8,()=>say('Getting late. Pyjama time!',2.4));}
  else if(!night&&st.nightApplied){st.nightApplied=false;wear(st.dayskin||settings.skin,{save:false,announce:false});later(.8,()=>say('Good morning!',2));}}
setInterval(()=>{if(!st.snoozed&&['IDLE','HUSH','SLEEP'].includes(st.state))nightCheck();},30000);

/* ---------- guards ---------- */
function setHushLook(on){st.opT=on?.42:1;st.scaleT=on?.75:1;bot.baseEmo=on?'sleepy':'neutral';if(bot.emoUntil===Infinity&&!bot.sleeping)bot.emo=bot.baseEmo;}
function enterHush(){interrupt();st.state='HUSH';setHushLook(true);const cx=st.x<W/2?Math.max(50,base*1.4):W-Math.max(50,base*1.4);goTo(cx,H);}
function exitHush(){setHushLook(false);idle();}
function enterSleep(byCmd){interrupt();st.state='SLEEP';st.sleepByCmd=!!byCmd;setHushLook(false);if(st.mode!=='ground')flyTo(st.x,H);bot.sleeping=true;bot.emoUntil=Infinity;}
function wake(msg){bot.sleeping=false;st.sleepByCmd=false;bot.play('stretch');bot.setEmotion('happy',2.5);if(msg)say(msg);idle();st.quietUntil=st.t+3;}

/* ---------- desktop files ---------- */
function vanishInto(x,y,then){bot.play('spin');burst(x,y);st.vanishT=0;later(.55,()=>{st.mode='infile';st.state='INFILE';bubble.classList.remove('on');then&&then();});}
function appearAt(x,feetY){feetY=clamp(feetY,hPx()+8,H);x=clamp(x,base*1.3,W-base*1.3);st.x=x;st.y=feetY;st.mode=feetY>=H-2?'ground':'hover';st.vanish=.05;st.vanishT=1;burst(x,feetY-hPx()*.5);bot.play('jump',.6);}
function diveInto(icon,icons){st.state='ANTIC';const cx=icon.x+icon.w/2,fy=icon.y+icon.h*.62;st.stayUntil=st.t+30;
  say(`Let me look inside "${short(icon.n)}"…`,2.2);
  goTo(cx,fy,()=>later(.5,()=>vanishInto(cx,icon.y+icon.h*.4,()=>later(rand(3,6),()=>{
    const others=icons.filter(i=>i!==icon),out=others.length&&Math.random()<.5?pick(others):icon;
    appearAt(out.x+out.w/2,out.y+out.h*.62);bot.setEmotion(pick(['happy','surprised','love']),2.2);
    say(out===icon?pick(['Nothing in there but bytes!','Cozy in there.','I tidied up a little. Kidding!','Boo!']):`Boo! I took a shortcut through "${short(out.n)}".`,2.8);
    later(3,()=>{st.stayUntil=st.t+8;endAntic();});}))));}
function visitIcon(icon){const right=icon.x+icon.w+base*1.6<W;const x=right?icon.x+icon.w+base*.9:icon.x-base*.9;st.stayUntil=st.t+15;
  goTo(x,icon.y+icon.h,()=>{bot.setEmotion('curious',2.6);bot.lookTarget={x:right?-.9:.9,y:.2};say(`Ooh, "${short(icon.n)}". What's in it?`,2.6);later(2.8,idle);});}
async function diveSomewhere(){const my=seq;const icons=await api.icons();if(my!==seq)return;
  if(!icons.length){say("I don't see any files on your desktop to hide in.",2.4);later(.1,()=>ANTICS.peek());return;}
  const near=icons.slice().sort((a,b)=>Math.hypot(a.x-st.x,a.y-st.y)-Math.hypot(b.x-st.x,b.y-st.y));diveInto(pick(near.slice(0,4)),icons);}

/* ---------- antics ---------- */
const ANTICS={
  spin(){bot.play('spin');bot.setEmotion('surprised',1.2);later(1.35,()=>{bot.setEmotion('dizzy',1.8);say(pick(['Whoa. Room is spinning.','Wheee!','I meant to do that.']),1.8);later(2,endAntic);});},
  dance(){bot.play('dance');bot.setEmotion('happy',4.2);say(pick(['Tiny dance break.','Can\'t stop the groove.','Dance with me?']),2);later(4.3,endAntic);},
  wave(){bot.play('wave');bot.setEmotion('happy',2.4);say(pick(['Hi! Doing great so far.','Psst, you\'re doing awesome.','Hello from over here!']),2.4);later(2.5,endAntic);},
  stretch(){bot.play('stretch');bot.setEmotion('happy',2.6);say(pick(['Stretch break? Roll those shoulders.','Sip some water?','Look away from the screen for 20 seconds.']),3);later(2.8,endAntic);},
  peek(){if(st.mode!=='ground'){flyTo(st.x,H,()=>ANTICS.peek());return;}const right=st.x>W/2;walkTo(right?W+base*.3:-base*.3,()=>later(1,()=>walkTo(right?W-base*1.8:base*1.8,()=>{bot.play('wave');bot.setEmotion('happy',2.2);say('Peekaboo!',2);later(2.4,endAntic);})));},
  hop(){bot.play('jump');bot.setEmotion('surprised',1.1);later(1.1,()=>{bot.play('jump');bot.setEmotion('happy',1.2);later(1.2,endAntic);});},
  joke(){bot.play('laugh');bot.setEmotion('laugh',2.4);say(pick(JOKES),4);later(4,endAntic);},
  dive(){diveSomewhere();}
};
function doAntic(){st.state='ANTIC';let keys=Object.keys(ANTICS).filter(k=>k!==st.lastAntic);if(settings.mode!=='lively')keys=keys.filter(k=>k!=='joke');if(!settings.roam)keys=keys.filter(k=>k!=='dive');
  const k=Math.random()<.22&&settings.roam&&st.lastAntic!=='dive'?'dive':pick(keys);st.lastAntic=k;ANTICS[k]();}
function endAntic(){idle();scheduleAntic();}
async function wander(){st.state='WANDER';const my=seq;
  if(settings.roam&&!interactive&&Math.random()<.5){
    const pts=[];for(let i=0;i<10;i++)pts.push({x:rand(90,W-90),y:rand(hPx()+60,H-hPx()-50)});
    const ok=await api.probe(pts);if(my!==seq||st.state!=='WANDER')return;
    const cand=pts.filter((p,i)=>ok[i]&&Math.hypot(p.x-mouse.x,p.y-mouse.y)>170);
    if(cand.length){const p=pick(cand);flyTo(p.x,p.y+hPx()*.5,()=>{if(Math.random()<.3)say(pick(['Nice view from up here.','Wheee!','Just floating by.']),2);idle();});return;}
  }
  if(st.mode!=='ground'){flyTo(clamp(st.x+rand(-220,220),60,W-60),H,()=>idle());return;}
  let x=st.x;for(let i=0;i<8;i++){x=rand(60,W-60);if(Math.abs(x-st.x)>150&&Math.abs(x-mouse.x)>140)break;}
  walkTo(x,()=>idle());}

/* ---------- commands ---------- */
const JOKES=['Why did the robot go on vacation? To recharge.','I would tell you a joke about RAM, but I forgot it.','My favourite workout is Ctrl + Alt + Stretch.','I tried to catch some fog this morning. I mist.','Debugging: removing the needles from the haystack you put there.','I asked the cloud for a joke. It was still loading.'];
function begin(){interrupt();bot.sleeping=false;setHushLook(false);st.state='PERFORM';st.quietUntil=st.t+4;}
function come(){begin();const x=mouse.x,y=mouse.y;st.stayUntil=st.t+15;say(pick(['Coming!','On my way!','Wait for me!']),1.4);
  const near=y>H-hPx()*1.3;goTo(x,near?H:y+hPx()*.55,()=>{bot.play('wave');bot.setEmotion('happy',2.2);say('Here I am!',1.6);later(2.4,idle);});}
function perform(name){if(st.snoozed)return;begin();
  const done=d=>later(d,()=>{idle();st.quietUntil=st.t+2;});
  switch(name){
    case 'wave':bot.play('wave');bot.setEmotion('happy',2.4);say('Hi there!');done(2.5);break;
    case 'dance':bot.play('dance');bot.setEmotion('happy',4.2);say('Busting a tiny move.');done(4.3);break;
    case 'jump':bot.play('jump');bot.setEmotion('surprised',1.1);later(1.1,()=>bot.setEmotion('happy',1.5));done(1.3);break;
    case 'spin':bot.play('spin');bot.setEmotion('surprised',1.2);later(1.3,()=>{bot.setEmotion('dizzy',1.8);say('Whoa. The room is spinning.',1.8);});done(2.8);break;
    case 'stretch':bot.play('stretch');bot.setEmotion('happy',2.6);say('Stretch break! Arms up, shoulders back, 20 seconds.',3.5);done(2.7);break;
    case 'joke':bot.play('laugh');bot.setEmotion('laugh',2.4);say(pick(JOKES),4.5);done(2.5);break;
    case 'time':bot.play('nod');say(`It's ${new Date().toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'})}.`);done(1.5);break;
    case 'outfit':wear(otherSkin());done(1.4);break;
    case 'sleep':enterSleep(true);say('Nap time. Poke me to wake me up.',2.4);break;
    case 'wake':wake("I'm back!");break;
    case 'napToggle':if(bot.sleeping||st.state==='SLEEP')wake("I'm awake!");else{enterSleep(true);say('Nap time. Ctrl+Alt+N wakes me.',2.4);}break;
    case 'come':come();break;
    case 'dive':st.state='ANTIC';diveSomewhere();break;
    case 'test-vanishcut':vanishInto(st.x,st.y-hPx()/2,()=>later(4,()=>appearAt(st.x,st.y)));setTimeout(()=>perform('wave'),200);break;
    case 'test-infilecut':vanishInto(st.x,st.y-hPx()/2,()=>later(4,()=>appearAt(st.x,st.y)));setTimeout(()=>{seq++;timers=[];},1500);break;
    case 'help':bot.play('nod');say('Try: catch the butterfly, catch the bird, dance, wave, jump, spin, joke, stretch, time, nap, come here, hide in a file, wear the kitty, change outfit, bigger, smaller, hide. Ctrl+Alt+/ lists my keyboard shortcuts.',8);done(1.5);break;
    default:bot.play('shrug');bot.setEmotion('curious',2.2);say("I don't know that one yet. Try: dance, joke, wear the kitty.",3.5);done(1.9);
  }}
function emote(e){bot.setEmotion(e,3.5);if(e==='surprised')bot.play('jump');else if(e==='dizzy')bot.play('spin');else if(e==='curious')bot.play('shrug');else bot.play('giggle');}
const RULES=[[/\b(come here|come to me|over here|follow me)\b/,'come'],[/\b(file|files|dive|explore|hide in)\b/,'dive'],[/\b(dance|party|groove|boogie)\b/,'dance'],[/\b(wave|hi|hello|hey|yo)\b/,'wave'],[/\b(jump|hop)\b/,'jump'],[/\b(spin|twirl)\b/,'spin'],[/\b(stretch|break|exercise)\b/,'stretch'],[/\b(joke|funny|laugh)\b/,'joke'],[/\b(time|clock)\b/,'time'],[/\b(wake|come back|awake)\b/,'wake'],[/\b(sleep|nap|rest|good ?night)\b/,'sleep'],[/\b(help|what can you do|commands)\b/,'help']];
const EMO_WORDS=[[/\b(happy|smile)\b/,'happy'],[/\b(sad|cry)\b/,'sad'],[/\b(angry|grumpy|mad)\b/,'angry'],[/\b(love|heart)\b/,'love'],[/\b(surprise|wow|shock)/,'surprised'],[/\b(dizzy|confused)\b/,'dizzy'],[/\b(curious|think|hmm)\b/,'curious'],[/\b(wink|playful)\b/,'wink']];
function setSize(sz){settings.size=sz;api.saveSettings({size:sz});base=SIZES[sz]/HF;}
function grow(d){const order=['S','M','L'],i=clamp(order.indexOf(settings.size)+d,0,2);if(order[i]===settings.size){say(d>0?"I'm as big as I get!":"I'm as small as I get!",1.6);return;}setSize(order[i]);say(d>0?'Look how big I am!':'Fun size!',1.8);}
async function showShortcuts(list){list=list||await api.shortcuts();bot.play('nod');
  const lines=list.map(s=>`${s.key.padEnd(20)} ${s.label}${s.ok?'':'  (taken by another app)'}`);say('Keyboard shortcuts\n'+lines.join('\n'),12,true);}
/* ---------- "catch the butterfly / bird" (critter.js; same chase as the Android app) ---------- */
function startChase(kind){begin();st.stayUntil=st.t+60;
  const top=Math.max(80,H-hPx()*4.5),bottom=H-hPx()*1.2;
  critter=new Critter(kind,W,H,top,Math.max(top+60,bottom),Math.max(34,base*1.1));
  bot.play('jump');bot.setEmotion('surprised',1.5);say(Critter.lines.start(critter.kind),2.4);}
function updateCritter(dt){if(!critter)return;
  const ev=critter.step(dt,st.x,st.y-hPx()*.6,Math.max(60,base*1.6));fxDirty=true;
  if(critter.state<=Critter.FLUTTER&&st.mode!=='drag'&&st.mode!=='fall'){st.target=null;st.mode='fly';
    st.fly={x:clamp(critter.x,base*1.3,W-base*1.3),y:clamp(critter.y+hPx()*.6,hPx()+10,H),then:null};}
  if(ev==='dodge'){bot.play('shrug');bot.setEmotion('surprised',1.6);say(Critter.lines.dodge(),1.8);}
  else if(ev==='caught'){st.fly=null;st.mode=st.y>=H-2?'ground':'hover';bot.play('jump');bot.setEmotion('love',2.6);say(Critter.lines.caught(critter.kind),2.4);}
  else if(ev==='free'){bot.play('wave');bot.setEmotion('happy',2.4);say(Critter.lines.free(),2.2);}
  else if(ev==='done'){critter=null;st.quietUntil=st.t+3;flyTo(st.x,H,()=>idle());}}
function drawCritter(){if(!fxDirty&&!critter)return;fxg.clearRect(0,0,fx.width,fx.height);fxDirty=!!critter;if(critter)critter.draw(fxg);}

function command(text){const s=text.toLowerCase().trim();if(!s)return;
  const cm=s.match(/\b(catch|chase|get|grab|go after)\b.*?\b(birds?|birdie|sparrow|parrot|butter ?fl(?:y|ies)|bug)\b/);
  if(cm)return startChase(/^(birds?|birdie|sparrow|parrot)$/.test(cm[2])?'bird':'butterfly');
  if(/\b(shortcut|shortcuts|hotkey|hotkeys|keys)\b/.test(s))return showShortcuts();
  const named=SKINS.find(sk=>sk.kw.some(k=>new RegExp('\\b'+k+'\\b').test(s)));
  if(/\b(wear|outfit|skin|dress|costume|clothes|change|be a|become|turn into|switch)\b/.test(s)){begin();named?wear(named.id):wear(otherSkin());later(1.4,idle);return;}
  if(named&&/\b(cat|kitty|bunny|rabbit|panda|bear)\b/.test(s)){begin();wear(named.id);later(1.4,idle);return;}
  if(/\b(bigger|grow|larger)\b/.test(s))return grow(1);
  if(/\b(smaller|shrink|tiny)\b/.test(s))return grow(-1);
  if(/\b(calm|quiet mode)\b/.test(s)){settings.mode='calm';api.saveSettings({mode:'calm'});scheduleAntic();say('Okay, I\'ll keep it calm.',2);return;}
  if(/\b(lively|party mode)\b/.test(s)){settings.mode='lively';api.saveSettings({mode:'lively'});scheduleAntic();say('Let\'s have some fun!',2);return;}
  if(/\b(quit|exit)\b/.test(s)){bot.setEmotion('sad',2);say('Bye for now!',1.5);setTimeout(()=>api.quit(),1400);return;}
  if(/\b(go away|bye|shh|hush|leave me|hide)\b/.test(s)&&!/\bhide in\b/.test(s)){interrupt();bot.setEmotion('sad',2);say('Okay, see you in an hour. Ctrl+Alt+Shift+B brings me back.',2.6);later(2,()=>api.snooze(3600e3));return;}
  for(const [re,a] of RULES)if(re.test(s))return perform(a);
  for(const [re,e] of EMO_WORDS)if(re.test(s)){begin();emote(e);say(`Feeling ${e==='angry'?'grumpy':e==='wink'?'playful':e}.`,1.8);later(2,idle);return;}
  perform('unknown');}

/* ---------- palette ---------- */
function openPalette(){if(st.mode==='infile')appearAt(st.x,st.y);paletteOpen=true;palette.hidden=false;input.value='';placePalette();bot.setEmotion('curious',30);bot.lookTarget={x:0,y:-.6};setTimeout(()=>input.focus(),30);}
function closePalette(){if(!paletteOpen)return;paletteOpen=false;palette.hidden=true;if(bot.emo==='curious')bot.setEmotion(bot.baseEmo,0.01);st.quietUntil=st.t+3.5;api.paletteClosed();}
function placePalette(){const w=300,top=st.y-hPx()-palette.offsetHeight-24;palette.style.transform=`translate(${clamp(st.x-w/2,8,W-w-8)}px,${clamp(top,8,H-120)}px)`;}
input.addEventListener('keydown',e=>{if(e.key==='Enter'){const v=input.value;closePalette();command(v);}else if(e.key==='Escape')closePalette();});
input.addEventListener('blur',()=>setTimeout(closePalette,120));
api.on('palette',()=>{if(st.snoozed)return;openPalette();});

/* ---------- IPC from main ---------- */
const unhide=()=>{if(st.mode==='infile')appearAt(st.x,st.y);else if(st.vanishT<1)restoreVisible();};
api.on('cursor',p=>{mouse.x=p.x;mouse.y=p.y;});
api.on('sense',s=>{sense.busy=s.busy;sense.away=s.away;});
api.on('command',id=>{unhide();if(id==='outfit'){begin();wear(otherSkin());later(1.4,idle);}else perform(id);});
api.on('wear',id=>{unhide();begin();wear(id);later(1.4,idle);});
api.on('size',d=>grow(d));
api.on('shortcuts',list=>{unhide();showShortcuts(list);});
api.on('settings',s=>{const prevMode=settings.mode,prevOut=settings.autoOutfit;settings=s;base=(SIZES[s.size]||96)/HF;if(s.mode!==prevMode)scheduleAntic();if(s.autoOutfit!==prevOut)scheduleOutfit();nightCheck();});
api.on('snooze',on=>{if(on){st.snoozed=true;interrupt();bot.play('hide');bubble.classList.remove('on');setTimeout(()=>{if(st.snoozed)canvas.style.opacity=0;},700);}
  else{st.snoozed=false;canvas.style.opacity=1;bot.stop();st.vanish=st.vanishT=1;st.mode='fall';st.state='FALL';st.y=-hPx();st.vx=0;st.vy=0;st.x=clamp(st.x,60,W-60);st.firstLanding=true;}});
api.on('desk-click',async p=>{
  if(!settings.followClicks||st.snoozed||paletteOpen||['DRAG','FALL'].includes(st.state))return;
  st.quietUntil=st.t+6;unhide();restoreVisible();const my=++seq;
  const icons=await api.icons();if(my!==seq)return;
  begin();if(st.state==='SLEEP'||bot.sleeping){bot.sleeping=false;}
  const hit=icons.find(i=>p.x>=i.x-4&&p.x<=i.x+i.w+4&&p.y>=i.y-4&&p.y<=i.y+i.h+4);
  if(hit){if(settings.roam&&Math.random()<.55)diveInto(hit,icons);else visitIcon(hit);return;}
  say(pick(['On my way!','Coming!','There? Okay!']),1.3);st.stayUntil=st.t+15;
  const near=p.y>H-hPx()*1.3;goTo(p.x,near?H:p.y+hPx()*.55,()=>{bot.play('giggle',.6);bot.setEmotion('happy',1.6);later(1,idle);});
});

/* ---------- pointer: poke, drag, throw ---------- */
function hitRect(){if(st.mode==='infile')return{l:-1,r:-1,t:-1,b:-1};const b=new THREE.Box3().setFromObject(bot.root);return{l:b.min.x-6,r:b.max.x+6,t:H-b.max.y-6,b:H-b.min.y+6};}
let rect={l:0,r:0,t:0,b:0};
const overBot=(x,y)=>x>=rect.l&&x<=rect.r&&y>=rect.t&&y<=rect.b;
canvas.addEventListener('pointerdown',e=>{if(e.button!==0||!overBot(e.clientX,e.clientY))return;canvas.setPointerCapture(e.pointerId);
  pd={x:e.clientX,y:e.clientY,offX:st.x-e.clientX,offY:st.y-e.clientY,drag:false};dragSamples=[];st.quietUntil=st.t+3;});
canvas.addEventListener('pointermove',e=>{mouse.x=e.clientX;mouse.y=e.clientY;if(!pd)return;
  if(!pd.drag&&Math.hypot(e.clientX-pd.x,e.clientY-pd.y)>5){pd.drag=true;interrupt();if(bot.sleeping)bot.sleeping=false;setHushLook(false);st.state='DRAG';st.mode='drag';bot.play('carried');bot.setEmotion('surprised',0);say(pick(['Whoa, where are we going?','Wheee!','Hold me gently!']),1.6);}
  if(pd.drag){st.x=e.clientX+pd.offX;st.y=e.clientY+pd.offY;dragSamples.push({x:st.x,y:st.y,t:performance.now()});if(dragSamples.length>8)dragSamples.shift();}});
canvas.addEventListener('pointerup',e=>{if(!pd)return;const wasDrag=pd.drag;pd=null;st.quietUntil=st.t+3;
  if(wasDrag){const a=dragSamples[0],b=dragSamples[dragSamples.length-1];let vx=0,vy=0;if(a&&b&&b.t>a.t){const dtt=(b.t-a.t)/1000;vx=(b.x-a.x)/dtt;vy=(b.y-a.y)/dtt;}
    st.vx=clamp(vx,-2600,2600);st.vy=clamp(vy,-2600,2600);
    if(Math.hypot(vx,vy)<250&&settings.roam&&st.y<H-hPx()){st.mode='hover';bot.stop();bot.setEmotion('happy',1.5);say('I\'ll float here for a bit.',1.8);st.stayUntil=st.t+20;idle();return;}
    st.mode='fall';st.state='FALL';bot.stop();bot.setEmotion('surprised',3);return;}
  const now=performance.now();if(now-lastClick<320){lastClick=0;begin();bot.play('jump');bot.setEmotion('happy',1.5);later(1.2,idle);return;}lastClick=now;poke();});
canvas.addEventListener('contextmenu',e=>{e.preventDefault();if(overBot(e.clientX,e.clientY))api.showMenu();});
function poke(){pokes.push(st.t);pokes=pokes.filter(t=>st.t-t<10);
  if(st.state==='SLEEP'){wake('Oh! I was just resting my eyes.');return;}
  if(pokes.length>=5){pokes=[];begin();bot.play('shrug');bot.setEmotion('angry',3);say('Hey! Personal space, please.',2.4);later(2,idle);return;}
  if(bot.action==='carried'||bot.action==='change')return;
  bot.play('giggle');bot.setEmotion(pick(['happy','happy','love']),1.6);say(pick(['Hehe, that tickles!','Boop received.','Again? Again!','You found me!']),1.6);}
function onLand(impact){bot.stop();
  if(st.firstLanding){st.firstLanding=false;bot.play('jump',.5);later(.5,()=>{bot.play('wave');bot.setEmotion('happy',2.6);});
    const daily=settings.lastDay!==today();
    later(.6,()=>say(daily?"Hi! I'm Blink. Ctrl+Alt+B to talk, Ctrl+Alt+/ for shortcuts. Click the desktop and I'll come over!":"I'm back!",5));
    if(daily){settings.lastDay=today();api.saveSettings({lastDay:settings.lastDay});later(6,()=>{const h=new Date().getHours();if(!(settings.nightCap&&(h>=21||h<6))){wear(seasonalOrRandom(),{announce:false});later(.8,()=>say('New day, new outfit!',2.4));}});}
    later(1,nightCheck);
    later(3,idle);st.state='PERFORM';return;}
  if(impact>1500){bot.play('giggle',.6);bot.setEmotion('dizzy',2.2);say(pick(['Whoa! Again! …or not.','Ouch, my circuits!','Stuck the landing. Mostly.']),2);}
  else if(impact>300){bot.play('giggle',.6);bot.setEmotion('happy',1.5);}
  st.state='PERFORM';later(1.2,idle);}

/* ---------- main loop ---------- */
let last=performance.now(),acc=0,hidden=false;
function frame(now){requestAnimationFrame(frame);
  const raw=Math.min(.1,(now-last)/1000);last=now;acc+=raw;
  const minDt=st.state==='HUSH'?1/12:1/30;if(acc<minDt)return;const dt=Math.min(.1,acc);acc=0;
  tick(dt);}
async function reprobe(){const my=seq;const ok=await api.probe([{x:st.x,y:st.y-hPx()*.5},{x:st.x,y:st.y-hPx()*.95}]);
  if(my!==seq||st.mode!=='hover'||st.state!=='IDLE'||interactive)return;
  if(!ok[0]||!ok[1]){say(pick(['Oops, you need this spot. Heading down.','Making room for you!']),2);st.state='WANDER';flyTo(st.x,H,()=>idle());}}
function tick(dt){
  st.t+=dt;
  if(timers.length){const due=timers.filter(t=>t.at<=st.t);timers=timers.filter(t=>t.at>st.t);for(const t of due)if(t.my===seq)t.fn();}
  if(st.snoozed){if(!hidden){bot.update(dt);render();if(canvas.style.opacity==='0')hidden=true;}setInteractive(false);return;}
  hidden=false;
  // safety nets: never stay invisible or lost
  if(!isFinite(st.x)||!isFinite(st.y)){st.x=W*.7;st.y=H;st.mode='ground';st.fly=null;st.target=null;}
  if(st.vanishT<1||st.mode==='infile'){if(!st.hiddenSince)st.hiddenSince=st.t;if(st.t-st.hiddenSince>12){interrupt();appearAt(st.x,st.y);bot.setEmotion('happy',2);say("Ta-da! I'm back.",2);idle();st.hiddenSince=0;}}
  else st.hiddenSince=0;
  const busy=sense.busy&&st.t>st.quietUntil&&!paletteOpen;
  if(!['DRAG','FALL','INFILE'].includes(st.state)){
    if(sense.away&&st.state!=='SLEEP')enterSleep(false);
    else if(!sense.away&&st.state==='SLEEP'&&!st.sleepByCmd)wake('Welcome back!');
    else if(busy&&['IDLE','WANDER','ANTIC'].includes(st.state))enterHush();
    else if(st.state==='HUSH'&&!busy)exitHush();
  }
  if(st.state==='IDLE'&&!paletteOpen&&!REDUCE){
    if(st.t>st.nextOutfit){st.state='ANTIC';wear(otherSkin());later(2.4,endAntic);scheduleOutfit();}
    else if(st.t>st.nextAntic)doAntic();
    else if(st.t>st.nextWander)wander();
    else if(st.mode==='hover'&&st.t>st.nextProbe&&st.t>st.stayUntil){st.nextProbe=st.t+2.5;reprobe();}
  }
  // look
  const cy=st.y-hPx()*.6,d=Math.hypot(mouse.x-st.x,mouse.y-cy);
  if(!paletteOpen){if(d<450&&st.state!=='SLEEP'){bot.lookTarget={x:clamp((mouse.x-st.x)/220,-1,1),y:clamp((mouse.y-cy)/220,-1,1)};st.lookAt=st.t+1.5;}
    else if(st.t>st.lookAt){st.lookAt=st.t+rand(1.5,3.5);bot.lookTarget={x:rand(-.8,.8),y:rand(-.4,.5)};}}
  updateCritter(dt);
  // movement
  let flyPose=0,tiltT=0;
  if(st.mode==='fall'){st.vy+=2600*dt;st.x+=st.vx*dt;st.y+=st.vy*dt;st.vx*=1-dt*.5;
    const half=base*1.1;if(st.x<half){st.x=half;st.vx=Math.abs(st.vx)*.45;}if(st.x>W-half){st.x=W-half;st.vx=-Math.abs(st.vx)*.45;}
    if(st.y-hPx()<0&&st.vy<0&&!st.firstLanding){st.y=hPx();st.vy=-st.vy*.35;}
    st.face=clamp(st.vx/800,-.8,.8);
    if(st.y>=H){st.y=H;const impact=st.vy;st.vy=0;st.vx=0;st.mode='ground';onLand(impact);}}
  else if(st.mode==='fly'&&st.fly){const f=st.fly,dx=f.x-st.x,dy=f.y-st.y,dist=Math.hypot(dx,dy);
    const speed=Math.min(380,90+dist*1.6)*(st.state==='HUSH'?.7:1),step=speed*dt;flyPose=1;
    if(dist<=step+.5){st.x=f.x;st.y=f.y;st.fly=null;if(st.y>=H-2){st.y=H;st.mode='ground';bot.play('giggle',.5);}else st.mode='hover';f.then&&f.then();}
    else{st.x+=dx/dist*step;st.y+=dy/dist*step;st.face=clamp(dx/dist,-1,1)*.85;tiltT=clamp(dx/dist,-1,1)*-.28;
      st.puffAcc+=dt;while(st.puffAcc>.05){st.puffAcc-=.05;puff(st.x+rand(-base*.2,base*.2),st.y+rand(0,4),pick([bot.skin.accent,'#FFFFFF',bot.skin.inner]),rand(-20,20)-dx/dist*60,-rand(60,130),rand(2,3.6)*base/35,rand(.35,.6));}}}
  else if(st.mode==='hover'){flyPose=.6;st.face=0;st.puffAcc+=dt;while(st.puffAcc>.16){st.puffAcc-=.16;puff(st.x+rand(-base*.15,base*.15),st.y+2,pick(['#FFFFFF',bot.skin.accent]),rand(-15,15),-rand(40,90),rand(1.6,2.6)*base/35,rand(.3,.5));}}
  else if(st.mode==='ground'){
    if(st.target){const dx=st.target.x-st.x,speed=(st.state==='HUSH'?.6:1)*(SIZES[settings.size]||96)*.95,step=speed*dt;
      if(Math.abs(dx)<=step){st.x=st.target.x;const th=st.target.then;st.target=null;bot.walk=0;st.face=0;th&&th();}
      else{st.x+=Math.sign(dx)*step;bot.walk=1;st.face=Math.sign(dx)*.95;}}
    else{bot.walk=0;st.face=0;}
    st.y=H;}
  if(st.mode!=='ground')bot.walk=0;
  if(st.mode==='infile')flyPose=0;
  bot.fly=lerp(bot.fly,flyPose,Math.min(1,dt*6));
  st.tilt=lerp(st.tilt,tiltT,Math.min(1,dt*6));
  st.scale=lerp(st.scale,st.scaleT,Math.min(1,dt*5));st.op=lerp(st.op,st.opT,Math.min(1,dt*4));canvas.style.opacity=st.op.toFixed(3);
  st.vanish=lerp(st.vanish,st.vanishT,Math.min(1,dt*(st.vanishT>st.vanish?7:9)));
  const hoverBob=st.mode==='hover'?Math.sin(st.t*2.2)*4:0;
  bot.group.rotation.y=lerp(bot.group.rotation.y,st.face,Math.min(1,dt*8));bot.group.rotation.z=st.tilt;
  bot.group.scale.setScalar(Math.max(.001,base*st.scale*st.vanish));bot.group.position.set(st.x,H-st.y+hoverBob,0);
  bot.group.visible=st.mode!=='infile'||st.vanish>.02;
  bot.update(dt);
  updatePuffs(dt);
  render();
  drawCritter();
  rect=hitRect();
  setInteractive(paletteOpen||!!pd||overBot(mouse.x,mouse.y));
  if(paletteOpen)placePalette();
  if(bubble.classList.contains('on')){if(st.t>st.bubbleUntil)bubble.classList.remove('on');
    const bw=bubble.offsetWidth,bh=bubble.offsetHeight,top=rect.t>0?rect.t:st.y-hPx();
    const by=top-bh-12<8?Math.min(rect.b+12,H-bh-8):top-bh-12;
    bubble.style.transform=`translate(${clamp(st.x-26,8,W-bw-8)}px,${clamp(by,8,H-bh-8)}px)`;}
}
function render(){renderer.render(scene,cam);}
function setInteractive(on){if(on!==interactive){interactive=on;api.setInteractive(on);}}
requestAnimationFrame(frame);
})();
