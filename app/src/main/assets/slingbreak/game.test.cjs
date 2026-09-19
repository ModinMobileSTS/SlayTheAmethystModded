const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const Matter=require('./vendor/matter.min.js');
const source=fs.readFileSync(__dirname+'/game.js','utf8');
function boot(saved){
  let storage=saved?JSON.stringify(saved):null;
  const context={Matter,console,window:{},matchMedia:()=>({matches:true}),localStorage:{getItem:()=>storage,setItem:(k,v)=>storage=v},document:{getElementById:()=>({})}};
  vm.createContext(context);vm.runInContext(source,context);
  return {G:context.window.Game,read:()=>JSON.parse(storage)};
}
test('prediction matches every live physics step through obstacle and wall ricochets',()=>{
  for(const [x,y,vx,vy] of [[390,970,-18,-16],[30,600,-23,-8],[750,40,20,-18],[390,36,3,-24],[300,540,22,0],[390,650,4,-25]]){
    const {G}=boot();Matter.Composite.clear(G.engine.world);G.bricks=[];G.obstacles=[];
    const body=Matter.Bodies.rectangle(390,540,84,44,{isStatic:true,label:'obstacle'});
    const obstacle={x:390,y:540,w:84,h:44,body,flash:0};body.obstacle=obstacle;G.obstacles.push(obstacle);Matter.Composite.add(G.engine.world,body);
    const worldBefore=G.engine.world.bodies.length,timeBefore=G.time;
    const path=G.predictPath(x,y,vx,vy,1800);
    assert.equal(G.engine.world.bodies.length,worldBefore);assert.equal(G.time,timeBefore);assert.equal(obstacle.flash,0);
    G.phase='flying';const arrow=G.addArrow(x,y,vx,vy);let bounces=0;G.onRicochet=()=>bounces++;
    for(const p of path.slice(1)){
      G.tick(G.physicsStep);
      assert.ok(Math.hypot(p.x-arrow.body.position.x,p.y-arrow.body.position.y)<1e-8,'predicted and live positions must match after every bounce');
    }
    assert.ok(bounces>0,'scenario must exercise a ricochet');
  }
});
test('60Hz WebView mode keeps prediction and live physics aligned',()=>{
  const {G}=boot();G.physicsStep=1/60;Matter.Composite.clear(G.engine.world);G.bricks=[];G.obstacles=[];
  const body=Matter.Bodies.rectangle(390,540,84,44,{isStatic:true,label:'obstacle'});
  const obstacle={x:390,y:540,w:84,h:44,body,flash:0};body.obstacle=obstacle;G.obstacles.push(obstacle);Matter.Composite.add(G.engine.world,body);
  const path=G.predictPath(390,970,-18,-16,1800);G.phase='flying';const arrow=G.addArrow(390,970,-18,-16);G.onRicochet=()=>{};
  for(const p of path.slice(1)){G.tick(G.physicsStep);assert.ok(Math.hypot(p.x-arrow.body.position.x,p.y-arrow.body.position.y)<1e-8);}
});
test('random levels retain all five specials, correct threshold, and no practical level cap',()=>{
  const {G}=boot();
   for(const level of [1,2,10,100,10000]){G.state.level=level;G.generate();assert.ok(G.bricks.length>=55);assert.ok(G.bricks.length<=68);assert.ok(G.bricks.every(b=>b.w===84&&b.h===44));assert.equal(G.threshold,Math.ceil(G.initial*.6));for(const kind of ['bomb','lightning','frost','prism','gold'])assert.ok(G.bricks.some(b=>b.type===kind));assert.ok(Number.isFinite(G.bonus()));}
});
test('coins scale with both level and bounded exponential chain',()=>{
  const {G}=boot();const base=G.reward('normal',1);assert.ok(G.reward('normal',5)>base);assert.ok(G.reward('normal',15)>G.reward('normal',5));assert.equal(G.mult(10000),12);G.state.level=10;assert.ok(G.reward('normal',1)>base);assert.equal(G.reward('gold',1),Math.round(3*Math.pow(10,1.1)*3));
});
test('core threshold unlocks, cleanup pays only its bonus, and cannot pay twice',()=>{
  const {G}=boot();G.bricks.forEach(b=>b.type='normal');
  for(let i=0;i<G.threshold;i++)G.hit(G.bricks[0],999);
  assert.ok(G.core);const before=G.state.coins,total=G.state.total,combo=G.combo,bonus=G.bonus(),level=G.state.level;
  G.clear();assert.equal(G.state.coins,before+bonus);assert.equal(G.state.total,total);assert.equal(G.combo,combo);assert.equal(G.bricks.length,0);assert.equal(G.state.level,level+1);G.clear();assert.equal(G.state.coins,before+bonus);
  assert.equal(G.obstacles.length,0);
});
test('upgrades are affordable-only and unavailable mid-shot',()=>{
  const {G}=boot();assert.equal(G.buy('arrow'),false);G.state.coins=1000;const price=G.cost('arrow');assert.equal(G.buy('arrow'),true);assert.equal(G.state.coins,1000-price);assert.equal(G.state.up.arrow,1);assert.ok(G.damage()>1);G.shoot(0,100);assert.equal(G.buy('power'),false);
});
test('fixed world keeps the sling beneath the lowest brick row with clear separation',()=>{
   const {G}=boot();assert.equal(G.H,1400);assert.equal(G.origin.y,970);
  const lowestRow=Math.max(...G.bricks.map(b=>b.y));
  assert.ok(G.origin.y-(lowestRow+14)>=20,'sling ring should clear the lowest brick row');
  G.bricks.forEach(b=>{b.type='normal';b.hp=1;});
  assert.equal(G.shoot(0,100),true);for(let i=0;i<700;i++)G.tick(1/120);
  assert.ok(G.killed>0,'arrow should hit bricks');assert.equal(G.phase,'ready');assert.equal(G.arrows.length,0);
});
test('board damage, core state, upgrades, and wallet survive reload',()=>{
  const {G,read}=boot();G.state.coins=500;G.buy('brick');G.bricks.forEach(b=>b.type='normal');for(let i=0;i<G.threshold;i++)G.hit(G.bricks[0],999);G.save();
  const loaded=boot(read()).G;assert.equal(loaded.bricks.length,G.bricks.length);assert.equal(loaded.killed,G.killed);assert.equal(loaded.state.coins,G.state.coins);assert.equal(loaded.state.up.brick,1);assert.ok(loaded.core);
  assert.equal(JSON.stringify(loaded.obstacles.map(o=>[o.x,o.y,o.w,o.h])),JSON.stringify(G.obstacles.map(o=>[o.x,o.y,o.w,o.h])));
});
test('barriers stay separated, avoid bricks and leave the core route open',()=>{
  const {G}=boot();
  for(let i=0;i<35;i++){
    G.state.level=i+1;G.generate();assert.ok(G.obstacles.length>=5&&G.obstacles.length<=8);
    assert.equal(G.initial,G.bricks.length);
    for(const o of G.obstacles){
      assert.ok(Math.abs(o.x-390)-o.w/2>=80);assert.ok(o.y+o.h/2<G.origin.y-130);
      assert.ok(G.bricks.every(b=>Math.abs(b.x-o.x)>=(b.w+o.w)/2||Math.abs(b.y-o.y)>=(b.h+o.h)/2));
      assert.ok(G.obstacles.every(other=>other===o||Math.hypot(other.x-o.x,other.y-o.y)>=104));
    }
  }
});
test('barriers reflect both faces, do not consume penetration or award coins, and survive repeated hits',()=>{
  for(const horizontal of [false,true]){
    const {G}=boot();G.bricks.forEach(b=>Matter.Composite.remove(G.engine.world,b.body));G.bricks=[];
    const o=G.obstacles[0],count=G.obstacles.length;
    for(let repeat=0;repeat<2;repeat++){
      G.phase='flying';G.addArrow(o.x+(horizontal?-o.w/2-12:0),o.y+(horizontal?0:o.h/2+12),horizontal?24:0,horizontal?0:-24);
      const arrow=G.arrows.at(-1);for(let i=0;i<4;i++)G.tick(1/120);
      assert.ok(horizontal?arrow.body.velocity.x<0:arrow.body.velocity.y>0,JSON.stringify({horizontal,repeat,obstacle:{x:o.x,y:o.y},position:arrow.body.position,velocity:arrow.body.velocity}));
      assert.equal(arrow.pierce,G.penetration());assert.equal(G.obstacles.length,count);
      assert.equal(G.state.coins,0);assert.equal(G.killed,0);assert.equal(G.combo,0);
      G.arrows.forEach(a=>Matter.Composite.remove(G.engine.world,a.body));G.arrows=[];
    }
  }
});
test('explosions and other specials never destroy or reward barriers',()=>{
  for(const type of ['bomb','lightning','frost','prism']){
    const {G}=boot();const original=G.obstacles.map(o=>o.body.id);G.bricks.forEach(b=>b.type='normal');
    const o=G.obstacles[0],b=G.bricks.reduce((a,b)=>Math.hypot(b.x-o.x,b.y-o.y)<Math.hypot(a.x-o.x,a.y-o.y)?b:a);b.type=type;G.hit(b,999);
    assert.deepEqual(G.obstacles.map(o=>o.body.id),original);assert.equal(G.killed,G.initial-G.bricks.length);
  }
});
test('old in-progress saves retain progress; untouched boards adopt the portrait layout',()=>{
  const {G,read}=boot();G.bricks.forEach(b=>b.type='normal');G.hit(G.bricks[0],999);G.save();
   const saved=read();delete saved.board.obstacles;delete saved.board.balanceVersion;delete saved.board.layoutVersion;
   const loaded=boot(saved).G;assert.equal(loaded.killed,1);assert.equal(loaded.state.coins,G.state.coins);assert.equal(loaded.bricks.length,G.bricks.length);
   saved.board.killed=0;const upgraded=boot(saved).G;assert.ok(upgraded.bricks.length>=55);assert.ok(upgraded.obstacles.length>=5);assert.ok(upgraded.bricks.every(b=>b.w===84&&b.h===44));
});
test('clearing reload advances exactly once and pause freezes time',()=>{
  const {G,read}=boot();G.spawnCore();const bonus=G.bonus();G.clear();const loaded=boot(read()).G;assert.equal(loaded.state.level,2);assert.equal(loaded.state.coins,bonus);assert.ok(loaded.bricks.length>0);const t=loaded.time;loaded.paused=true;loaded.tick(2);assert.equal(loaded.time,t);
});
test('special effects apply meaningful state changes',()=>{
  for(const type of ['bomb','lightning','frost','prism']){
    const {G}=boot();G.bricks.forEach(b=>{b.type='normal';b.hp=b.max=(type==='frost'?100:1);});const target=G.bricks.find(b=>G.bricks.some(t=>t!==b&&Math.hypot(t.x-b.x,t.y-b.y)<78));assert.ok(target);target.type=type;G.hit(target,999);
    if(type==='bomb'||type==='lightning')assert.ok(G.killed>1);
    if(type==='frost')assert.ok(G.bricks.some(b=>b.frozen&&b.hp<b.max));
    if(type==='prism'){assert.equal(G.arrows.length,3);assert.ok(G.arrows.every(a=>a.pierce===2));}
  }
});
test('special quotas include guaranteed types and never exceed 22 percent at any upgrade tier',()=>{
  const {G}=boot();let previous=0;
  for(const tier of [0,1,5,10,20,50,100,1000]){
    G.state.up.brick=tier;const rate=G.specialRate();assert.ok(rate>=previous&&rate<=.22);previous=rate;
    if(tier===0)assert.equal(rate,.1);
    for(let sample=0;sample<8;sample++){
      G.state.level=sample+1;G.generate();const special=G.bricks.filter(b=>b.type!=='normal');
      assert.equal(special.length,Math.floor(G.initial*rate));assert.ok(special.length/G.initial<=.22);
      for(const type of ['bomb','lightning','frost','prism','gold'])assert.ok(special.some(b=>b.type===type));
    }
  }
});
test('penetration grows once per four upgrades, caps at six, and damage still progresses',()=>{
  const {G}=boot();
  for(const [tier,pierce] of [[0,2],[3,2],[4,3],[8,4],[12,5],[16,6],[50,6]]){
    G.state.up.arrow=tier;assert.equal(G.penetration(),pierce);assert.equal(G.damage(),2+.65*Math.log2(tier+1));
  }
});
test('frost bricks are pass-through and do not consume arrow penetration',()=>{
  const {G}=boot();
  const frost=G.bricks[0];frost.type='frost';
  const normal=G.bricks[1];normal.type='normal';
  assert.equal(G.pierceCost(frost.body),0);assert.equal(G.pierceCost(normal.body),1);
  const arrow={pierce:2};arrow.pierce-=G.pierceCost(frost.body);assert.equal(arrow.pierce,2);
  arrow.pierce-=G.pierceCost(normal.body);assert.equal(arrow.pierce,1);
});
test('a two-pierce arrow destroys a frost brick plus two aligned normal bricks',()=>{
  const {G}=boot();
  G.bricks.slice(3).forEach(b=>Matter.Composite.remove(G.engine.world,b.body));G.bricks=G.bricks.slice(0,3);
  G.bricks.forEach((b,i)=>{Matter.Body.setPosition(b.body,{x:390,y:500-i*46});b.x=390;b.y=500-i*46;b.hp=b.max=1;b.type=i===0?'frost':'normal';});
  G.initial=G.bricks.length;G.threshold=99;G.shoot(0,100);
  for(let i=0;i<700&&G.phase==='flying';i++)G.tick(1/120);
  assert.equal(G.killed,3);assert.equal(G.combo,3);assert.equal(G.arrows.length,0);
});
test('an arrow can hit the same brick again after leaving it',()=>{
  const {G}=boot();const {Bodies,Composite}=Matter;
  G.engine.gravity.y=0;G.bricks.forEach(b=>Composite.remove(G.engine.world,b.body));G.bricks=[];
  G.obstacles.forEach(o=>Composite.remove(G.engine.world,o.body));G.obstacles=[];
  const body=Bodies.rectangle(390,500,84,44,{isStatic:true,label:'brick'});
  const b={x:390,y:500,w:84,h:44,hp:100,max:100,type:'normal',frozen:false,body,flash:0,id:Math.random()};
  body.brick=b;G.bricks.push(b);Composite.add(G.engine.world,body);
  G.phase='flying';G.addArrow(300,500,24,0,20);
  for(let i=0;i<240&&G.arrows.length;i++)G.tick(1/120);
  assert.equal((100-b.hp)/2,3);
});
test('plain arrows stop after exactly two brick contacts at the initial tier',()=>{
  const {G}=boot();G.bricks.forEach(b=>{b.type='normal';b.hp=1;});G.shoot(0,100);
  for(let i=0;i<600&&G.phase==='flying';i++)G.tick(1/120);
  assert.equal(G.killed,2);assert.equal(G.combo,2);assert.equal(G.arrows.length,0);
});
test('portrait boards reload without rerolling',()=>{
  const {G,read}=boot();G.state.level=20;G.generate();const original=JSON.stringify(read().board);
   assert.ok(G.initial>=62);const loaded=boot(read());assert.equal(JSON.stringify(loaded.read().board),original);
});
test('legacy special-heavy boards are capped while preserving damage, currency and progress',()=>{
  const {G,read}=boot();G.state.coins=321;G.killed=1;G.bricks[0].hp=.5;G.bricks.forEach(b=>b.type='bomb');G.save();
  const saved=read();delete saved.board.balanceVersion;const loaded=boot(saved).G;
   assert.equal(loaded.state.coins,321);assert.equal(loaded.killed,1);assert.ok(loaded.bricks.every(b=>b.w===84&&b.h===44));
  assert.equal(loaded.bricks.filter(b=>b.type!=='normal').length,Math.floor(loaded.initial*loaded.specialRate()));
});
test('all special destructions and core trigger dedicated priority audio, never on a nonlethal hit',()=>{
  for(const [type,cue] of [['bomb','boom'],['lightning','lightning'],['frost','frost'],['prism','prism'],['gold','gold']]){
    const {G}=boot(),events=[];G.bricks.forEach(b=>b.type='normal');const b=G.bricks[0];b.type=type;b.hp=b.max=10;G.sound=(...args)=>events.push(args);
    G.hit(b,1);assert.ok(!events.some(e=>e[3]));G.hit(b,100,100);assert.equal(events.filter(e=>e[0]===cue&&e[2]===b.x&&e[3]===true).length,1);G.hit(b,100);assert.equal(events.filter(e=>e[0]===cue&&e[3]===true).length,1);
  }
  const {G}=boot(),events=[];G.sound=(...args)=>events.push(args);G.clear();assert.equal(events.filter(e=>e[0]==='win'&&e[3]===true).length,1);
});
