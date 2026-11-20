// Floating Blink: renders the real 3D Blink (same blink.js as desktop and web) inside a small transparent window.
// The Android side moves the window around the screen and calls window.B to animate.
(function(){
"use strict";
const canvas=document.getElementById('c');
const renderer=new THREE.WebGLRenderer({canvas,antialias:true,alpha:true});
renderer.setPixelRatio(Math.min(2,window.devicePixelRatio||1));renderer.setClearColor(0x000000,0);
const scene=new THREE.Scene();addLights(scene);
const cam=new THREE.OrthographicCamera(0,1,1,0,.1,2000);cam.position.z=600;
const skin=new URLSearchParams(location.search).get('skin')||'bubblegum';
const bot=new Bot({skin});scene.add(bot.group);
let W=1,H=1,u=10;
function size(){W=innerWidth;H=innerHeight;renderer.setSize(W,H,false);cam.left=0;cam.right=W;cam.top=H;cam.bottom=0;cam.updateProjectionMatrix();u=H/4.4;}
addEventListener('resize',size);size();
const st={walk:0,fly:0,face:0,tilt:0};
window.B={
  play:(n,d)=>bot.play(n,d),
  stop:()=>bot.stop(),
  emote:(e,d)=>bot.setEmotion(e,d==null?2.5:d),
  base:e=>{bot.baseEmo=e;},
  wear:id=>bot.wear(id),
  sleep:on=>{bot.sleeping=!!on;if(on)bot.emoUntil=Infinity;else bot.emoUntil=bot.t;},
  move:(walk,fly,face,tilt)=>{st.walk=walk;st.fly=fly;st.face=face;st.tilt=tilt;},
  look:(x,y)=>{bot.lookTarget={x,y};}
};
let last=performance.now(),acc=0,lookAt=0;
function frame(now){
  requestAnimationFrame(frame);
  const raw=Math.min(.1,(now-last)/1000);last=now;acc+=raw;
  if(acc<1/30)return;const dt=Math.min(.1,acc);acc=0;
  bot.walk=st.walk;
  bot.fly+=(st.fly-bot.fly)*Math.min(1,dt*6);
  bot.group.rotation.y+=(st.face-bot.group.rotation.y)*Math.min(1,dt*8);
  bot.group.rotation.z=st.tilt;
  bot.group.scale.setScalar(u);
  bot.group.position.set(W/2,4,0);
  if(bot.t>lookAt){lookAt=bot.t+1.5+Math.random()*2.5;bot.lookTarget={x:Math.random()*1.6-.8,y:Math.random()*.9-.4};}
  bot.update(dt);
  renderer.render(scene,cam);
}
requestAnimationFrame(frame);
if(window.Android&&window.Android.ready)window.Android.ready();
})();
