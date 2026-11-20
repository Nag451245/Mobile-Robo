// Blink character: skins, procedural face, 3D rig and animations. Shared globals for app.js.
"use strict";
const clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
const lerp=(a,b,t)=>a+(b-a)*t;
const smooth=t=>{t=clamp(t,0,1);return t*t*(3-2*t);};
const easeInOut=t=>t<.5?2*t*t:1-Math.pow(-2*t+2,2)/2;
const backOut=x=>{const c1=1.70158,c3=c1+1;return 1+c3*Math.pow(x-1,3)+c1*Math.pow(x-1,2);};
const REDUCE=matchMedia('(prefers-reduced-motion: reduce)').matches;
const rand=(a,b)=>a+Math.random()*(b-a);
const pick=a=>a[Math.floor(Math.random()*a.length)];

const SKINS=[
  {id:'bubblegum',name:'Bubblegum',shell:'#FFB3D4',belly:'#FFF0F7',trim:'#56C9BA',accent:'#FFD447',inner:'#FF86B8',bow:'#FF5FA2',eye:'#7DF9FF',bg:['#3A1652','#170829'],blush:'#FF7AB6',mouth:'smile',acc:['antenna','discs','bow'],kw:['pink','bubblegum','bubble','robot']},
  {id:'sunny',name:'Sunny',shell:'#FFD84A',belly:'#FFF6CF',trim:'#3FA2F7',accent:'#FF6F59',inner:'#FF9F80',eye:'#9BFFF0',bg:['#0E2B4D','#061629'],blush:'#FF8A65',mouth:'smile',acc:['antenna','discs'],kw:['sunny','yellow','sun']},
  {id:'kitty',name:'Mint Kitty',shell:'#8FE3C8',belly:'#F0FFF8',trim:'#2E8C7C',accent:'#FF9EC7',inner:'#FFB0D0',eye:'#FFF38A',bg:['#0F3838','#051E1E'],blush:'#FF8FC0',mouth:'cat',acc:['catEars','tail'],kw:['cat','kitty','kitten','mint']},
  {id:'bunny',name:'Lavender Bunny',shell:'#C9B5FF',belly:'#FFFFFF',trim:'#7B5CE0',accent:'#FFB3D9',inner:'#FFC2E0',eye:'#FFD6F5',bg:['#24154A','#0E0826'],blush:'#FF9ACB',mouth:'cat',acc:['bunnyEars'],kw:['bunny','rabbit','lavender','purple']},
  {id:'panda',name:'Panda',shell:'#FAFAFA',belly:'#FFFFFF',trim:'#2A2A33',limb:'#2A2A33',accent:'#7ED957',inner:'#4A4A55',leaf:'#7ED957',eye:'#B8F3FF',bg:['#1A1D24','#0A0B0F'],blush:'#FF9EB5',mouth:'smile',acc:['bearEars','leaf'],kw:['panda','bear']},
  {id:'sprout',name:'Sprout',shell:'#FFEFD0',belly:'#FFFFFF',trim:'#3E9B6B',accent:'#7BD88F',inner:'#FFB38A',leaf:'#6CD47E',eye:'#B9FF9C',bg:['#123222','#06180F'],blush:'#FF9F8A',mouth:'smile',acc:['leaf','discs'],kw:['sprout','plant','leaf','green']},
  {id:'classic',name:'Classic',shell:'#F2F4F7',belly:'#FFFFFF',trim:'#2A3544',accent:'#FFB020',inner:'#FFB020',eye:'#6FF3FF',bg:['#0F1D2F','#050B14'],blush:'#FF7AB0',mouth:'smile',acc:['antenna','discs'],kw:['classic','white','original']},
  {id:'snow',name:'Snow Buddy',shell:'#DDF1FF',belly:'#FFFFFF',trim:'#5C8FD6',accent:'#FF5A6E',inner:'#FF5A6E',hat:'#FF4D64',eye:'#A8F0FF',bg:['#102A4A','#061328'],blush:'#FF8FA8',mouth:'smile',acc:['beanie'],kw:['snow','winter','christmas','holiday','beanie','scarf']},
  {id:'nightcap',name:'Sleepy Cap',shell:'#A9B8FF',belly:'#E8ECFF',trim:'#3D4B9E',accent:'#FFE27A',inner:'#FFE27A',hat:'#4B5BD6',eye:'#FFE9A8',bg:['#121640','#070820'],blush:'#FF9AC2',mouth:'cat',acc:['nightcap'],kw:['night','nightcap','pajama','pyjama','cap']}
];
const SKIN_BY={};SKINS.forEach(s=>SKIN_BY[s.id]=s);

