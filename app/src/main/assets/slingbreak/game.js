/* Matter.js advances projectiles; swept ray queries prevent tunneling through bricks. */
(() => {
  'use strict';
   const {Engine, Bodies, Body, Composite} = Matter;
  const KEY = 'slingbreak-save-v1';
  const defaults = () => ({level:1,coins:0,total:0,best:0,up:{power:0,arrow:0,brick:0},sound:true,board:null,skills:{},skillChosenLevel:0,draft:null,skillRuntime:null});
  let saved;
  try { saved=JSON.parse(localStorage.getItem(KEY)); } catch {}
  const validNumber = n => typeof n==='number' && Number.isFinite(n) && n>=0;
  const valid = saved && ['level','coins','total','best'].every(k=>validNumber(saved[k])) && saved.level>=1 && Number.isInteger(saved.level) && saved.up && ['power','arrow','brick'].every(k=>Number.isInteger(saved.up[k])&&saved.up[k]>=0);
  const state = valid ? saved : defaults();
  // One-time migration: sound used to default off; flip existing saves to on.
  if (saved && saved.sound === false && saved.soundMigrated !== true) state.sound = true;
  state.soundMigrated = true;
   const engine = Engine.create({gravity:{x:0,y:.48}}),previewEngine=Engine.create({gravity:{x:0,y:.48}});
    const G = window.Game = {state,engine,bricks:[],obstacles:[],arrows:[],particles:[],texts:[],rings:[],bolts:[],core:null,W:780,H:1400,origin:{x:390,y:970},combo:0,shotMoney:0,shotTime:0,shots:0,killed:0,initial:0,threshold:0,phase:'ready',paused:false,drag:null,shake:0,time:0,coreFlash:0,toast:null,ui:()=>{},keyboardAngle:0,keyboardPower:.85,reduced:matchMedia('(prefers-reduced-motion: reduce)').matches,physicsStep:1/60,predictionVersion:0};
  G.colors={normal:'#d5e8b3',bomb:'#f58d75',lightning:'#ecd77e',frost:'#a6d5e3',prism:'#c4b2e2',gold:'#d4df85'};
  G.withArrow=(arrow,fn)=>{const previous=G.activeArrow;G.activeArrow=arrow;try{return fn();}finally{G.activeArrow=previous;}};
  G.fmt = n => n>=1e9 ? (n/1e9).toFixed(1)+'B' : n>=1e6 ? (n/1e6).toFixed(1)+'M' : n>=10000 ? (n/1000).toFixed(1)+'k' : Math.floor(n).toLocaleString('en-US');
   G.balanceVersion=3;
   G.layoutVersion=2;
   G.nextShotAt=0;
  // Damage grows deliberately slower than brick HP so a run can build power
  // without turning every later board into a single-hit sweep.
  G.damage = () => 2 + .65*Math.log2(state.up.arrow+1);
  G.speed = () => 24 + 11*(1-Math.exp(-state.up.power/9));
  G.penetration = () => Math.min(6,2 + Math.floor(state.up.arrow/4));
  G.pierceCost = body => body.label==='brick' && body.brick.type==='frost' ? 0 : 1;
  G.specialRate = () => Math.min(.22,.10+.12*(1-Math.exp(-state.up.brick/12)));
  G.valueMultiplier = () => 1+.16*state.up.brick;
  G.baseHp = () => Math.floor(3+.8*Math.log2(state.level)+.2*Math.log2(state.level)**2);
  G.cost = key => Math.ceil(({power:75,arrow:100,brick:120}[key])*Math.pow(({power:1.4,arrow:1.46,brick:1.5}[key]),state.up[key]));
  G.bonus = (level=state.level) => Math.round(240*Math.pow(level,1.15));
  G.mult = n => Math.min(12,Math.pow(1.14,Math.min(10,Math.max(0,n-1)))*Math.pow(1.035,Math.max(0,n-11)));
  G.reward = (type,n) => Math.max(1,Math.round(3*Math.pow(state.level,1.1)*G.valueMultiplier()*G.mult(n)*(type==='gold'?3:1)));
  G.save = () => {
     state.board={layoutVersion:G.layoutVersion,balanceVersion:G.balanceVersion,level:state.level,initial:G.initial,killed:G.killed,bricks:G.bricks.map(b=>({x:b.x,y:b.y,w:b.w,h:b.h,hp:b.hp,max:b.max,type:b.type,frozen:b.frozen})),obstacles:G.obstacles.map(o=>({x:o.x,y:o.y,w:o.w,h:o.h})),core:!!G.core};
    try {localStorage.setItem(KEY,JSON.stringify(state));} catch {document.getElementById('save-status').textContent='存档不可用';}
  };
  const makeBrick = data => {
    const b={...data,flash:0,id:Math.random()};
    b.body=Bodies.rectangle(b.x,b.y,b.w,b.h,{isStatic:true,label:'brick'});
    b.body.brick=b;G.bricks.push(b);Composite.add(engine.world,b.body);return b;
  };
  const makeObstacle = data => {
    const o={...data,flash:0};
    o.body=Bodies.rectangle(o.x,o.y,o.w,o.h,{isStatic:true,label:'obstacle'});
    o.body.obstacle=o;G.obstacles.push(o);Composite.add(engine.world,o.body);return o;
  };
  const shuffle = items => {
    for(let i=items.length-1;i>0;i--){const j=Math.floor(Math.random()*(i+1));[items[i],items[j]]=[items[j],items[i]];}
    return items;
  };
   G.generate = (restore=false) => {
     G.boardEntrance = null;
     G.nextShotAt=0;
     G.predictionVersion++;Composite.clear(engine.world);G.bricks=[];G.obstacles=[];G.arrows=[];G.core=null;G.combo=0;G.shotMoney=0;G.killed=0;G.shots=0;G.phase='ready';G.particles=[];G.rings=[];G.bolts=[];G.texts=[];G.coreFlash=0;
    const board=state.board;
    const validObstacles=board && (board.obstacles===undefined || (Array.isArray(board.obstacles)&&board.obstacles.length<=12&&board.obstacles.every(o=>['x','y','w','h'].every(k=>validNumber(o[k]))&&o.w>0&&o.h>0)));
     if(restore && board && !(board.layoutVersion!==G.layoutVersion&&board.killed===0) && !(board.balanceVersion!==G.balanceVersion&&board.killed===0) && board.level===state.level && Array.isArray(board.bricks) && board.bricks.length<=240 && board.bricks.every(b=>['x','y','w','h','hp','max'].every(k=>validNumber(b[k])) && b.hp>0 && b.w>0 && b.h>0 && b.type in G.colors) && validNumber(board.initial) && validNumber(board.killed) && board.initial>0 && validObstacles){
      board.bricks.forEach(makeBrick);G.initial=board.initial;G.killed=board.killed;G.threshold=Math.ceil(G.initial*.6);
      (board.obstacles||[]).forEach(makeObstacle);
      if(board.balanceVersion!==G.balanceVersion){
        const oldBase=Math.max(1,Math.floor(1+.65*Math.log2(state.level)+.16*Math.log2(state.level)**2));
        G.bricks.forEach(b=>{const remaining=b.hp/b.max;b.max=G.baseHp()+(b.max>oldBase?1:0);b.hp=b.max*remaining;});
        const special=shuffle(G.bricks.filter(b=>b.type!=='normal'));
        special.slice(Math.floor(G.initial*G.specialRate())).forEach(b=>b.type='normal');
      }
      if(board.core || G.killed>=G.threshold) G.spawnCore(true);
    }else{
       const rows=10+Math.min(1,Math.floor((state.level-1)/8));
      const types=['bomb','lightning','frost','prism','gold'];
      const base=G.baseHp();
      const slots=[];
       for(let r=0;r<rows;r++)for(let c=0;c<7;c++)slots.push({r,c,x:102+c*96,y:170+r*60});
      const barriers=new Set(),count=5+Math.floor(Math.random()*3)+Math.min(1,Math.floor(state.level/10));
      // Keep an open central route to the core and separate barriers to avoid sealed pockets.
       for(const s of shuffle(slots.filter(s=>s.r>0&&s.r<rows-1&&s.y<920&&Math.abs(s.c-3)>=2))){
        if(G.obstacles.length>=count)break;
        if(G.obstacles.some(o=>Math.hypot(o.x-s.x,o.y-s.y)<104))continue;
         makeObstacle({x:s.x,y:s.y,w:84,h:44});barriers.add(s);
      }
       const gaps=new Set(shuffle(slots.filter(s=>s.c!==3&&!barriers.has(s))).slice(0,4+Math.floor(Math.random()*4)));
      for(const s of slots) {
        if(barriers.has(s)||gaps.has(s))continue;
        const hp=base+(Math.random()<.12?1:0);
         makeBrick({x:s.x,y:s.y,w:84,h:44,hp,max:hp,type:'normal',frozen:false});
      }
      // A fixed quota includes the five guaranteed types, so no board exceeds its rate.
      const candidates=shuffle([...G.bricks]);
      const quota=Math.floor(G.bricks.length*G.specialRate());
      for(let i=0;i<quota;i++){
        candidates[i].type=i<types.length?types[i]:types[Math.floor(Math.random()*types.length)];
        candidates[i].hp=candidates[i].max=base;
      }
      G.initial=G.bricks.length;G.threshold=Math.ceil(G.initial*.6);
    }
    G.save();G.ui();
  };
  G.burst=(x,y,color,count=16,force=1)=>{
    if(G.reduced) count=Math.min(count,5);
    else count=Math.ceil(count*.55);
    for(let i=0;i<count;i++){const a=Math.random()*Math.PI*2,v=(1+Math.random()*5)*force;G.particles.push({x,y,vx:Math.cos(a)*v,vy:Math.sin(a)*v-1,life:.5+Math.random()*.4,max:1,size:2+Math.random()*5,color,rot:Math.random()*6});}
    if(G.particles.length>300)G.particles.splice(0,G.particles.length-300);
  };
  G.ring=(x,y,color,r=90)=>G.rings.push({x,y,color,r,life:.55,max:.55});
  G.float=(x,y,text,color='#566e37',size=17)=>G.texts.push({x,y,text,color,size,life:1.05});
  G.spawnCore = (quiet=false) => {
    if(G.core || G.phase==='clearing')return;
    G.predictionVersion++;
    const body=Bodies.rectangle(390,80,47,47,{isStatic:true,label:'core',angle:Math.PI/4});
    G.core={x:390,y:80,body,born:G.time-(quiet?2:0)};Composite.add(engine.world,body);
    if(!quiet){G.coreFlash=1;G.ring(390,80,'#a4d65e',220);G.burst(390,80,'#a4d65e',45,2);G.shake=6;G.sound('core');G.toast?.('核心已显现 · 命中即可清场');}
    G.ui();
  };
  G.clear = () => {
    if(G.phase==='clearing')return;
    G.phase='clearing';G.clearAt=G.time+2.0;const bonus=G.bonus();state.coins+=bonus;G.shake=14;G.coreFlash=1.6;
    G.ring(390,80,'#a4d65e',850);G.burst(390,80,'#93c446',80,3);G.float(390,385,'核心击破','#415d26',35);G.float(390,431,'关卡奖金 + '+G.fmt(bonus),'#709945',24);G.specialSound('win');
    G.bricks.forEach(b=>G.burst(b.x,b.y,G.colors[b.type],6));
    // Core cleanup is deliberately separate from rewarded destruction and combo counters.
    G.bricks=[];G.obstacles=[];G.arrows=[];G.core=null;Composite.clear(engine.world);
    state.level++;state.board=null;
    try{localStorage.setItem(KEY,JSON.stringify(state));}catch{}
    G.ui();
  };
  G.hit=(b,damage,depth=0)=>{
    if(!G.bricks.includes(b)||G.phase==='clearing')return;
    b.hp-=damage*(b.frozen?2:1);b.flash=.16;
    if(b.hp>0){G.burst(b.x,b.y,G.colors[b.type],4,.5);G.sound('tap',1,b.x);return;}
    G.bricks.splice(G.bricks.indexOf(b),1);G.predictionVersion++;Composite.remove(engine.world,b.body);G.killed++;state.total++;G.combo++;state.best=Math.max(state.best,G.combo);
    const money=G.awardBrick?G.awardBrick(b,depth):G.reward(b.type,G.combo);state.coins+=money;G.shotMoney+=money;G.burst(b.x,b.y,G.colors[b.type],16);G.float(b.x,b.y,'+'+G.fmt(money));G.shake=Math.min(8,G.shake+1.5);
    if(b.type!=='normal')G.specialSound(b.type,b.x);
    G.sound('break',G.combo,b.x);
    if(G.killed>=G.threshold&&!G.core)G.spawnCore();
    const near=(range)=>G.bricks.filter(t=>Math.hypot(t.x-b.x,t.y-b.y)<range);
    const effect=G.specialConfig?.(b.type)||{};
    if(depth<70){
      if(b.type==='bomb'){const radius=effect.radius||132;G.ring(b.x,b.y,'#ed8b6e',radius+4);G.shake=9;near(radius).forEach(t=>G.hit(t,G.damage()*(effect.damage||2),depth+1));}
      if(b.type==='lightning'){const targets=[...G.bricks].sort((a,c)=>Math.hypot(a.x-b.x,a.y-b.y)-Math.hypot(c.x-b.x,c.y-b.y)).slice(0,effect.links||5);let prev=b;targets.forEach(t=>{G.bolts.push({x:prev.x,y:prev.y,tx:t.x,ty:t.y,life:.4});prev=t;G.hit(t,G.damage()*(effect.damage||1.6),depth+1);});}
      if(b.type==='frost'){const radius=effect.radius||140;G.ring(b.x,b.y,'#81bece',radius+4);near(radius).forEach(t=>{t.frozen=true;t.flash=.25;G.hit(t,G.damage()*(effect.damage||.5),depth+1);});}
      if(b.type==='prism'){G.ring(b.x,b.y,'#b399d8',55);const count=effect.shards||3;for(let i=0;i<count;i++){const a=-.9+i*1.8/(count-1);G.addArrow(b.x,b.y,Math.sin(a)*18,-Math.cos(a)*18,effect.pierce||2);}}
    }
    G.onBrickDestroyed?.(b,depth);
    G.ui();
  };
  G.addArrow=(x,y,vx,vy,pierce=G.penetration())=>{
    if(G.arrows.length>=64)return;
    const body=Bodies.circle(x,y,3,{frictionAir:.0005,isSensor:true,collisionFilter:{mask:0},label:'arrow'});Body.setVelocity(body,{x:vx,y:vy});Composite.add(engine.world,body);
    const arrow={body,life:0,pierce,hit:new Set(),overlap:new Set(),trail:[],damage:G.damage()};G.arrows.push(arrow);G.initAchievementArrow?.(arrow);return arrow;
  };
  G.shoot=(dx,dy)=>{
     if(G.paused||!['ready','flying'].includes(G.phase)||G.arrows.length>=64||G.time<G.nextShotAt)return false;
    const len=Math.hypot(dx,dy);if(len<10||dy<5)return false;
    const strength=Math.min(1,len/100),speed=G.speed()*(.56+.44*strength);
     if(G.phase==='ready'){G.combo=0;G.shotMoney=0;}
     G.shots++;G.shotTime=G.time;G.phase='flying';
     G.nextShotAt=G.time+1;
    G.addArrow(G.origin.x,G.origin.y,-dx/len*speed,-dy/len*speed);G.sound('shoot');G.ui();return true;
  };
  G.buy=key=>{
    if(!(key in state.up)||G.phase!=='ready'||G.paused)return false;
    const cost=G.cost(key);if(state.coins<cost)return false;
    state.coins-=cost;state.up[key]++;G.save();G.ui();G.sound('upgrade');G.toast?.('升级成功');return true;
  };
  // Slab intersection supplies an exact entry order and face normal for swept ricochets.
  G.sweep=(bounds,from,to,padding=2.5)=>{
    // Reject disjoint swept bounds before allocating slab-intersection state.
    if(Math.max(from.x,to.x)<bounds.min.x-padding||Math.min(from.x,to.x)>bounds.max.x+padding||Math.max(from.y,to.y)<bounds.min.y-padding||Math.min(from.y,to.y)>bounds.max.y+padding)return null;
    let enter=0,exit=1,normal={x:0,y:0};
    for(const axis of ['x','y']){
      const delta=to[axis]-from[axis],min=bounds.min[axis]-padding,max=bounds.max[axis]+padding;
      if(Math.abs(delta)<1e-8){if(from[axis]<min||from[axis]>max)return null;continue;}
      const t1=(min-from[axis])/delta,t2=(max-from[axis])/delta,near=Math.min(t1,t2),far=Math.max(t1,t2);
      if(near>enter){enter=near;normal={x:0,y:0};normal[axis]=delta>0?-1:1;}
      exit=Math.min(exit,far);if(enter>exit)return null;
    }
    if(exit<0||enter>1)return null;
    return {t:enter,normal};
  };
  G.reflectObstacle=(body,from,contact)=>{
    const p=body.position,v=body.velocity,n=contact.normal,dot=v.x*n.x+v.y*n.y;
    if(dot>=0)return false;
    Body.setPosition(body,{x:from.x+(p.x-from.x)*contact.t+n.x*.8,y:from.y+(p.y-from.y)*contact.t+n.y*.8});
    Body.setVelocity(body,{x:(v.x-2*dot*n.x)*.94,y:(v.y-2*dot*n.y)*.94});
    return true;
  };
  G.reflectWalls=(body,onBounce=()=>{})=>{
    const p=body.position;
    if(p.x<20||p.x>760){Body.setPosition(body,{x:Math.max(20,Math.min(760,p.x)),y:p.y});Body.setVelocity(body,{x:-body.velocity.x*.86,y:body.velocity.y});onBounce();}
    if(p.y<22){Body.setPosition(body,{x:p.x,y:22});Body.setVelocity(body,{x:body.velocity.x,y:Math.abs(body.velocity.y)*.85});onBounce();}
  };
  const previewBody=Bodies.circle(0,0,3,{frictionAir:.0005,isSensor:true,collisionFilter:{mask:0},label:'arrow'});
  Composite.add(previewEngine.world,previewBody);
  G.predictPath=(x,y,vx,vy,maxDistance=720)=>{
    // A separate world uses Matter's integration without touching live bodies or effects.
     const preview=previewEngine;preview.gravity.x=engine.gravity.x;preview.gravity.y=engine.gravity.y;preview.gravity.scale=engine.gravity.scale;preview.timing.timestamp=0;
    const body=previewBody;body.deltaTime=1000/60;body.force.x=0;body.force.y=0;body.torque=0;
    Body.setPosition(body,{x,y});Body.setAngle(body,0);Body.setAngularVelocity(body,0);Body.setVelocity(body,{x:vx,y:vy});
    const arrow={body,hit:new Set()},path=[{x,y}];let distance=0;
     const stepSeconds=G.physicsStep;
     for(let step=0;step<Math.ceil(4.5/stepSeconds)&&distance<maxDistance;step++){
       G.guideArrows?.(stepSeconds,[arrow]);
       const from={...body.position};Engine.update(preview,stepSeconds*1000);
      const collisions=(G.phasesObstacles?.()?[]:G.obstacles).map(o=>G.sweep(o.body.bounds,from,body.position)).filter(Boolean).sort((a,b)=>a.t-b.t);
      for(const contact of collisions)if(G.reflectObstacle(body,from,contact))break;
      G.reflectWalls(body);
      const p={...body.position};distance+=Math.hypot(p.x-from.x,p.y-from.y);path.push(p);
      for(const b of G.bricks)if(G.sweep(b.body.bounds,from,p))arrow.hit.add(b.body.id);
      if(p.y>G.H-20)break;
    }
    return path;
  };
  G.tick=dt=>{
    if(G.paused)return;
    G.time+=dt;
    if(G.phase==='entering'&&G.time>=G.boardEntrance.end){G.boardEntrance=null;G.phase='ready';G.ui();}
    if(G.phase==='clearing'&&G.time>=G.clearAt){G.generate();G.toast?.('LEVEL '+state.level+' · 新的局面');}
    if(G.phase==='flying'){
      G.beforePhysics?.(dt);
       for(const a of G.arrows){a.previousPosition??={x:0,y:0};a.previousPosition.x=a.body.position.x;a.previousPosition.y=a.body.position.y;}
       const bodies=[],bricksById=new Map();
       for(const b of G.bricks){bodies.push(b.body);bricksById.set(b.body.id,b);}
       for(const o of G.obstacles)bodies.push(o.body);
       if(G.core)bodies.push(G.core.body);
       Engine.update(engine,dt*1000);
      for(const a of [...G.arrows]){
        if(G.phase==='clearing')break;
         a.life+=dt;const p=a.body.position,from=a.previousPosition||p,trailPoint=a.trail.length>=14?a.trail.shift():{};trailPoint.x=p.x;trailPoint.y=p.y;a.trail.push(trailPoint);
         const collisions=[];for(const body of bodies){const contact=G.sweep(body.bounds,from,p);if(contact)collisions.push({body,contact});}collisions.sort((a,b)=>a.contact.t-b.contact.t);
        for(const {body,contact} of collisions){
          if(body.label==='obstacle'){
            if(G.phasesObstacles?.()){G.onPhaseObstacle?.(a,body.obstacle);continue;}
            if(!G.reflectObstacle(a.body,from,contact))continue;
            const {x,y}=a.body.position;
            a.trail=[];body.obstacle.flash=.2;G.burst(x,y,'#ced9a4',9,.7);G.ring(x,y,'#8a9480',27);G.shake=Math.max(G.shake,2);G.sound('ricochet',1,x);G.onRicochet?.(a);break;
          }
          if(a.overlap.has(body.id))continue;
          if(body.label==='core'){G.withArrow(a,()=>{G.awardCore?.(a);G.clear();});break;}
          if(!G.bricks.includes(body.brick))continue;
          a.overlap.add(body.id);a.hit.add(body.id);
          G.withArrow(a,()=>{if(G.projectileHit)G.projectileHit(body.brick,a);else G.hit(body.brick,a.damage);});a.pierce-=G.pierceCost(body);if(a.pierce<=0)break;
        }
        if(G.phase==='clearing')break;
        G.reflectWalls(a.body,()=>G.onRicochet?.(a));
        for(const id of a.overlap){
          const brick=bricksById.get(id);
          if(!brick||brick.hp<=0||!G.sweep(brick.body.bounds,a.body.position,a.body.position))a.overlap.delete(id);
        }
        if(a.pierce<=0||p.y>G.H-20||a.life>4.5){Composite.remove(engine.world,a.body);G.arrows=G.arrows.filter(v=>v!==a);}
      }
      if(G.phase==='flying'&&G.arrows.length===0&&!G.hasPendingEffects?.()){G.phase='ready';G.save();G.ui();}
    }
    let alive=0;
    for(const p of G.particles){p.x+=p.vx*dt*60;p.y+=p.vy*dt*60;p.vy+=dt*7;p.life-=dt;p.rot+=dt*3;if(p.life>0)G.particles[alive++]=p;}G.particles.length=alive;
    alive=0;for(const p of G.texts){p.y-=dt*25;p.life-=dt;if(p.life>0)G.texts[alive++]=p;}G.texts.length=alive;
    for(const items of [G.rings,G.bolts]){alive=0;for(const p of items){p.life-=dt;if(p.life>0)items[alive++]=p;}items.length=alive;}
    G.bricks.forEach(b=>b.flash=Math.max(0,b.flash-dt));G.obstacles.forEach(o=>o.flash=Math.max(0,o.flash-dt));G.shake=Math.max(0,G.shake-dt*28);G.coreFlash=Math.max(0,G.coreFlash-dt*1.4);
  };
  G.sound=()=>{};
  G.specialSound=(type,x=390)=>G.sound(type==='bomb'?'boom':type,1,x,true);
  G.reset=()=>{Object.assign(state,defaults());G.paused=false;G.generate();};
  G.generate(true);
})();
