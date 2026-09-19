(() => {
  'use strict';
  const G=window.Game,{Body}=Matter,TAU=Math.PI*2,rank=id=>G.skillRank(id);
  const colors={nailburst:'#b97b51',fusepath:'#cf7554',rhythm:'#b75e7e',reboundaim:'#39978c',overkill:'#a38d42',pendulum:'#658ca8',teslanet:'#a99231',undertow:'#419eab',chronicle:'#9b789a',firewheel:'#cd8150',worldfold:'#459c95',starforge:'#bd9141'};
  let jobs=[],fields=[],signals=[],records=[];
  const point=p=>({x:p.x,y:p.y});
  const fx=(id,p,r=75)=>G.skillFX?.(id,p.x,p.y,{r,force:true});
  function later(delay,source,fn){
    if(jobs.length<192)jobs.push({at:G.time+delay,level:G.state.level,source,fn});
  }
  function signal(id,from,to=from,duration=.6){
    signals.push({id,from:point(from),to:point(to),born:G.time,duration});
    if(signals.length>96)signals.shift();
  }
  function area(p,r,damage){
    for(const b of [...G.bricks])if(Math.hypot(b.x-p.x,b.y-p.y)<=r)G.hit(b,damage,1);
  }
  function child(source,p,angle,damage,pierce){
    return G.withArrow(source,()=>{
      const a=G.addArrow(p.x,p.y,Math.cos(angle)*25,Math.sin(angle)*25,pierce);
      if(a){a.damage=damage;a.overdriveChild=true;}
      return a;
    });
  }
  function inTriangle(p,vertices){
    const cross=(a,b,c)=>(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);
    if(Math.abs(cross(...vertices))<1)return false;
    const signs=vertices.map((v,i)=>cross(v,vertices[(i+1)%3],p));
    return signs.every(s=>s>=0)||signs.every(s=>s<=0);
  }
  function netTargets(){
    const first=[...G.bricks].sort((a,b)=>b.y-a.y)[0];if(!first)return [];
    const rest=G.bricks.filter(b=>b!==first);
    const second=rest.sort((a,b)=>Math.hypot(b.x-first.x,b.y-first.y)-Math.hypot(a.x-first.x,a.y-first.y))[0];
    if(!second)return [point(first)];
    const third=rest.filter(b=>b!==second).sort((a,b)=>{
      const score=p=>Math.abs((second.x-first.x)*(p.y-first.y)-(second.y-first.y)*(p.x-first.x));
      return score(b)-score(a);
    })[0];
    return [first,second,third].filter(Boolean).map(point);
  }
  const add=G.addArrow;
  G.addArrow=(...args)=>{
    const a=add(...args);if(!a)return a;
    if(rank('fusepath')||rank('rhythm'))a.pierce+=3;
    a.overdriveBase=a.damage;return a;
  };
  const shoot=G.shoot;
  G.shoot=(...args)=>{
    const existing=new Set(G.arrows),result=shoot(...args);if(!result)return result;
    const source=G.arrows.find(a=>!existing.has(a));if(!source)return result;
    source.overdriveMain=true;
    const damage=G.damage(),v=source.body.velocity,angle=Math.atan2(v.y,v.x),shots=G.state.skillRuntime.shots;
    G.withArrow(source,()=>{
      if(rank('pendulum')){
        if(shots%2)for(const side of [-1,1])child(source,G.origin,angle+side*.19,damage*1.2,2);
        else{source.damage*=3;source.pierce+=3;fx('pendulum',G.origin,130);}
      }
      if(rank('teslanet')){
        const vertices=netTargets();
        if(vertices.length){
          fields.push({id:'teslanet',vertices,born:G.time,duration:.95,source});
          for(let i=0;i<3;i++)later(.18+i*.26,source,()=>{
            for(const b of [...G.bricks]){
              const edge=vertices.some((p,j)=>G.sweep(b.body.bounds,p,vertices[(j+1)%vertices.length],12));
              if(edge||vertices.length===3&&inTriangle(b,vertices))G.hit(b,damage*1.5,1);
            }
            vertices.forEach(p=>fx('teslanet',p,45));G.sound('lightning');
          });
        }
      }
      if(rank('chronicle')){
        const record={source,damage,until:G.time+1,hits:new Map()};records.push(record);
        later(1,source,()=>{
          records=records.filter(r=>r!==record);
          for(const [b,amount] of record.hits)if(G.bricks.includes(b)){
            signal('chronicle',b,b,.8);fx('chronicle',b,65);G.hit(b,Math.min(damage*4,amount)/(b.frozen?2:1),1);
          }
          G.sound('prism');
        });
      }
      if(rank('firewheel'))source.firewheel={next:G.time,hits:new Map()};
      if(rank('worldfold')){
        const x=Math.max(90,Math.min(690,G.origin.x+v.x/Math.max(1,-v.y)*(G.origin.y-430)));
        fields.push({id:'worldfold',x,source,damage,born:G.time,duration:1.15,previous:0,hits:new Set()});
        G.sound('core');
      }
      if(rank('starforge')&&G.bricks.length){
        const center=G.bricks.reduce((p,b)=>({x:p.x+b.x/G.bricks.length,y:p.y+b.y/G.bricks.length}),{x:0,y:0});
        fields.push({id:'starforge',...center,source,damage,born:G.time,duration:1.2,stacks:0});
        fx('starforge',center,110);G.sound('prism');
      }
    });
    return result;
  };
  const projectileHit=G.projectileHit;
  G.projectileHit=(b,a)=>{
    if(!G.bricks.includes(b))return;
    const p=point(b),base=a.damage,hp=b.hp,mult=b.frozen?2:1;
    if(rank('overkill')){a.damage+=a.storedForce||0;a.storedForce=0;}
    if(rank('rhythm')){
      a.beats=(a.beats||0)+1;
      if(a.beats%3===0){a.damage*=4;a.pierce+=2;fx('rhythm',p,100);later(.04,a,()=>area(p,100,G.damage()));G.sound('boom');}
    }
    const dealt=a.damage;
    try{projectileHit(b,a);}finally{a.damage=base;}
    if(G.phase!=='flying')return;
    if(rank('overkill')&&!G.bricks.includes(b)){
      a.storedForce=Math.min(G.damage()*4,Math.max(0,dealt*mult-hp)+G.damage()*.5);
      a.pierce=Math.max(a.pierce,2);signal('overkill',p,a.body.position);fx('overkill',p);
    }
    if(rank('nailburst')&&a.overdriveMain&&!a.shellOpened){
      a.shellOpened=true;const angle=Math.atan2(a.body.velocity.y,a.body.velocity.x);
      for(const offset of [-.38,0,.38]){
        const c=child(a,p,angle+offset,G.damage()*.9,2);
        if(c){c.overlap.add(b.body.id);c.hit.add(b.body.id);}
      }
      fx('nailburst',p,95);G.sound('prism');
    }
    if(rank('fusepath')){
      const chain=a.fusePoints??=[];
      if(chain.length<4&&!chain.some(t=>t.id===b.body.id)){
        const previous=chain.at(-1);chain.push({...p,id:b.body.id});
        if(previous){
          signal('fusepath',previous,p,.8);
          later(.25,a,()=>{for(const target of [...G.bricks])if(G.sweep(target.body.bounds,previous,p,12))G.hit(target,G.damage()*1.5,1);fx('fusepath',p,65);G.sound('boom');});
        }
      }
    }
    if(rank('undertow')&&!a.tideCast){
      a.tideCast=true;fields.push({id:'undertow',...p,born:G.time,duration:.85,source:a});
      for(let i=0;i<3;i++)later(.12+i*.2,a,()=>{
        const outer=240-i*80,inner=outer-80;
        for(const target of [...G.bricks]){const d=Math.hypot(target.x-p.x,target.y-p.y);if(d<=outer&&(i===2||d>inner))G.hit(target,G.damage()*2,1);}
        G.ring(p.x,p.y,colors.undertow,outer);G.sound('frost');
      });
      later(.75,a,()=>{area(p,110,G.damage()*3);fx('undertow',p,110);G.sound('boom');});
    }
  };
  const hit=G.hit;
  G.hit=(b,damage,depth=0)=>{
    if(G.bricks.includes(b)&&rank('chronicle')){
      const record=records.find(r=>r.source===G.activeArrow&&G.time<r.until);
      if(record){record.hits.set(b,(record.hits.get(b)||0)+Math.min(b.hp,damage*(b.frozen?2:1)));b.skillMarkUntil=record.until;}
    }
    return hit(b,damage,depth);
  };
  const destroyed=G.onBrickDestroyed;
  G.onBrickDestroyed=(b,...args)=>{
    if(rank('starforge'))for(const f of fields)if(f.id==='starforge'&&G.time-f.born<f.duration&&f.stacks<16){
      f.stacks++;signal('starforge',b,f,.45);
    }
    return destroyed?.(b,...args);
  };
  const bounce=G.onRicochet;
  G.onRicochet=a=>{
    const result=bounce?.(a);
    if(rank('reboundaim')&&(a.smartBounces||0)<3){
      const p=a.body.position,target=G.bricks.filter(b=>!a.hit.has(b.body.id)).sort((b,c)=>Math.hypot(b.x-p.x,b.y-p.y)-Math.hypot(c.x-p.x,c.y-p.y))[0];
      if(target){
        a.smartBounces=(a.smartBounces||0)+1;a.pierce++;a.damage+=a.overdriveBase*.5;
        const d=Math.hypot(target.x-p.x,target.y-p.y)||1,speed=Math.max(22,Math.hypot(a.body.velocity.x,a.body.velocity.y));
        Body.setVelocity(a.body,{x:(target.x-p.x)/d*speed,y:(target.y-p.y)/d*speed});signal('reboundaim',p,target);fx('reboundaim',p);
      }
    }
    return result;
  };
  const pending=G.hasPendingEffects;
  G.hasPendingEffects=()=>jobs.length>0||fields.length>0||pending?.();
  const tick=G.tick;
  G.tick=dt=>{
    if(G.paused||G.phase==='draft')return tick(dt);
    if(G.phase==='flying'){
      const due=jobs.filter(j=>j.at<=G.time);jobs=jobs.filter(j=>j.at>G.time);
      for(const job of due)if(G.phase==='flying'&&job.level===G.state.level)G.withArrow(job.source,job.fn);
      for(const a of G.arrows)if(a.firewheel&&G.time>=a.firewheel.next){
        a.firewheel.next=G.time+.18;
        G.withArrow(a,()=>{for(const b of [...G.bricks]){
          const distance=Math.hypot(b.x-a.body.position.x,b.y-a.body.position.y),count=a.firewheel.hits.get(b.body.id)||0;
          if(distance>=45&&distance<=115&&count<4){a.firewheel.hits.set(b.body.id,count+1);G.hit(b,G.damage(),1);}
        }});
      }
      for(const f of [...fields]){
        if(G.phase!=='flying')break;
        const t=Math.min(1,(G.time-f.born)/f.duration);
        G.withArrow(f.source,()=>{
          if(f.id==='worldfold'){
            const left=20+(f.x-20)*t,right=760-(760-f.x)*t,oldLeft=20+(f.x-20)*f.previous,oldRight=760-(760-f.x)*f.previous;
            for(const b of [...G.bricks])if(!f.hits.has(b.body.id)&&((b.x+b.w/2>=oldLeft&&b.x-b.w/2<=left)||(b.x-b.w/2<=oldRight&&b.x+b.w/2>=right))){f.hits.add(b.body.id);G.hit(b,f.damage*3,1);}
            f.previous=t;
            if(t===1){
              signal('worldfold',{x:f.x,y:125},{x:f.x,y:G.origin.y-120},.65);
              for(const b of [...G.bricks])if(Math.abs(b.x-f.x)<=75+b.w/2)G.hit(b,f.damage*6,1);
              fx('worldfold',{x:f.x,y:430},180);G.sound('boom');if(!G.reduced)G.shake=Math.max(G.shake,9);
            }
          }
          if(f.id==='starforge'&&t===1){
            const radius=170+f.stacks*12;area(f,radius,f.damage*(4+f.stacks*.4));
            fx('starforge',f,radius);G.ring(f.x,f.y,colors.starforge,radius);G.float(f.x,f.y-35,'熔炉释放 · '+f.stacks+' 层',colors.starforge,22);G.sound('boom');if(!G.reduced)G.shake=Math.max(G.shake,10);
          }
        });
      }
      fields=fields.filter(f=>G.time-f.born<f.duration);
    }
    signals=signals.filter(s=>G.time-s.born<s.duration);
    return tick(dt);
  };
  const generate=G.generate,clear=G.clear;
  const reset=()=>{jobs=[];fields=[];signals=[];records=[];};
  G.generate=(...args)=>{reset();return generate(...args);};
  G.clear=(...args)=>{reset();return clear(...args);};
  const status=G.expandedSkillStatus;
  G.expandedSkillStatus=id=>{
    if(id==='pendulum')return{text:G.state.skillRuntime.shots%2?'下箭：重弩 ×3':'下箭：双翼齐射',progress:1};
    if(id==='rhythm'){const beats=G.arrows.at(-1)?.beats||0;return{text:`重音 ${beats%3} / 3`,progress:beats%3/3};}
    if(id==='chronicle')return{text:records.length?`正在记录 ${records.length} 支箭的伤痕`:'伤痕重映就绪',progress:records.length?Math.min(1,1-(records[0].until-G.time)):1};
    if(id==='starforge'){const f=fields.find(f=>f.id===id);return{text:f?`熔炉 ${f.stacks} / 16 层`:'吞星熔炉就绪',progress:f?f.stacks/16:1};}
    if(id==='worldfold')return{text:fields.some(f=>f.id===id)?'光墙合拢中':'对折光墙就绪',progress:1};
    return status?.(id);
  };
  const draw=G.drawSkillMechanics;
  const line=(ctx,a,b)=>{ctx.beginPath();ctx.moveTo(a.x,a.y);ctx.lineTo(b.x,b.y);ctx.stroke();};
  const ring=(ctx,x,y,r)=>{ctx.beginPath();ctx.arc(x,y,r,0,TAU);ctx.stroke();};
  G.drawSkillMechanics=ctx=>{
    draw?.(ctx);ctx.save();ctx.lineCap='round';
    for(const s of signals){
      const t=(G.time-s.born)/s.duration;ctx.strokeStyle=colors[s.id];ctx.globalAlpha=1-t;ctx.lineWidth=2;
      if(s.id==='chronicle'){ctx.save();ctx.translate(s.from.x,s.from.y);G.paintSkillSignature(ctx,s.id,t,50,colors[s.id],'#b4c8bf');ctx.restore();}
      else if(s.id==='worldfold'){for(const width of [150,35,3]){ctx.lineWidth=width;ctx.globalAlpha=(1-t)*(width===3?.9:.12);line(ctx,s.from,s.to);}}
      else{
        ctx.setLineDash(s.id==='reboundaim'?[4,7]:[]);line(ctx,s.from,s.to);ctx.setLineDash([]);
        const progress=s.id==='fusepath'?Math.min(1,t*3):Math.min(1,t*2);
        ring(ctx,s.from.x+(s.to.x-s.from.x)*progress,s.from.y+(s.to.y-s.from.y)*progress,4);
      }
    }
    for(const f of fields){
      const t=Math.min(1,(G.time-f.born)/f.duration);ctx.strokeStyle=colors[f.id];ctx.fillStyle=colors[f.id];ctx.globalAlpha=.75;ctx.lineWidth=2;
      if(f.id==='teslanet'){
        ctx.beginPath();f.vertices.forEach((p,i)=>i?ctx.lineTo(p.x,p.y):ctx.moveTo(p.x,p.y));ctx.closePath();ctx.stroke();ctx.globalAlpha=G.reduced?.08:.06+Math.sin(t*Math.PI*6)**2*.12;ctx.fill();
        for(const p of f.vertices){ctx.globalAlpha=.8;ring(ctx,p.x,p.y,12);}
      }
      if(f.id==='undertow')for(let i=0;i<3;i++){ctx.globalAlpha=(1-t)*(.6-i*.12);ring(ctx,f.x,f.y,Math.max(4,240*(1-t)-i*30));}
      if(f.id==='worldfold')for(const x of [20+(f.x-20)*t,760-(760-f.x)*t]){
        for(const width of [24,7,2]){ctx.lineWidth=width;ctx.globalAlpha=width===2?.9:.14;line(ctx,{x,y:125},{x,y:G.origin.y-100});}
        ctx.globalAlpha=.45;for(let y=145;y<G.origin.y-100;y+=42)line(ctx,{x:x-9,y:y+9},{x:x+9,y:y-9});
      }
      if(f.id==='starforge'){
        const r=25+f.stacks*2;ctx.globalAlpha=.12;ring(ctx,f.x,f.y,170+f.stacks*12);
        ctx.save();ctx.translate(f.x,f.y);ctx.globalAlpha=.85;G.paintSkillSignature(ctx,f.id,G.reduced?.5:t,r+22,colors[f.id],'#71aaa2');ctx.restore();
        ctx.globalAlpha=.8;ctx.font='bold 16px sans-serif';ctx.textAlign='center';ctx.fillText(String(f.stacks),f.x,f.y+6);
      }
    }
    for(const a of G.arrows)if(a.firewheel){
      const p=a.body.position,angle=G.reduced?0:G.time*7;ctx.strokeStyle=colors.firewheel;ctx.lineWidth=2;ctx.globalAlpha=.25;ring(ctx,p.x,p.y,90);
      for(const side of [0,Math.PI]){ctx.save();ctx.translate(p.x+Math.cos(angle+side)*90,p.y+Math.sin(angle+side)*90);ctx.globalAlpha=.9;G.paintSkillSignature(ctx,'firewheel',.5,20,colors.firewheel,'#dec773');ctx.restore();}
    }
    ctx.restore();
  };
})();