function rrect(ctx,x,y,w,h,r){r=Math.min(r,w/2,h/2);ctx.beginPath();ctx.moveTo(x+r,y);ctx.arcTo(x+w,y,x+w,y+h,r);ctx.arcTo(x+w,y+h,x,y+h,r);ctx.arcTo(x,y+h,x,y,r);ctx.arcTo(x,y,x+w,y,r);ctx.closePath();}
function lidClip(ctx,cx,cy,r,side,yi,yo){const xi=cx-side*r,xo=cx+side*r,s=(yo-yi)/(xo-xi),xl=cx-2.2*r,xr=cx+2.2*r;ctx.beginPath();ctx.moveTo(xl,yi+s*(xl-xi));ctx.lineTo(xr,yi+s*(xr-xi));ctx.lineTo(xr,cy+3*r);ctx.lineTo(xl,cy+3*r);ctx.closePath();ctx.clip();}
function heart(ctx,cx,cy,s){ctx.beginPath();ctx.moveTo(cx,cy+.7*s);ctx.bezierCurveTo(cx-1.15*s,cy,cx-.6*s,cy-.95*s,cx,cy-.35*s);ctx.bezierCurveTo(cx+.6*s,cy-.95*s,cx+1.15*s,cy,cx,cy+.7*s);ctx.fill();}

