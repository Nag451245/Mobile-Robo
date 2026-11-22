// "Catch the butterfly / bird": the same chase as the Android app (Critter.java), drawn on a 2D layer above Blink.
// It flutters in from a screen edge, dodges once ("Hey, come back!"), gets caught, sits in Blink's hands, then flies free.
(function(){
"use strict";
const ENTER=0,FLUTTER=1,CAUGHT=2,FREE=3,DONE=4;
class Critter{
  constructor(kind,w,h,top,bottom,size){
    this.kind=kind==='bird'?'bird':'butterfly';this.w=w;this.h=h;this.top=top;this.bottom=bottom;this.size=size;
    const fromLeft=Math.random()<.5;this.x=fromLeft?-size:w+size;this.y=top+Math.random()*(bottom-top)*.6;this.faceLeft=!fromLeft;
    this.state=ENTER;this.t=0;this.stateT=0;this.dodges=0;this.dodgedAt=-10;this.pick();
  }
  bird(){return this.kind==='bird';}
  speed(){let s=(this.bird()?.30:.20)*this.w;if(this.stateT>14&&this.state===FLUTTER)s*=.35;return s;}
  pick(){this.tx=this.w*(.15+Math.random()*.7);this.ty=this.top+Math.random()*(this.bottom-this.top);}
  // hx,hy: Blink's hands; returns 'dodge' | 'caught' | 'free' | 'done' | null
  step(dt,hx,hy,reach){
    this.t+=dt;this.stateT+=dt;
    if(this.state===ENTER||this.state===FLUTTER){
      const dx=this.tx-this.x,dy=this.ty-this.y,d=Math.hypot(dx,dy);
      if(d<this.size)this.pick();
      else{const sp=this.speed()*dt;this.x+=dx/d*sp;this.y+=dy/d*sp+Math.sin(this.t*(this.bird()?6:9))*this.size*(this.bird()?.6:1.4)*dt;if(Math.abs(dx)>this.size*.2)this.faceLeft=dx<0;}
      if(this.state===ENTER&&this.stateT>1.5){this.state=FLUTTER;this.stateT=0;}
      if(this.state===FLUTTER&&this.t-this.dodgedAt>1.5&&Math.hypot(this.x-hx,this.y-hy)<reach){
        if(this.dodges===0){this.dodges++;this.dodgedAt=this.t;this.tx=hx<this.w/2?this.w*(.7+Math.random()*.2):this.w*(.1+Math.random()*.2);this.ty=this.top+Math.random()*(this.bottom-this.top)*.5;return 'dodge';}
        this.state=CAUGHT;this.stateT=0;this.x=hx;this.y=hy;return 'caught';
      }
      return null;
    }
    if(this.state===CAUGHT){this.x=hx;this.y=hy;if(this.stateT>2.4){this.state=FREE;this.stateT=0;this.faceLeft=Math.random()<.5;this.tx=this.faceLeft?-this.size*3:this.w+this.size*3;this.ty=-this.size*3;return 'free';}return null;}
    if(this.state===FREE){
      const dx=this.tx-this.x,dy=this.ty-this.y,d=Math.hypot(dx,dy),sp=this.speed()*1.3*dt;
      if(d<=sp||this.y<-this.size*2||this.x<-this.size*2||this.x>this.w+this.size*2){this.state=DONE;return 'done';}
      this.x+=dx/d*sp;this.y+=dy/d*sp+Math.sin(this.t*9)*this.size*.8*dt;return null;
    }
    return null;
  }
  done(){return this.state===DONE;}
  draw(g){
    g.save();g.translate(this.x,this.y);if(this.faceLeft)g.scale(-1,1);
    const s=this.size,rest=this.state===CAUGHT;
    if(this.bird()){
      const flap=rest?0:Math.sin(this.t*16);
      g.fillStyle='#4FA8FF';g.beginPath();g.moveTo(-s*.3,0);g.lineTo(-s*.55,-s*.12);g.lineTo(-s*.55,s*.14);g.closePath();g.fill();
      g.fillStyle='#6EC1FF';g.beginPath();g.ellipse(-s*.05,0,s*.3,s*.21,0,0,7);g.fill();
      g.fillStyle='#E8F6FF';g.beginPath();g.ellipse(s*.02,s*.09,s*.17,s*.11,0,0,7);g.fill();
      g.fillStyle='#6EC1FF';g.beginPath();g.arc(s*.28,-s*.16,s*.16,0,7);g.fill();
      g.fillStyle='#FFB13D';g.beginPath();g.moveTo(s*.42,-s*.2);g.lineTo(s*.58,-s*.14);g.lineTo(s*.42,-s*.09);g.closePath();g.fill();
      g.fillStyle='#231B33';g.beginPath();g.arc(s*.32,-s*.2,s*.035,0,7);g.fill();
      g.fillStyle='#FF8FB1';g.beginPath();g.arc(s*.25,-s*.1,s*.035,0,7);g.fill();
      g.fillStyle='#3D8EE6';g.save();g.translate(-s*.05,-s*.05);g.rotate((-25-flap*45)*Math.PI/180);g.beginPath();g.ellipse(-s*.04,0,s*.21,s*.1,0,0,7);g.fill();g.restore();
    }else{
      const flap=rest?.35+.1*Math.sin(this.t*3):.25+.75*Math.abs(Math.cos(this.t*14));
      for(const side of [-1,1]){g.save();g.scale(side*flap,1);
        g.fillStyle='#FF7EB6';g.beginPath();g.ellipse(s*.25,-s*.21,s*.25,s*.27,0,0,7);g.fill();
        g.fillStyle='#B57BFF';g.beginPath();g.ellipse(s*.18,s*.18,s*.18,s*.2,0,0,7);g.fill();
        g.fillStyle='#FFFFFF';g.beginPath();g.arc(s*.3,-s*.22,s*.06,0,7);g.fill();g.beginPath();g.arc(s*.2,s*.18,s*.04,0,7);g.fill();g.restore();}
      g.fillStyle='#3A2C4F';g.beginPath();g.ellipse(0,0,s*.05,s*.3,0,0,7);g.fill();
      g.strokeStyle='#3A2C4F';g.lineWidth=Math.max(1.5,s*.03);g.lineCap='round';g.beginPath();
      g.moveTo(0,-s*.28);g.quadraticCurveTo(-s*.06,-s*.44,-s*.14,-s*.5);g.moveTo(0,-s*.28);g.quadraticCurveTo(s*.06,-s*.44,s*.14,-s*.5);g.stroke();
    }
    g.restore();
  }
}
const pick=a=>a[Math.floor(Math.random()*a.length)];
Critter.lines={
  start:k=>k==='bird'?pick(['A birdie! Come here, little bird!','Tweet tweet! I\'m gonna catch you!','Ooh, a bird! Wait for me!']):pick(['Ooh, a butterfly! Let me catch it!','Pretty butterfly! Come here!','Butterfly! Wait for me!']),
  dodge:()=>pick(['Hey, come back!','Whoa, so fast!','Almost had it!']),
  caught:k=>k==='bird'?pick(['Got you, little birdie! Hehe!','Caught you! Hello, birdie!']):pick(['Got you! Hehe, it tickles!','Caught it! So pretty!']),
  free:()=>pick(['Fly free, little friend!','Bye bye! Come visit again!','Off you go!'])
};
Critter.ENTER=ENTER;Critter.FLUTTER=FLUTTER;Critter.CAUGHT=CAUGHT;Critter.FREE=FREE;Critter.DONE=DONE;
window.Critter=Critter;
})();
