(() => {
  'use strict';
  const G=window.Game,{Body,Composite}=Matter,TAU=Math.PI*2;
  const rank=id=>G.skillRank(id),runtime=()=>G.state.skillRuntime;
  let tasks=[],saws=[],signals=[];
  const colors={boomerang:'#5c9c8b',wormhole:'#947cab',buzzsaw:'#a38b60',siegebreaker:'#b77b59',spectral:'#779eb0',transmute:'#859f54',threadweaver:'#b7748d',infection:'#7d9f57',contract:'#b69a48',timeslip:'#649caa'};
  const fx=(id,x,y,options={})=>G.skillFX?.(id,x,y,options);
  function later(delay,source,fn){
    if(tasks.length>=160)return;
    tasks.push({at:G.time+delay,level:G.state.level,source,fn});
  }
  function signal(id,type,from,to=from,duration=.6){
    signals.push({id,type,from:{...from},to:{...to},born:G.time,duration});
    if(signals.length>80)signals.splice(0,signals.length-80);
  }
  function damageArea(position,radius,damage,source){
    G.withArrow(source,()=>{for(const b of [...G.bricks])if(Math.hypot(b.x-position.x,b.y-position.y)<radius)G.hit(b,damage,1);});
  }
  function ensureContract(){
    if(!rank('contract'))return;
    const c=runtime().contract??={rounds:0,targets:[],size:0};
    if(c.rounds>=3||c.targets.length||!G.bricks.length)return;
    c.targets=[...G.bricks].sort((a,b)=>b.y-a.y||a.hp-b.hp).slice(0,3).map(b=>({x:b.x,y:b.y}));
    c.size=c.targets.length;G.save();
  }
  const contracted=b=>rank('contract')&&runtime().contract?.targets.some(p=>p.x===b.x&&p.y===b.y);
  const choose=G.chooseSkill;
  G.chooseSkill=id=>{const result=choose(id);if(result){ensureContract();G.ui();}return result;};
  const add=G.addArrow;
  G.addArrow=(...args)=>{
    const a=add(...args);if(!a)return a;
    if(rank('boomerang')||rank('spectral'))a.pierce+=6;
    if(rank('siegebreaker')||rank('spectral')||rank('timeslip'))a.damage*=2;
    a.expansionBase=a.damage;return a;
  };
  const shoot=G.shoot;
  G.shoot=(...args)=>{
    const existing=new Set(G.arrows),result=shoot(...args);if(!result)return result;
    const source=G.arrows.find(a=>!existing.has(a));
    if(rank('timeslip'))G.nextShotAt=G.time+.25;
    if(rank('transmute')){
      const candidates=G.bricks.filter(b=>b.type==='normal');
      for(let i=candidates.length-1;i>0;i--){const j=Math.floor(Math.random()*(i+1));[candidates[i],candidates[j]]=[candidates[j],candidates[i]];}
      candidates.slice(0,4).forEach((b,i)=>{
        b.type=['bomb','lightning','frost','prism'][(runtime().shots+i)%4];b.hp=Math.max(.25,b.hp*.5);b.flash=.25;
        signal('transmute','transform',b,b,.9);fx('transmute',b.x,b.y,{r:65,force:true});
      });
      G.sound('upgrade');G.save();
    }
    if(rank('buzzsaw')&&G.bricks.length){
      const rows=new Map();for(const b of G.bricks)rows.set(b.y,(rows.get(b.y)||0)+1);
      const y=[...rows].sort((a,b)=>b[1]-a[1]||b[0]-a[0])[0][0],direction=runtime().shots%2?1:-1;
      saws.push({source,y,x:direction>0?30:750,fromX:direction>0?30:750,direction,born:G.time,duration:1.4,next:G.time,hits:new Map(),damage:G.damage()*1.5});
      if(saws.length>6)saws.shift();G.sound('ricochet');
    }
    ensureContract();return result;
  };
  function infect(b,source,chain){
    if(chain.visited.has(b.body.id)||chain.visited.size>=15||!G.bricks.includes(b))return;
    chain.visited.add(b.body.id);const origin={x:b.x,y:b.y};
    signal('infection','seed',origin,origin,.35);
    later(.3,source,()=>{
      fx('infection',origin.x,origin.y,{r:65,force:true});
      if(G.bricks.includes(b))G.hit(b,chain.damage,1);
      const next=G.bricks.filter(t=>!chain.visited.has(t.body.id)&&Math.hypot(t.x-origin.x,t.y-origin.y)<=230).sort((a,b)=>Math.hypot(a.x-origin.x,a.y-origin.y)-Math.hypot(b.x-origin.x,b.y-origin.y)).slice(0,2);
      for(const target of next){if(chain.visited.size>=15)break;signal('infection','root',origin,target,.5);infect(target,source,chain);}
    });
  }
  const projectileHit=G.projectileHit;
  G.projectileHit=(b,a)=>{
    if(!G.bricks.includes(b))return;
    const position={x:b.x,y:b.y},contract=contracted(b),damage=a.damage;
    if(contract)a.damage*=2;
    if(rank('infection')&&!a.infectionChain){a.infectionChain={visited:new Set(),damage:G.damage()*3};infect(b,a,a.infectionChain);}
    try{projectileHit(b,a);}finally{if(contract)a.damage=damage;}
    if(G.phase!=='flying')return;
    if(rank('wormhole')&&(a.warps||0)<3&&!a.warpPending){
      a.warpPending=true;a.warps=(a.warps||0)+1;a.pierce+=2;
      later(.035,a,()=>{
        a.warpPending=false;if(!G.arrows.includes(a))return;
        const target=G.bricks.filter(t=>!a.hit.has(t.body.id)&&Math.hypot(t.x-position.x,t.y-position.y)>80).sort((x,y)=>Math.hypot(y.x-position.x,y.y-position.y)-Math.hypot(x.x-position.x,x.y-position.y))[0];
        if(!target)return;
        a.damage=a.expansionBase*(1+.5*a.warps);
        const exit={x:target.x,y:target.y+target.h/2+9},entry={...a.body.position};
        Body.setPosition(a.body,exit);Body.setVelocity(a.body,{x:0,y:-Math.max(22,Math.hypot(a.body.velocity.x,a.body.velocity.y))});a.overlap.clear();a.trail=[];
        signal('wormhole','portal',entry,exit,.8);fx('wormhole',entry.x,entry.y,{r:65,force:true});fx('wormhole',exit.x,exit.y,{r:65,force:true});G.sound('prism');
      });
    }
    if(rank('threadweaver')){
      const chain=a.threadChain??={points:[],seen:new Set()};
      if(chain.points.length>=5||chain.seen.has(b.body.id))return;
      chain.seen.add(b.body.id);const previous=chain.points.at(-1);chain.points.push(position);
      if(previous)later(.045,a,()=>{
        signal('threadweaver','cut',previous,position,.75);fx('threadweaver',position.x,position.y,{r:55});
        for(const target of [...G.bricks])if(G.sweep(target.body.bounds,previous,position,14))G.hit(target,G.damage()*3,1);
        G.sound('prism');
      });
      // Let the arrow reach all five anchor points even before it earns its first cut.
      a.pierce=Math.max(a.pierce,6-chain.points.length);
    }
  };
  const destroyed=G.onBrickDestroyed;
  G.onBrickDestroyed=(b,...args)=>{
    if(contracted(b)){
      const c=runtime().contract;c.targets=c.targets.filter(p=>p.x!==b.x||p.y!==b.y);
      fx('contract',b.x,b.y,{r:75,force:true});
      if(!c.targets.length){
        c.rounds++;const bounty=Math.round(G.bonus()*.4);G.state.coins+=bounty;G.shotMoney+=bounty;
        G.arrows.forEach(a=>a.pierce+=4);G.float(390,G.origin.y-110,'契约完成 +'+G.fmt(bounty),colors.contract,24);G.sound('upgrade');
        fx('contract',390,G.origin.y-130,{r:145,force:true});
        // Rearm next tick, after the current destruction chain has finished.
        if(c.rounds<3)later(.12,G.activeArrow,ensureContract);
      }
      G.save();
    }
    return destroyed?.(b,...args);
  };
  const bounce=G.onRicochet;
  G.onRicochet=a=>{
    if(rank('siegebreaker')){
      const p=a.body.position,obstacle=G.obstacles.find(o=>Math.abs(o.x-p.x)<=o.w/2+6&&Math.abs(o.y-p.y)<=o.h/2+6);
      if(obstacle){
         Composite.remove(G.engine.world,obstacle.body);G.obstacles=G.obstacles.filter(o=>o!==obstacle);G.predictionVersion++;
        if(a.preImpactVelocity)Body.setVelocity(a.body,a.preImpactVelocity);
        const origin={x:obstacle.x,y:obstacle.y};fx('siegebreaker',origin.x,origin.y,{r:155,force:true});
        signal('siegebreaker','rubble',origin,origin,.8);G.burst(origin.x,origin.y,'#87917f',22,1.5);G.sound('boom');
        runtime().wallsBroken=(runtime().wallsBroken||0)+1;
        later(.035,a,()=>damageArea(origin,155,G.damage()*4,a));G.save();return;
      }
    }
    return bounce?.(a);
  };
  G.phasesObstacles=()=>rank('spectral')>0;
  G.onPhaseObstacle=(a,o)=>{
    const seen=a.phasedWalls??=new Set();if(seen.has(o.body.id))return;seen.add(o.body.id);
    a.damage+=a.expansionBase*.5;signal('spectral','phase',o,o,.7);fx('spectral',o.x,o.y,{r:60});
  };
  const beforePhysics=G.beforePhysics;
  G.beforePhysics=dt=>{
    const slow=rank('timeslip')&&G.drag?.dy>5;G.engine.timing.timeScale=slow ? .2 : 1;
    for(const a of G.arrows){
      a.preImpactVelocity={...a.body.velocity};
      if(slow)a.life-=dt*.8;
      if(rank('boomerang')&&!a.returned&&a.life>=.55){
        a.returned=true;a.damage*=3;a.pierce+=6;a.overlap.clear();a.hit.clear();
        const v=a.body.velocity;Body.setVelocity(a.body,{x:-v.x,y:-v.y});
        fx('boomerang',a.body.position.x,a.body.position.y,{r:100,force:true});G.sound('ricochet');
      }
    }
    return beforePhysics?.(dt);
  };
  const pending=G.hasPendingEffects;
  G.hasPendingEffects=()=>tasks.length>0||saws.length>0||pending?.();
  const tick=G.tick;
  G.tick=dt=>{
    if(G.paused||G.phase==='draft')return tick(dt);
    if(G.phase==='flying'){
      const due=tasks.filter(t=>t.at<=G.time);tasks=tasks.filter(t=>t.at>G.time);
      for(const task of due){if(G.phase!=='flying')break;if(task.level===G.state.level)G.withArrow(task.source,task.fn);}
      for(const saw of saws){
        if(G.phase!=='flying')break;
        const progress=Math.min(1,(G.time-saw.born)/saw.duration);saw.x=saw.direction>0?30+progress*720:750-progress*720;
        if(G.time<saw.next)continue;saw.next=G.time+.075;
        const from={x:saw.fromX,y:saw.y},to={x:saw.x,y:saw.y};saw.fromX=saw.x;
        G.withArrow(saw.source,()=>{
          for(const b of [...G.bricks])if((saw.hits.get(b.body.id)||0)<3&&G.sweep(b.body.bounds,from,to,43)){
            saw.hits.set(b.body.id,(saw.hits.get(b.body.id)||0)+1);G.hit(b,saw.damage,1);
          }
        });
      }
      saws=saws.filter(s=>G.time-s.born<s.duration);
    }else G.engine.timing.timeScale=1;
    signals=signals.filter(s=>G.time-s.born<s.duration);return tick(dt);
  };
  function resetTransient(){tasks=[];saws=[];signals=[];G.engine.timing.timeScale=1;}
  const generate=G.generate,clear=G.clear;
  G.generate=(...args)=>{resetTransient();const result=generate(...args);ensureContract();return result;};
  G.clear=(...args)=>{resetTransient();return clear(...args);};
  G.expandedSkillStatus=id=>{
    if(id==='contract'){const c=runtime().contract;return{text:c?.rounds>=3?'三轮悬赏已完成':`悬赏 ${c?c.size-c.targets.length:0} / ${c?.size||3} · 第 ${Math.min(3,(c?.rounds||0)+1)} 轮`,progress:c?.size?(c.size-c.targets.length)/c.size:0};}
    if(id==='siegebreaker')return{text:`已粉碎 ${runtime().wallsBroken||0} 面障碍`,progress:1};
    if(id==='timeslip')return{text:G.drag?.dy>5?'时流 20% · 布阵中':'时流恢复 · 快速连射',progress:G.drag?.dy>5 ? .2 : 1};
    if(id==='buzzsaw')return{text:saws.length?`${saws.length} 枚锯盘横切中`:'锯盘就绪',progress:1};
    if(id==='transmute')return{text:`已射击 ${runtime().shots} 次 · 连锁炼成`,progress:1};
    if(id==='spectral')return{text:'无视障碍 · 越墙增幅',progress:1};
    return null;
  };
  const line=(ctx,a,b)=>{ctx.beginPath();ctx.moveTo(a.x,a.y);ctx.lineTo(b.x,b.y);ctx.stroke();};
  G.drawSkillMechanics=ctx=>{
    ctx.save();ctx.lineCap='round';
    for(const s of signals){
      const t=Math.min(1,(G.time-s.born)/s.duration);ctx.strokeStyle=colors[s.id];ctx.globalAlpha=(1-t)*.8;ctx.lineWidth=2;
      if(s.type==='cut'){
        for(const [width,alpha] of [[16,.1],[5,.3],[1.5,.9]]){ctx.lineWidth=width;ctx.globalAlpha=(1-t)*alpha;line(ctx,s.from,s.to);}
      }else if(s.type==='root'){
        const end={x:s.from.x+(s.to.x-s.from.x)*Math.min(1,t*3),y:s.from.y+(s.to.y-s.from.y)*Math.min(1,t*3)};
        ctx.beginPath();ctx.moveTo(s.from.x,s.from.y);ctx.quadraticCurveTo((s.from.x+end.x)/2+20,(s.from.y+end.y)/2-15,end.x,end.y);ctx.stroke();
      }else if(s.type==='portal'){
        ctx.setLineDash([2,9]);ctx.globalAlpha=(1-t)*.25;line(ctx,s.from,s.to);ctx.setLineDash([]);
        for(const p of [s.from,s.to]){ctx.save();ctx.translate(p.x,p.y);ctx.globalAlpha=1-t;G.paintSkillSignature?.(ctx,s.id,t,48,colors[s.id],'#9dc2b4');ctx.restore();}
      }else{
        ctx.save();ctx.translate(s.from.x,s.from.y);G.paintSkillSignature?.(ctx,s.id,t,s.type==='seed'?24:48,colors[s.id],'#c8c68b');ctx.restore();
      }
    }
    for(const saw of saws){
      ctx.save();ctx.translate(saw.x,saw.y);ctx.rotate(G.reduced?0:(G.time-saw.born)*saw.direction*10);ctx.globalAlpha=.95;
      ctx.fillStyle='#f4f2e9';ctx.beginPath();ctx.arc(0,0,38,0,TAU);ctx.fill();
      G.paintSkillSignature?.(ctx,'buzzsaw',.6,53,colors.buzzsaw,'#d4bb79');ctx.restore();
    }
    if(rank('contract'))for(const p of runtime().contract?.targets||[]){
      const b=G.bricks.find(b=>b.x===p.x&&b.y===p.y);if(!b)continue;
      ctx.globalAlpha=.85;ctx.strokeStyle=colors.contract;ctx.lineWidth=2;ctx.strokeRect(b.x-b.w/2-4,b.y-b.h/2-4,b.w+8,b.h+8);
      ctx.save();ctx.translate(b.x,b.y-b.h/2-10);G.paintSkillSignature?.(ctx,'contract',.5,13,colors.contract,'#a6b575','aura');ctx.restore();
    }
    if(rank('spectral'))for(const o of G.obstacles){ctx.globalAlpha=.7;ctx.strokeStyle=colors.spectral;ctx.setLineDash([4,6]);ctx.strokeRect(o.x-o.w/2-3,o.y-o.h/2-3,o.w+6,o.h+6);ctx.setLineDash([]);}
    if(rank('timeslip')&&G.drag?.dy>5){
      ctx.globalAlpha=.3;ctx.strokeStyle=colors.timeslip;ctx.lineWidth=3;
      for(const x of [22,758])line(ctx,{x,y:135},{x,y:G.origin.y-100});
      for(const a of G.arrows.slice(0,12)){ctx.save();ctx.translate(a.body.position.x,a.body.position.y);G.paintSkillSignature?.(ctx,'timeslip',.5,22,colors.timeslip,'#b9c694','aura');ctx.restore();}
    }
    ctx.restore();
  };
  ensureContract();
})();