function drawFace(ctx,W,H,emo,open,lx,ly,t,sk){
  sk=sk||SKINS[0];
  const g=ctx.createLinearGradient(0,0,0,H);g.addColorStop(0,sk.bg[0]);g.addColorStop(1,sk.bg[1]);ctx.fillStyle=g;ctx.fillRect(0,0,W,H);
  ctx.fillStyle='rgba(255,255,255,0.035)';for(let y=0;y<H;y+=Math.max(3,H/42))ctx.fillRect(0,y,W,Math.max(1,H/128));
  const col=emo==='angry'?'#FF7A5C':emo==='love'?'#FF6FAE':sk.eye;
  const r=H*.25,ox=lx*r*.45,oy=ly*r*.32,ey=H*.43;
  const strong=['happy','love','wink','laugh'].includes(emo);
  ctx.save();ctx.globalAlpha=strong?.85:.45;ctx.fillStyle=sk.blush;ctx.shadowColor=sk.blush;ctx.shadowBlur=H*.06;
  for(const s of [-1,1]){ctx.beginPath();ctx.ellipse(W*.5+s*W*.31+ox*.5,H*.7+oy*.4,W*.06,H*.055,0,0,7);ctx.fill();}ctx.restore();
  ctx.save();ctx.shadowColor=col;ctx.shadowBlur=H*.1;ctx.fillStyle=col;ctx.strokeStyle=col;ctx.lineCap='round';ctx.lineJoin='round';
  for(const side of [-1,1]){
    const cx=W*.5+side*W*.165+ox,cy=ey+oy;
    ctx.save();
    let e=emo;if(e==='wink')e=side<0?'happy':'neutral';
    const blob=(w,h)=>{h=Math.max(h,r*.12);rrect(ctx,cx-w/2,cy-h/2,w,h,Math.min(w,h)/2);ctx.fill();};
    const shine=(w,h)=>{if(h<r*.9)return;ctx.save();ctx.shadowBlur=0;ctx.fillStyle='rgba(255,255,255,.95)';ctx.beginPath();ctx.arc(cx-w*.2,cy-h*.22,r*.2,0,7);ctx.fill();ctx.beginPath();ctx.arc(cx+w*.17,cy+h*.2,r*.09,0,7);ctx.fill();ctx.restore();};
    switch(e){
      case 'happy':case 'laugh':ctx.lineWidth=r*.34;ctx.beginPath();ctx.arc(cx,cy+r*.35,r*.6,Math.PI*1.12,Math.PI*1.88);ctx.stroke();break;
      case 'sleep':ctx.lineWidth=r*.24;ctx.beginPath();ctx.arc(cx,cy-r*.3,r*.58,Math.PI*.18,Math.PI*.82);ctx.stroke();break;
      case 'sleepy':blob(r*1.5,r*2*open*.3);break;
      case 'sad':{lidClip(ctx,cx,cy,r,side,cy-r*.95,cy-r*.15);const w=r*1.5,h=r*1.9*open;blob(w,h);shine(w,h*1.3);break;}
      case 'angry':{lidClip(ctx,cx,cy,r,side,cy-r*.05,cy-r*.9);blob(r*1.55,r*1.8*open);break;}
      case 'surprised':{const k=1.1+Math.sin(t*9)*.04;ctx.lineWidth=r*.26;ctx.beginPath();ctx.arc(cx,cy,r*k*open+.01,0,7);ctx.stroke();ctx.beginPath();ctx.arc(cx,cy,r*.34,0,7);ctx.fill();break;}
      case 'love':heart(ctx,cx,cy,r*1.15*(1+.08*Math.sin(t*8)));break;
      case 'dizzy':{ctx.lineWidth=r*.16;ctx.beginPath();const rot=t*6*side;for(let i=0;i<=64;i++){const a=i/64*2.6*Math.PI*2,rr=i/64*r*.95,x=cx+Math.cos(a+rot)*rr,y=cy+Math.sin(a+rot)*rr;i?ctx.lineTo(x,y):ctx.moveTo(x,y);}ctx.stroke();break;}
      case 'curious':{const s=side>0?1.2:.86,w=r*1.5*s,h=r*2*open*s;blob(w,h);shine(w,h);break;}
      default:{const w=r*1.5,h=r*2*open;blob(w,h);shine(w,h);}
    }
    ctx.restore();
  }
  const mx=W*.5+ox*.6,my=H*.76+oy*.4,m=H*.055;
  ctx.lineWidth=H*.028;ctx.shadowBlur=H*.04;ctx.beginPath();
  const catW=()=>{ctx.beginPath();ctx.arc(mx-m*.48,my-m*.25,m*.48,0,Math.PI);ctx.arc(mx+m*.48,my-m*.25,m*.48,0,Math.PI);ctx.stroke();};
  switch(emo){
    case 'happy':case 'laugh':case 'wink':{const k=emo==='laugh'?1.25+Math.abs(Math.sin(t*14))*.25:1.05;ctx.beginPath();ctx.moveTo(mx-m*k,my-m*.4);ctx.arc(mx,my-m*.4,m*k,0,Math.PI);ctx.closePath();ctx.fill();ctx.shadowBlur=0;ctx.fillStyle='#FF8FB1';ctx.beginPath();ctx.ellipse(mx,my+m*.32*k,m*.5*k,m*.24*k,0,0,7);ctx.fill();break;}
    case 'sad':ctx.arc(mx,my+m*.85,m*.85,Math.PI*1.2,Math.PI*1.8);ctx.stroke();break;
    case 'surprised':ctx.ellipse(mx,my,m*.5,m*.65,0,0,7);ctx.stroke();break;
    case 'angry':ctx.moveTo(mx-m,my);ctx.lineTo(mx-m*.5,my-m*.35);ctx.lineTo(mx,my);ctx.lineTo(mx+m*.5,my-m*.35);ctx.lineTo(mx+m,my);ctx.stroke();break;
    case 'dizzy':for(let i=0;i<=20;i++){const x=mx-m*1.2+i/20*m*2.4,y=my+Math.sin(i/20*Math.PI*4+t*6)*m*.25;i?ctx.lineTo(x,y):ctx.moveTo(x,y);}ctx.stroke();break;
    case 'sleep':ctx.arc(mx,my,m*.28*(1+.2*Math.sin(t*1.5)),0,7);ctx.fill();break;
    case 'sleepy':ctx.moveTo(mx-m*.4,my);ctx.lineTo(mx+m*.4,my);ctx.stroke();break;
    case 'love':catW();break;
    default:if(sk.mouth==='cat')catW();else{ctx.arc(mx,my-m*.65,m*.85,Math.PI*.2,Math.PI*.8);ctx.stroke();}
  }
  ctx.restore();
  ctx.fillStyle=col;ctx.textAlign='center';ctx.textBaseline='middle';
  if(emo==='sad'){const p=(t*.7)%1;ctx.globalAlpha=1-p;ctx.fillStyle='#8FDCFF';ctx.beginPath();ctx.arc(W*.335+ox+W*.04,H*.62+p*H*.3,H*.035,0,7);ctx.fill();ctx.globalAlpha=1;}
  if(emo==='sleep'){ctx.font=`700 ${H*.15}px "Segoe UI",sans-serif`;for(let i=0;i<3;i++){const p=(t*.35+i/3)%1;ctx.globalAlpha=Math.sin(p*Math.PI);ctx.fillText('z',W*.82+p*W*.08,H*.55-p*H*.42);}ctx.globalAlpha=1;}
  if(emo==='curious'){ctx.font=`700 ${H*.22}px "Segoe UI",sans-serif`;ctx.fillText('?',W*.9,H*.28+Math.sin(t*3)*H*.03);}
  if(emo==='surprised'){ctx.font=`800 ${H*.22}px "Segoe UI",sans-serif`;ctx.fillText('!',W*.9,H*.26);}
  if(emo==='love'){ctx.fillStyle='#FF8FC0';for(let i=0;i<2;i++){const p=(t*.4+i/2)%1;ctx.globalAlpha=Math.sin(p*Math.PI);heart(ctx,W*(.12+i*.76),H*.5-p*H*.35,H*.05);}ctx.globalAlpha=1;}
  if(emo==='angry'){ctx.save();ctx.strokeStyle=col;ctx.lineWidth=H*.025;ctx.translate(W*.88,H*.2);for(let i=0;i<4;i++){ctx.rotate(Math.PI/2);ctx.beginPath();ctx.moveTo(H*.02,H*.02);ctx.quadraticCurveTo(H*.07,H*.02,H*.07,H*.07);ctx.stroke();}ctx.restore();}
}

