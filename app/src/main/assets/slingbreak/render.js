(() => {
  'use strict';
  const G=Game,canvas=document.getElementById('game'),ctx=canvas.getContext('2d');
  const brickInks={normal:'#75944f',bomb:'#9e4935',lightning:'#8f792e',frost:'#4f8d9e',prism:'#796099',gold:'#809142'};
  let hpLabels=new WeakMap();
  document.fonts?.addEventListener('loadingdone',()=>{hpLabels=new WeakMap();});
  const hpLabel=b=>{
    const hp=Math.ceil(b.hp),cached=hpLabels.get(b);
    if(cached&&cached.hp===hp&&cached.w===b.w&&cached.h===b.h&&cached.type===b.type)return cached;
    const text=G.fmt(hp),baseSize=Math.min(b.type==='normal'?23:22,b.h*.65);
    let size=baseSize;
    if(b.type!=='normal'){
      ctx.font=`500 ${baseSize}px "DM Sans", "Noto Sans SC", sans-serif`;
      size*=Math.min(1,(b.w/2-6)/Math.max(1,ctx.measureText(text).width));
    }
    const result={hp,w:b.w,h:b.h,type:b.type,text,size};hpLabels.set(b,result);return result;
  };
   let scale=1,offsetX=0,offsetY=0,cssW=780,cssH=760,prediction=null;
  const resize=()=>{
      // Keep the backing store sharp on high-density phones without making
      // the animation buffer unnecessarily expensive on extreme DPR screens.
      const r=canvas.getBoundingClientRect(),dpr=Math.min(devicePixelRatio||1,2);
     cssW=r.width;cssH=r.height;canvas.width=Math.round(cssW*dpr);canvas.height=Math.round(cssH*dpr);prediction=null;
     scale=Math.min(cssW/G.W,cssH/1100);G.H=cssH/scale;offsetX=(cssW-G.W*scale)/2;offsetY=0;G.origin.y=Math.min(G.H-180,970);
     G.drag=null;G.pointer=null;
    G.view={scale,offsetX,offsetY,width:cssW,height:cssH};
  };
  new ResizeObserver(resize).observe(canvas);resize();
  const line=(x,y,tx,ty,color,width=1)=>{ctx.strokeStyle=color;ctx.lineWidth=width;ctx.beginPath();ctx.moveTo(x,y);ctx.lineTo(tx,ty);ctx.stroke();};
  const rounded=(x,y,w,h,r,color)=>{ctx.fillStyle=color;ctx.beginPath();ctx.roundRect(x,y,w,h,r);ctx.fill();};
  const circle=(x,y,r,color)=>{ctx.fillStyle=color;ctx.beginPath();ctx.arc(x,y,r,0,Math.PI*2);ctx.fill();};
  const label=(text,x,y,size=12,color='#91a17d',font='DM Sans',weight=500)=>{ctx.font=`${weight} ${size}px "${font}", "Noto Sans SC", sans-serif`;ctx.fillStyle=color;ctx.textAlign='center';ctx.fillText(text,x,y);};
  function glyph(type,x,y,color){
    ctx.save();ctx.translate(x,y);ctx.strokeStyle=color;ctx.fillStyle=color;ctx.lineWidth=1.65;ctx.lineCap='round';ctx.lineJoin='round';
    if(type==='bomb'){for(let i=0;i<8;i++){const a=i*Math.PI/4;line(Math.cos(a)*3,Math.sin(a)*3,Math.cos(a)*8,Math.sin(a)*8,color,1.6);}circle(0,0,2,color);}
    if(type==='lightning'){ctx.beginPath();ctx.moveTo(1,-9);ctx.lineTo(-6,1);ctx.lineTo(0,1);ctx.lineTo(-2,9);ctx.lineTo(6,-2);ctx.lineTo(1,-2);ctx.closePath();ctx.fill();}
    if(type==='frost'){for(let i=0;i<6;i++){ctx.rotate(Math.PI/3);line(0,0,0,-8,color,1.5);line(0,-5,-3,-7,color,1.3);line(0,-5,3,-7,color,1.3);}}
    if(type==='prism'){line(0,8,0,0,color,1.6);line(0,0,-6,-6,color,1.6);line(0,0,6,-6,color,1.6);line(-6,-6,-6,-2,color,1.6);line(-6,-6,-2,-6,color,1.6);line(6,-6,6,-2,color,1.6);line(6,-6,2,-6,color,1.6);}
    if(type==='gold'){ctx.beginPath();ctx.arc(0,0,8,0,Math.PI*2);ctx.stroke();label('¥',0,4,11,color);}
    ctx.restore();
  }
  function arrow(x,y,angle,color='#343f2b',alpha=1){
    ctx.save();ctx.globalAlpha=alpha;ctx.translate(x,y);ctx.rotate(angle);ctx.lineCap='round';line(-29,0,4,0,color,2.3);
    ctx.fillStyle=color;ctx.beginPath();ctx.moveTo(10,0);ctx.lineTo(0,-4);ctx.lineTo(0,4);ctx.closePath();ctx.fill();
    line(-23,0,-29,-4,'#a3c279',2);line(-23,0,-29,4,'#a3c279',2);ctx.restore();
  }
  function drawCore(){
    const c=G.core;if(!c)return;
    const age=G.time-c.born,entrance=Math.min(1,age/1.1),pulse=Math.sin(G.time*3);
    ctx.save();ctx.translate(c.x,c.y);ctx.scale(entrance,entrance);
    ctx.strokeStyle='#a4cd72';ctx.lineWidth=1;ctx.globalAlpha=.5;
    for(let i=0;i<2;i++){ctx.save();ctx.rotate(G.time*(i?-.5:.4));ctx.setLineDash([12,8,3,8]);ctx.beginPath();ctx.arc(0,0,40+i*10+pulse*2,0,Math.PI*2);ctx.stroke();ctx.restore();}
    ctx.globalAlpha=1;ctx.rotate(Math.PI/4);ctx.shadowColor='#a2d865';ctx.shadowBlur=G.reduced?0:18+pulse*6;rounded(-21,-21,42,42,4,'#b6ed66');ctx.shadowBlur=0;ctx.strokeStyle='#608938';ctx.lineWidth=1.5;ctx.strokeRect(-14,-14,28,28);ctx.fillStyle='#5a7b38';ctx.fillRect(-4,-4,8,8);ctx.restore();
    label('THE CORE',390,49,9,'#81966a','DM Sans',600);
  }
   const PREDICTION_MAX=720,PREDICTION_FADE_START=500;
   const predictionColor=ready=>{
     const t=Math.max(0,Math.min(1,1-(G.nextShotAt-G.time)/.5)),eased=t*t*t;
     const start=[246,123,101],end=ready.match(/\w{2}/g).map(v=>parseInt(v,16));
     return `rgb(${start.map((v,i)=>Math.round(v+(end[i]-v)*eased)).join(',')})`;
   };
   const smoothPrediction=(current,now)=>{
     if(!current.from)return current.path;
     const blend=Math.min(1,(now-current.at)/48),ease=1-(1-blend)**3,from=current.from,path=current.path,display=current.display;
     display.length=path.length;
     for(let i=0;i<path.length;i++){
       const start=from[Math.min(i,from.length-1)],end=path[i],point=display[i]||{};
       point.x=start.x+(end.x-start.x)*ease;point.y=start.y+(end.y-start.y)*ease;display[i]=point;
     }
     return display;
   };
  function drawSling(){
    const {x,y}=G.origin,d=G.drag,px=x+(d?.dx||0),py=y+(d?.dy||0);
    ctx.save();ctx.globalAlpha=.72;ctx.setLineDash([4,6]);ctx.strokeStyle='#8eae68';ctx.lineWidth=1.5;ctx.beginPath();ctx.arc(x,y,79,0,Math.PI*2);ctx.stroke();ctx.restore();
    circle(x,y,48,'#eef2e5');
    ctx.lineCap='round';line(x,y+53,x,y+16,'#293525',12);line(x,y+16,x-23,y-14,'#293525',10);line(x,y+16,x+23,y-14,'#293525',10);
    line(x-23,y-14,px,py,'#99b777',3);line(x+23,y-14,px,py,'#99b777',3);
    line(px-5,py,px+5,py,'#374c28',5);circle(x-23,y-14,3,'#b5d592');circle(x+23,y-14,3,'#b5d592');
     if(['ready','flying'].includes(G.phase)){
      const angle=d?Math.atan2(-d.dy,-d.dx):-Math.PI/2+G.keyboardAngle;
      arrow(px,py-4,angle);
      if(d&&d.dy>5){
        const len=Math.hypot(d.dx,d.dy),speed=G.speed()*(.56+.44*Math.min(1,len/100));
         const key=[Math.round(d.dx),Math.round(d.dy),G.predictionVersion,G.physicsStep,G.speed()].join(':');
         const now=performance.now();
         const interval=33;
         if(!prediction||prediction.key!==key&&now-prediction.at>=interval){
           const path=G.predictPath(x,y,-d.dx/len*speed,-d.dy/len*speed,PREDICTION_MAX),from=prediction?.display||prediction?.path;
           prediction={key,at:now,path,from:from?.map(point=>({x:point.x,y:point.y})),display:path.map(point=>({x:point.x,y:point.y}))};
         }
         const path=smoothPrediction(prediction,now);
        let travelled=0;
         ctx.save();ctx.strokeStyle=predictionColor('#4f7d2d');ctx.lineWidth=2.6;ctx.lineCap='round';ctx.lineJoin='round';ctx.shadowColor=predictionColor('#d8f3ae');ctx.shadowBlur=5;
        for(let i=1;i<path.length;i++){
          const a=path[i-1],b=path[i],segment=Math.hypot(b.x-a.x,b.y-a.y),fade=Math.min(1,Math.max(0,(PREDICTION_MAX-travelled)/(PREDICTION_MAX-PREDICTION_FADE_START)));
          if(fade<=0)break;ctx.globalAlpha=.9*fade;ctx.beginPath();ctx.moveTo(a.x,a.y);ctx.lineTo(b.x,b.y);ctx.stroke();travelled+=segment;
        }
        ctx.restore();travelled=0;
         const dotColor=predictionColor('#78a943');
         path.forEach((p,i)=>{if(i>0)travelled+=Math.hypot(p.x-path[i-1].x,p.y-path[i-1].y);if(i%6===0){const fade=Math.min(1,Math.max(0,(PREDICTION_MAX-travelled)/(PREDICTION_MAX-PREDICTION_FADE_START)));ctx.globalAlpha=.85*fade;circle(p.x,p.y,2.8,dotColor);}});ctx.globalAlpha=1;
      }else{
         ctx.save();ctx.globalAlpha=.8;ctx.setLineDash([4,7]);line(x,y-65,x+Math.sin(G.keyboardAngle)*40,y-110,predictionColor('#6f9c41'),1.7);ctx.restore();
      }
    }
    for(let i=0;i<5;i++){const active=d&&Math.hypot(d.dx,d.dy)>i*20;rounded(x-22+i*10,y+75,5,4,1,active?'#83ab51':'#d9e1ce');}
  }
  function drawPointer(){
    const p=G.pointerPos;if(!p||G.pointerType==='touch')return;
    ctx.save();ctx.translate(p.x,p.y);ctx.strokeStyle='#0d110b';ctx.fillStyle='#0d110b';ctx.lineWidth=2;ctx.lineCap='round';
    line(-9,0,-3,0,'#0d110b',2);line(3,0,9,0,'#0d110b',2);line(0,-9,0,-3,'#0d110b',2);line(0,3,0,9,'#0d110b',2);
    circle(0,0,2,'#0d110b');ctx.restore();
  }
  function boardItemEntrance(item){
    ctx.save();
    if(!G.boardEntrance)return 1;
    const delay=Math.max(0,(item.y-170)/60)*.035+(item.x/780)*.045;
    const progress=Math.max(0,Math.min(1,(G.time-G.boardEntrance.start-delay)/.42));
    const eased=1-(1-progress)**3,size=.88+.12*eased;
    ctx.translate(item.x,item.y-26*(1-eased));ctx.scale(size,size);ctx.translate(-item.x,-item.y);
    ctx.globalAlpha=eased;
    return eased;
  }
  function render(){
    const dpr=canvas.width/cssW;ctx.setTransform(dpr,0,0,dpr,0,0);ctx.clearRect(0,0,cssW,cssH);
    ctx.save();ctx.translate(offsetX,offsetY);ctx.scale(scale,scale);
    if(!G.reduced && G.shake)ctx.translate((Math.random()-.5)*G.shake,(Math.random()-.5)*G.shake);
    ctx.strokeStyle='#e6ebdf';ctx.lineWidth=1;ctx.setLineDash([3,7]);ctx.beginPath();ctx.moveTo(85,G.origin.y-130);ctx.lineTo(695,G.origin.y-130);ctx.stroke();ctx.setLineDash([]);
    if(G.phase!=='draft'){
    for(const b of G.bricks){
      const alpha=boardItemEntrance(b);
      const color=b.frozen?'#b4dce6':G.colors[b.type];
      rounded(b.x-b.w/2,b.y-b.h/2+3,b.w,b.h,5,b.type==='normal'?'#c2d59f':color);
      rounded(b.x-b.w/2,b.y-b.h/2,b.w,b.h-1,5,color);
      if(b.flash>0){ctx.globalAlpha=b.flash*3*alpha;rounded(b.x-b.w/2,b.y-b.h/2,b.w,b.h,5,'#fff');ctx.globalAlpha=alpha;}
      const ink=brickInks[b.type];
      if(b.type!=='normal'){
        const showHp=b.max>1;
        ctx.save();ctx.translate(b.x-(showHp?b.w/4:0),b.y);
        const size=showHp?Math.min(1,b.h/32,b.w/64):Math.min(1,b.h/27);
        ctx.scale(size,size);glyph(b.type,0,0,ink);ctx.restore();
        if(showHp){
          const {text,size:fontSize}=hpLabel(b);
          label(text,b.x+b.w/4,b.y+fontSize/3,fontSize,ink);
        }
      }
       else if(b.max>1){const {text,size}=hpLabel(b);label(text,b.x,b.y+size/3,size,ink);}
      else{ctx.globalAlpha=.45*alpha;line(b.x-7,b.y,b.x+7,b.y,ink,1.1);ctx.globalAlpha=alpha;}
      if(b.hp<b.max){ctx.strokeStyle='#7e955a';ctx.lineWidth=1;ctx.beginPath();ctx.moveTo(b.x-b.w/2+5,b.y-b.h/2);ctx.lineTo(b.x-b.w/2+10,b.y-3);ctx.lineTo(b.x-b.w/2+6,b.y+2);ctx.stroke();}
      if(b.frozen){ctx.strokeStyle='#7db3c3';ctx.lineWidth=1;ctx.strokeRect(b.x-b.w/2+2,b.y-b.h/2+2,b.w-4,b.h-4);}
      ctx.restore();
    }
    for(const o of G.obstacles){
      const alpha=boardItemEntrance(o);
      const left=o.x-o.w/2,top=o.y-o.h/2;
      rounded(left,top+3,o.w,o.h,4,'#4b514b');rounded(left,top,o.w,o.h,4,'#777f77');
      line(left+6,top+3,left+o.w-6,top+3,'#a1aaa0',1);
      ctx.save();ctx.beginPath();ctx.rect(left+3,top+5,o.w-6,o.h-10);ctx.clip();
      for(let x=left-20;x<left+o.w;x+=11)line(x,top+o.h,x+o.h,top,'#666f66',3);
      ctx.restore();
      rounded(o.x-8,o.y-6,16,12,3,'#535e51');line(o.x-4,o.y,o.x+4,o.y,'#d5dfcc',2);
      [left+5,left+o.w-5].forEach(x=>circle(x,o.y,1.3,'#cad3c4'));
      if(o.flash>0){ctx.globalAlpha=o.flash*3*alpha;rounded(left,top,o.w,o.h,4,'#e7f5d3');ctx.globalAlpha=alpha;}
      ctx.restore();
    }
    drawCore();
    G.drawSkillMechanics?.(ctx);
    G.drawSkillEffects?.(ctx,'field');
    }
    G.rings.forEach(r=>{ctx.globalAlpha=r.life/r.max*.65;ctx.strokeStyle=r.color;ctx.lineWidth=2;ctx.beginPath();ctx.arc(r.x,r.y,r.r*(1-r.life/r.max),0,Math.PI*2);ctx.stroke();});ctx.globalAlpha=1;
    G.bolts.forEach(b=>{ctx.globalAlpha=Math.min(1,b.life*4);ctx.strokeStyle='#bea33e';ctx.lineWidth=2.5;ctx.beginPath();ctx.moveTo(b.x,b.y);for(let i=1;i<6;i++)ctx.lineTo(b.x+(b.tx-b.x)*i/6+(Math.random()-.5)*18,b.y+(b.ty-b.y)*i/6+(Math.random()-.5)*18);ctx.lineTo(b.tx,b.ty);ctx.stroke();});ctx.globalAlpha=1;
    G.arrows.forEach(a=>{if(!a.skillVisual)a.trail.forEach((p,i)=>{ctx.globalAlpha=i/a.trail.length*.3;circle(p.x,p.y,1.8,a.color||'#8baa65');});ctx.globalAlpha=1;arrow(a.body.position.x,a.body.position.y,Math.atan2(a.body.velocity.y,a.body.velocity.x),a.color||'#343f2b');});
    drawSling();
    G.drawSkillEffects?.(ctx,'front');
    G.particles.forEach(p=>{ctx.save();ctx.globalAlpha=Math.min(1,p.life*2);ctx.translate(p.x,p.y);ctx.rotate(p.rot);ctx.fillStyle=p.color;ctx.fillRect(-p.size/2,-p.size/2,p.size,p.size);ctx.restore();});
    G.texts.forEach(p=>{ctx.globalAlpha=Math.min(1,p.life*2);label(p.text,p.x,p.y,p.size,p.color,'DM Sans',600);});ctx.globalAlpha=1;
    if(G.coreFlash>0){ctx.fillStyle=`rgba(201,239,162,${G.coreFlash*.13})`;ctx.fillRect(0,0,780,G.H);}
    drawPointer();
    ctx.restore();
  }
  const point=e=>{const r=canvas.getBoundingClientRect();return{x:(e.clientX-r.left-offsetX)/scale,y:(e.clientY-r.top-offsetY)/scale};};
  let drawStep=0;
  const updatePointer=e=>{if(e.pointerType!=='touch'){G.pointerType=e.pointerType;G.pointerPos=point(e);}};
   const powerReadout=document.querySelector('#power-readout b');
   const updateDrag=e=>{const p=point(e);if(e.pointerType!=='touch'){G.pointerType=e.pointerType;G.pointerPos=p;}let dx=p.x-G.origin.x,dy=p.y-G.origin.y;dy=Math.max(0,dy);const len=Math.hypot(dx,dy);if(len>105){dx*=105/len;dy*=105/len;}G.drag={dx,dy};const power=Math.min(100,len),step=Math.floor(power/20);if(step>drawStep)G.sound('draw',step);drawStep=step;const text=Math.round(power)+'%';if(powerReadout.textContent!==text)powerReadout.textContent=text;};
  canvas.addEventListener('pointerenter',updatePointer);
  canvas.addEventListener('pointerleave',()=>{if(!G.drag)G.pointerPos=null;});
  canvas.addEventListener('pointerdown',e=>{
     if(G.paused||!['ready','flying'].includes(G.phase)||e.button>0)return;
    const p=point(e);if(Math.hypot(p.x-G.origin.x,p.y-G.origin.y)>120)return;
    G.audio.unlock();updatePointer(e);drawStep=0;canvas.setPointerCapture(e.pointerId);canvas.focus({preventScroll:true});G.drag={dx:0,dy:0};G.pointer=e.pointerId;G.ui();
  });
  canvas.addEventListener('pointermove',e=>{if(G.drag&&G.pointer===e.pointerId)updateDrag(e);else updatePointer(e);});
  canvas.addEventListener('pointerup',e=>{if(!G.drag||G.pointer!==e.pointerId)return;const {dx,dy}=G.drag;G.drag=null;G.pointer=null;G.shoot(dx,dy);G.ui();});
  const cancel=()=>{G.drag=null;G.pointer=null;G.pointerPos=null;G.ui();};canvas.addEventListener('pointercancel',cancel);canvas.addEventListener('lostpointercapture',()=>{if(G.drag)cancel();});
  canvas.addEventListener('keydown',e=>{
     if(!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown',' '].includes(e.key))return;e.preventDefault();if(G.paused||!['ready','flying'].includes(G.phase))return;
    if(e.key==='ArrowLeft')G.keyboardAngle=Math.max(-1.1,G.keyboardAngle-.07);
    if(e.key==='ArrowRight')G.keyboardAngle=Math.min(1.1,G.keyboardAngle+.07);
    if(e.key==='ArrowUp')G.keyboardPower=Math.min(1,G.keyboardPower+.05);
    if(e.key==='ArrowDown')G.keyboardPower=Math.max(.2,G.keyboardPower-.05);
    if(e.key===' '&&!e.repeat)G.shoot(-Math.sin(G.keyboardAngle)*100*G.keyboardPower,Math.cos(G.keyboardAngle)*100*G.keyboardPower);
  });
   let last=performance.now(),acc=0;
   function frame(now){const delta=Math.min((now-last)/1000,.05);last=now;acc+=delta;let steps=0;while(acc>=G.physicsStep&&steps++<4){G.tick(G.physicsStep);acc-=G.physicsStep;}if(steps===4)acc=0;render();requestAnimationFrame(frame);}
  requestAnimationFrame(frame);
})();