let PG=null;
class Bot{
  constructor(opts={}){
    if(!PG)PG=[new THREE.BoxGeometry(.1,.1,.1),new THREE.OctahedronGeometry(.08),new THREE.SphereGeometry(.06,8,6)];
    const g=new THREE.Group(),root=new THREE.Group();g.add(root);
    const std=(r=.42)=>{const m=new THREE.MeshStandardMaterial({color:'#ffffff',roughness:r,metalness:.03});m.userData.shared=true;return m;};
    const M=this.M={shell:std(.38),belly:std(.5),trim:std(.45),limb:std(.38),inner:std(.5),glow:std(.3),heart:new THREE.MeshBasicMaterial({color:'#ff7ab0'})};
    M.glow.emissiveIntensity=.45;M.heart.userData.shared=true;
    const sph=(r,w=32,h=20)=>new THREE.SphereGeometry(r,w,h);
    this.feet=[];
    for(const s of [-1,1]){const f=new THREE.Mesh(sph(.2,24,14),M.trim);f.scale.set(1,.6,1.3);f.position.set(s*.26,.11,.06);root.add(f);this.feet.push(f);}
    const body=new THREE.Group();body.position.y=.72;root.add(body);
    const torso=new THREE.Mesh(sph(.55,40,28),M.shell);torso.scale.set(1,.92,.9);body.add(torso);
    const belly=new THREE.Mesh(sph(.36,32,20),M.belly);belly.scale.set(1,1.05,.35);belly.position.set(0,-.04,.4);body.add(belly);
    const hs=new THREE.Shape();hs.moveTo(0,-.9);hs.bezierCurveTo(-1.4,0,-.7,1.1,0,.45);hs.bezierCurveTo(.7,1.1,1.4,0,0,-.9);
    const heartM=new THREE.Mesh(new THREE.ShapeGeometry(hs,16),M.heart);heartM.position.set(0,-.02,.535);heartM.scale.setScalar(.12);body.add(heartM);
    const headPivot=new THREE.Group();headPivot.position.y=.5;body.add(headPivot);
    const head=new THREE.Mesh(sph(.82,48,32),M.shell);head.scale.set(1.2,.95,1);head.position.y=.62;headPivot.add(head);
    const bezel=new THREE.Mesh(new THREE.SphereGeometry(.828,48,24,Math.PI/2-1.0,2.0,Math.PI/2-.68,1.36),M.trim);head.add(bezel);
    this.fw=opts.faceW||512;this.fh=this.fw/2;
    const fc=document.createElement('canvas');fc.width=this.fw;fc.height=this.fh;
    this.fx=fc.getContext('2d');this.tex=new THREE.CanvasTexture(fc);this.tex.anisotropy=4;
    const screen=new THREE.Mesh(new THREE.SphereGeometry(.836,48,24,Math.PI/2-.93,1.86,Math.PI/2-.6,1.2),new THREE.MeshBasicMaterial({map:this.tex}));head.add(screen);
    const mkArm=s=>{const p=new THREE.Group();p.position.set(s*.5,.06,0);body.add(p);
      p.add(new THREE.Mesh(sph(.11,20,14),M.limb));
      const a=new THREE.Mesh(new THREE.CylinderGeometry(.09,.08,.24,16),M.limb);a.position.y=-.14;p.add(a);
      const h=new THREE.Mesh(sph(.13,20,14),M.trim);h.position.y=-.3;p.add(h);return p;};
    this.armL=mkArm(-1);this.armR=mkArm(1);
    Object.assign(this,{group:g,root,body,headPivot,head,heartM});
    this.accParts=[];this.accAnims=[];this.parts=[];
    this.t=Math.random()*10;this.action=null;this.aT=0;this.aDur=0;this.emo='neutral';this.baseEmo='neutral';this.emoUntil=Infinity;
    this.blinkNext=this.t+2;this.blinkT=-1;this.look={x:0,y:0};this.lookTarget={x:0,y:0};this.walk=0;this.fly=0;this.sleeping=false;this.onActionEnd=null;this.pendingSkin=null;
    this.cur={rootY:0,rootRotY:0,sx:1,sy:1,bodyY:.72,headX:0,headY:0,headZ:0,armL:-.2,armR:.2,armLx:0,armRx:0};
    this.applySkin(typeof opts.skin==='string'?(SKIN_BY[opts.skin]||SKINS[0]):(opts.skin||SKINS[0]));
  }
  applySkin(sk){
    this.skin=sk;const M=this.M;
    M.shell.color.set(sk.shell);M.belly.color.set(sk.belly);M.trim.color.set(sk.trim);M.limb.color.set(sk.limb||sk.shell);M.inner.color.set(sk.inner);
    M.glow.color.set(sk.accent);M.glow.emissive.set(sk.accent);M.heart.color.set(sk.accent==='#7ED957'||sk.accent==='#7BD88F'?'#FF7AB0':sk.accent);
    for(const o of this.accParts){if(o.parent)o.parent.remove(o);o.traverse(x=>{if(x.geometry)x.geometry.dispose();if(x.material&&!x.material.userData.shared)x.material.dispose();});}
    this.accParts=[];this.accAnims=[];this.buildAcc(sk);
    if(this.onSkin)this.onSkin(sk);
  }
  buildAcc(sk){
    const M=this.M,hp=this.headPivot,HC=.62,A=this.accAnims;
    const add=(parent,o)=>{parent.add(o);this.accParts.push(o);return o;};
    const mat=c=>new THREE.MeshStandardMaterial({color:c,roughness:.45});
    const sph=(r,w=20,h=14)=>new THREE.SphereGeometry(r,w,h);
    for(const a of sk.acc){switch(a){
      case 'antenna':{const g=new THREE.Group();g.position.set(0,HC+.72,0);const st=new THREE.Mesh(new THREE.CylinderGeometry(.028,.034,.3,10),M.trim);st.position.y=.15;g.add(st);const tip=new THREE.Mesh(sph(.12),M.glow);tip.position.y=.34;g.add(tip);add(hp,g);A.push(t=>{g.rotation.z=Math.sin(t*4.5)*.14;g.rotation.x=Math.sin(t*3.1)*.06;});break;}
      case 'discs':{for(const s of [-1,1]){const d=new THREE.Mesh(new THREE.CylinderGeometry(.2,.2,.14,28),M.trim);d.rotation.z=Math.PI/2;d.position.set(s*.97,HC,0);add(hp,d);const d2=new THREE.Mesh(new THREE.CylinderGeometry(.12,.12,.16,24),M.glow);d2.rotation.z=Math.PI/2;d2.position.set(s*.99,HC,0);add(hp,d2);}break;}
      case 'bow':{const g=new THREE.Group();g.position.set(.6,HC+.6,.22);g.rotation.z=-.5;const m=mat(sk.bow||sk.accent);for(const s of [-1,1]){const c=new THREE.Mesh(new THREE.ConeGeometry(.15,.28,20),m);c.rotation.z=s*Math.PI/2;c.position.x=s*.13;g.add(c);}g.add(new THREE.Mesh(sph(.08,16,12),m));add(hp,g);A.push(t=>{g.scale.setScalar(1+Math.sin(t*3)*.04);});break;}
      case 'catEars':{for(const s of [-1,1]){const g=new THREE.Group();g.position.set(s*.5,HC+.58,0);g.rotation.z=-s*.38;const e=new THREE.Mesh(new THREE.ConeGeometry(.27,.46,24),M.shell);e.position.y=.2;g.add(e);const i=new THREE.Mesh(new THREE.ConeGeometry(.15,.3,20),M.inner);i.position.set(0,.16,.1);g.add(i);add(hp,g);A.push(t=>{const tw=Math.pow(Math.max(0,Math.sin(t*1.3+s*.7)),24);g.rotation.z=-s*(.38+tw*.3);});}break;}
      case 'tail':{const g=new THREE.Group();g.position.set(0,-.2,-.42);const curve=new THREE.CatmullRomCurve3([new THREE.Vector3(0,0,0),new THREE.Vector3(0,.05,-.3),new THREE.Vector3(.12,.35,-.45),new THREE.Vector3(.05,.66,-.35)]);g.add(new THREE.Mesh(new THREE.TubeGeometry(curve,24,.075,10,false),M.shell));const tip=new THREE.Mesh(sph(.1),M.inner);tip.position.set(.05,.66,-.35);g.add(tip);add(this.body,g);A.push(t=>{g.rotation.y=Math.sin(t*3)*.45;g.rotation.z=Math.sin(t*3)*.12;});break;}
      case 'bunnyEars':{for(const s of [-1,1]){const g=new THREE.Group();g.position.set(s*.3,HC+.64,0);const e=new THREE.Mesh(sph(.5,24,18),M.shell);e.scale.set(.3,1,.2);e.position.y=.46;g.add(e);const i=new THREE.Mesh(sph(.5,20,14),M.inner);i.scale.set(.17,.78,.1);i.position.set(0,.46,.06);g.add(i);add(hp,g);const flop=s>0;A.push(t=>{g.rotation.z=-s*.12-(flop?.55+Math.sin(t*2)*.08:Math.sin(t*1.7)*.05);g.rotation.x=flop?-.12:0;});}break;}
      case 'bearEars':{for(const s of [-1,1]){const e=new THREE.Mesh(sph(.22,20,16),M.trim);e.scale.set(1,1,.6);e.position.set(s*.62,HC+.6,0);add(hp,e);const i=new THREE.Mesh(sph(.12,16,12),M.inner);i.scale.set(1,1,.4);i.position.set(s*.62,HC+.6,.1);add(hp,i);}break;}
      case 'leaf':{const g=new THREE.Group();g.position.set(0,HC+.72,0);const st=new THREE.Mesh(new THREE.CylinderGeometry(.025,.03,.26,8),mat('#3E9B4F'));st.position.y=.13;g.add(st);const lm=mat(sk.leaf||'#6CD47E');for(const s of [-1,1]){const l=new THREE.Mesh(sph(.2),lm);l.scale.set(1,.3,.55);l.position.set(s*.17,.3,0);l.rotation.z=s*.45;g.add(l);}add(hp,g);A.push(t=>{g.rotation.z=Math.sin(t*2.2)*.15;});break;}
      case 'beanie':{const g=new THREE.Group();g.position.y=HC;g.scale.set(1.2,.95,1);const hm=mat(sk.hat||'#FF5A6E'),wm=mat('#FFFFFF');g.add(new THREE.Mesh(new THREE.SphereGeometry(.86,36,14,0,Math.PI*2,0,.8),hm));const brim=new THREE.Mesh(new THREE.TorusGeometry(.61,.08,12,48),wm);brim.rotation.x=Math.PI/2;brim.position.y=.6;g.add(brim);const pom=new THREE.Mesh(sph(.17,18,14),wm);pom.position.y=.98;g.add(pom);add(hp,g);
        const sc=new THREE.Mesh(new THREE.TorusGeometry(.35,.11,12,40),hm);sc.rotation.x=Math.PI/2;sc.position.y=.44;add(this.body,sc);const end=new THREE.Mesh(new THREE.BoxGeometry(.17,.42,.07),hm);end.position.set(.2,.2,.44);add(this.body,end);A.push(t=>{end.rotation.z=.18+Math.sin(t*2.4)*.1;pom.position.y=.98+Math.sin(t*4)*.02;});break;}
      case 'nightcap':{const g=new THREE.Group();g.position.set(-.05,HC+.55,0);const cm=mat(sk.hat||'#4B5BD6'),ym=mat('#FFE27A');const cone=new THREE.Mesh(new THREE.ConeGeometry(.56,1.15,32),cm);cone.position.y=.5;g.add(cone);const brim=new THREE.Mesh(new THREE.TorusGeometry(.58,.08,12,48),mat('#FFFFFF'));brim.rotation.x=Math.PI/2;brim.position.y=-.04;g.add(brim);const pom=new THREE.Mesh(sph(.13),ym);pom.position.y=1.1;g.add(pom);
        for(let i=0;i<5;i++){const st=new THREE.Mesh(new THREE.OctahedronGeometry(.05),ym);const a=i*1.3,yy=.15+i*.17,rr=.56*(1-(yy+.075)/1.15);st.position.set(Math.cos(a)*rr,yy,Math.sin(a)*rr);g.add(st);}
        add(hp,g);A.push(t=>{g.rotation.z=.55+Math.sin(t*1.6)*.07;});break;}
    }}
  }
  wear(sk,anim=true){if(typeof sk==='string')sk=SKIN_BY[sk];if(!sk)return;if(!anim||REDUCE){this.applySkin(sk);return;}this.pendingSkin=sk;this.poof();this.play('change');this.setEmotion('happy',2.2);}
  poof(){if(REDUCE)return;const sk=this.skin,cols=[sk.shell,sk.accent,sk.inner,'#FFFFFF',sk.trim,sk.blush];
    for(let i=0;i<22;i++){const m=new THREE.Mesh(PG[i%3],new THREE.MeshBasicMaterial({color:cols[i%cols.length]}));m.position.set(rand(-.3,.3),rand(.9,1.9),rand(-.1,.5));
      const v=new THREE.Vector3(rand(-1,1),rand(.2,1.3),rand(-.3,1)).normalize().multiplyScalar(rand(1.8,3.4));this.group.add(m);this.parts.push({m,v,life:0,max:rand(.8,1.2)});}}
  setEmotion(e,dur=2.5){this.emo=e;this.emoUntil=dur?this.t+dur:Infinity;}
  play(name,dur){const D={wave:2.4,dance:4.2,jump:1.1,spin:1.3,stretch:2.6,laugh:2.2,shrug:1.8,nod:1.4,hide:3.4,giggle:1.2,change:1.2,carried:9999};
    if(this.action==='change'&&this.pendingSkin){this.applySkin(this.pendingSkin);this.pendingSkin=null;this.cur.rootRotY=0;}
    this.action=name;this.aT=0;this.aDur=dur||D[name]||2;this._swapped=false;if(this.sleeping&&name!=='hide')this.sleeping=false;}
  stop(){if(this.action==='change'&&this.pendingSkin){this.applySkin(this.pendingSkin);this.pendingSkin=null;}if(this.action==='spin'||this.action==='change')this.cur.rootRotY=0;this.action=null;}
  update(dt){
    this.t+=dt;const t=this.t,c=this.cur;
    if(t>this.emoUntil){this.emo=this.sleeping?'sleep':this.baseEmo;this.emoUntil=Infinity;}
    if(this.sleeping&&this.emoUntil===Infinity)this.emo='sleep';
    const breathe=REDUCE?0:Math.sin(t*2.2);
    const T={rootY:0,rootRotY:0,sx:1,sy:1,bodyY:.72+breathe*.022,headX:-this.look.y*.2,headY:this.look.x*.42,headZ:REDUCE?0:Math.sin(t*.9)*.05,armL:-.2-breathe*.05,armR:.2+breathe*.05,armLx:0,armRx:0};
    if(this.walk>0){const w=this.walk,s=t*11;T.rootY+=Math.abs(Math.sin(s))*.08*w;T.armLx=Math.sin(s)*.7*w;T.armRx=-Math.sin(s)*.7*w;T.headZ+=Math.sin(s)*.06*w;this.feet[0].position.z=.06+Math.sin(s)*.14*w;this.feet[1].position.z=.06-Math.sin(s)*.14*w;}
    else{this.feet[0].position.z=this.feet[1].position.z=.06;}
    if(this.fly>.01){const f=this.fly;T.armL=lerp(T.armL,-1.3,f);T.armR=lerp(T.armR,1.3,f);T.armLx=lerp(T.armLx,.6,f);T.armRx=lerp(T.armRx,.6,f);T.rootY+=Math.sin(t*4)*.05*f;this.feet[0].position.z=this.feet[1].position.z=.06-.14*f;}
    if(this.sleeping){T.bodyY=.68+Math.sin(t*1.1)*.02;T.headX=.3;T.headY=0;T.headZ=.16;T.armL=-.05;T.armR=.05;}
    if(this.action){
      this.aT+=dt;const a=this.aT,p=Math.min(1,a/this.aDur);
      switch(this.action){
        case 'wave':{const up=smooth(a/.3)*smooth((this.aDur-a)/.3);T.armR=.2+up*(2.35+Math.sin(a*12)*.35);T.headZ+=up*.14;break;}
        case 'dance':{const s=a*8;T.rootY+=Math.abs(Math.sin(s))*.16;T.rootRotY=Math.sin(a*4)*.45;T.armL=-.4-(Math.sin(s)*.5+.5)*1.6;T.armR=.4+(Math.cos(s)*.5+.5)*1.6;T.headZ+=Math.sin(a*4)*.2;break;}
        case 'jump':{if(p<.18){T.sy=1-.18*Math.sin(p/.18*Math.PI);T.sx=1+(1-T.sy)*.6;}else if(p<.82){const q=(p-.18)/.64,k=Math.sin(q*Math.PI);T.rootY+=k*1.1;T.sy=1+.08*k;T.sx=1-.04*k;T.armL=-.2-2.2*k;T.armR=.2+2.2*k;}else{const q=(p-.82)/.18;T.sy=1-.14*Math.sin(q*Math.PI);T.sx=1+(1-T.sy)*.6;}break;}
        case 'spin':{T.rootRotY=easeInOut(p)*Math.PI*2;c.rootRotY=T.rootRotY;const k=Math.sin(p*Math.PI);T.rootY+=k*.3;T.armL=-.2-1.2*k;T.armR=.2+1.2*k;break;}
        case 'change':{T.rootRotY=easeInOut(p)*Math.PI*2;c.rootRotY=T.rootRotY;const k=p<.5?1-smooth(p/.5)*.8:.2+.8*backOut((p-.5)/.5);T.sx=T.sy=k;c.sx=c.sy=k;T.rootY+=Math.sin(p*Math.PI)*.45;T.armL=-1.4;T.armR=1.4;
          if(p>=.5&&!this._swapped){this._swapped=true;if(this.pendingSkin){this.applySkin(this.pendingSkin);this.pendingSkin=null;}this.poof();}break;}
        case 'stretch':{const up=smooth(a/.5)*smooth((this.aDur-a)/.5);T.armL=-.2-up*2.75;T.armR=.2+up*2.75;T.sy=1+up*.06;T.headX=-.25*up;break;}
        case 'laugh':{T.bodyY+=Math.abs(Math.sin(a*18))*.05;T.headX=-.15;T.headZ+=Math.sin(a*9)*.07;T.armL=-.5;T.armR=.5;break;}
        case 'shrug':{const k=Math.sin(p*Math.PI);T.armL=-.2-k*.9;T.armR=.2+k*.9;T.armLx=T.armRx=-k*.6;T.headZ+=k*.25;T.bodyY+=k*.04;break;}
        case 'giggle':{T.rootY+=Math.abs(Math.sin(a*14))*.12*(1-p);T.headZ+=Math.sin(a*20)*.08*(1-p);break;}
        case 'nod':{T.headX=Math.sin(p*Math.PI*4)*.25;break;}
        case 'hide':{const k=p<.2?smooth(p/.2):p>.8?smooth((1-p)/.2):1;T.sx=T.sy=1-k*.98;T.rootRotY=k*Math.PI;break;}
        case 'carried':{T.armL=-2.3+Math.sin(a*7)*.2;T.armR=2.3-Math.sin(a*7)*.2;T.headZ+=Math.sin(a*5)*.12;T.headX=.1;this.feet[0].position.z=.06+Math.sin(a*9)*.14;this.feet[1].position.z=.06-Math.sin(a*9)*.14;break;}
      }
      if(this.action&&this.aT>=this.aDur){const done=this.action;this.action=null;if(done==='spin'||done==='change'){c.rootRotY-=Math.PI*2;T.rootRotY=0;}if(done==='change'&&this.pendingSkin){this.applySkin(this.pendingSkin);this.pendingSkin=null;}if(this.onActionEnd)this.onActionEnd(done);}
    }
    const k=1-Math.exp(-dt*(this.action?30:9));
    for(const key in T)c[key]=lerp(c[key],T[key],k);
    this.root.position.y=c.rootY;this.root.rotation.y=c.rootRotY;this.root.scale.set(c.sx,c.sy,c.sx);
    this.body.position.y=c.bodyY;this.headPivot.rotation.set(c.headX,c.headY,c.headZ);
    this.armL.rotation.set(c.armLx,0,c.armL);this.armR.rotation.set(c.armRx,0,c.armR);
    if(!REDUCE)for(const f of this.accAnims)f(t);
    for(let i=this.parts.length-1;i>=0;i--){const q=this.parts[i];q.life+=dt;q.m.position.addScaledVector(q.v,dt);q.v.y-=4.5*dt;q.v.multiplyScalar(1-dt*1.6);q.m.rotation.x+=dt*7;q.m.rotation.y+=dt*5;
      q.m.scale.setScalar(Math.max(.001,1-q.life/q.max));if(q.life>=q.max){this.group.remove(q.m);q.m.material.dispose();this.parts.splice(i,1);}}
    let open=1;
    if(this.blinkT<0&&t>this.blinkNext)this.blinkT=0;
    if(this.blinkT>=0){this.blinkT+=dt;const b=this.blinkT/.16;open=Math.abs(1-2*Math.min(1,b));if(b>=1){this.blinkT=-1;this.blinkNext=t+rand(2,6);open=1;}}
    const kl=Math.min(1,dt*7);this.look.x+=(this.lookTarget.x-this.look.x)*kl;this.look.y+=(this.lookTarget.y-this.look.y)*kl;
    drawFace(this.fx,this.fw,this.fh,this.emo,open,this.look.x,this.look.y,t,this.skin);this.tex.needsUpdate=true;
    const pulse=this.emo==='sleep'||this.emo==='sleepy'?.1:Math.pow(Math.max(0,Math.sin(t*3)),6);
    this.heartM.scale.setScalar(.12*(1+.18*pulse));
    this.M.glow.emissiveIntensity=.3+.35*pulse;
  }
}

function addLights(scene,intensity=1){
  scene.add(new THREE.HemisphereLight(0xffffff,0x9a8fb8,.9*intensity));
  const d=new THREE.DirectionalLight(0xffffff,.8*intensity);d.position.set(3,5,4);scene.add(d);
  const rim=new THREE.DirectionalLight(0xffc4e6,.55*intensity);rim.position.set(-4,3,-3);scene.add(rim);
}
