const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const Matter=require('./vendor/matter.min.js');
function boot(saved){
  let storage=saved?JSON.stringify(saved):null,seed=9128;
  const math=Object.create(Math);math.random=()=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed/4294967296;};
  const context={Matter,Math:math,console,window:{},matchMedia:()=>({matches:true}),localStorage:{getItem:()=>storage,setItem:(k,v)=>storage=v},document:{getElementById:()=>({})}};
  vm.createContext(context);for(const file of ['game.js','skills.js','skill-expansion.js'])vm.runInContext(fs.readFileSync(__dirname+'/'+file,'utf8'),context);
  const G=context.window.Game;G.burst=G.float=G.ring=()=>{};return{G,read:()=>JSON.parse(storage)};
}
function ready(id){const result=boot(),G=result.G;G.state.skills=id?{[id]:1}:{};G.state.skillChosenLevel=G.state.level;G.state.draft=null;G.phase='ready';return result;}
function finish(G){for(let i=0;i<1800&&G.phase==='flying';i++)G.tick(1/120);assert.notEqual(G.phase,'flying');if(G.time<G.nextShotAt)G.tick(G.nextShotAt-G.time);}
function park(G){for(const a of G.arrows){Matter.Body.setPosition(a.body,{x:390,y:1350});Matter.Body.setVelocity(a.body,{x:0,y:0});}}
function armor(G){G.bricks.forEach(b=>{b.type='normal';b.hp=b.max=100;b.frozen=false;});}
test('81 single-level choices, stable drafts and one selection per level',()=>{
  const {G,read}=boot();assert.equal(G.skillCatalog.length,81);assert.ok(G.skillCatalog.every(s=>s.max===1));assert.equal(G.phase,'draft');assert.equal(G.shoot(0,100),false);assert.equal(G.buy('arrow'),false);
  assert.equal(JSON.stringify(boot(read()).G.state.draft),JSON.stringify(G.state.draft));
  const id=G.state.draft.options[0];assert.equal(G.chooseSkill('invalid'),false);assert.equal(G.chooseSkill(id),true);assert.equal(G.chooseSkill(id),false);assert.equal(Object.keys(G.state.skills).length,1);assert.equal(boot(read()).G.skillRank(id),1);
});
test('clear awards current skill bonus once, expires it immediately and survives transition reload',()=>{
  const {G,read}=ready('treasury');G.save();assert.equal(G.bonus(),720);G.clear();assert.equal(G.state.coins,720);assert.equal(G.skillRank('treasury'),0);assert.equal(Object.keys(G.state.skills).length,0);assert.equal(G.settledBonus,720);G.clear();assert.equal(G.state.coins,720);
  const loaded=boot(read()).G;assert.equal(loaded.state.level,2);assert.equal(loaded.phase,'draft');assert.equal(loaded.state.coins,720);assert.equal(Object.keys(loaded.state.skills).length,0);
});
test('same skill can be selected in another level without stacking',()=>{
  const {G}=ready('titan');assert.equal(G.damage(),4);G.clear();G.tick(2.1);
  G.state.draft={level:2,options:['titan','mint','prism']};G.prepareDraft();assert.equal(G.chooseSkill('titan'),true);assert.equal(G.skillRank('titan'),1);assert.equal(G.damage(),4);
});
test('legacy accumulated skills are removed without losing wallet, gear or board progress',()=>{
  const {G,read}=ready('mint');G.state.coins=678;G.state.up.arrow=10;G.killed=4;G.save();const old=read();delete old.skillScopeVersion;old.skills={titan:50,mint:10,ice:2};
  const loaded=boot(old).G;assert.equal(loaded.state.coins,678);assert.equal(loaded.state.up.arrow,10);assert.equal(loaded.killed,4);assert.equal(loaded.phase,'draft');assert.equal(Object.keys(loaded.state.skills).length,0);assert.ok(loaded.damage()<5);
});
test('decay is applied once within its level, expires before the next board',()=>{
  const {G,read}=ready('decay');const before=G.bricks[0].hp;G.prepareDraft();assert.equal(G.bricks[0].hp,before*.65);assert.equal(boot(read()).G.bricks[0].hp,G.bricks[0].hp);
  G.clear();G.tick(2.1);assert.ok(G.bricks.every(b=>b.hp===b.max));assert.equal(G.state.skillRuntime.decay,false);
});
test('resonance threshold returns to normal on the next level',()=>{
  const {G}=ready('resonance');G.prepareDraft();assert.equal(G.threshold,Math.ceil(G.initial*.45));G.clear();G.tick(2.1);assert.equal(G.threshold,Math.ceil(G.initial*.6));
});
test('economy passives apply only this level but earned gear and coins persist',()=>{
  const {G,read}=ready('forge');G.state.coins=10000;G.buy('arrow');assert.equal(G.state.up.arrow,3);const loaded=boot(read()).G;loaded.buy('arrow');assert.equal(loaded.state.up.arrow,4);loaded.clear();loaded.tick(2.1);loaded.chooseSkill(loaded.state.draft.options.find(id=>id!=='forge'));loaded.buy('arrow');assert.equal(loaded.state.up.arrow,5);
  assert.equal(ready('mint').G.reward('normal',1),6);assert.equal(ready('alchemist').G.reward('gold',1),24);assert.equal(ready('bargain').G.cost('arrow'),60);
});
test('every single passive runs a shot without deadlocking or exceeding projectile budget',()=>{
  for(const skill of boot().G.skillCatalog){const {G}=ready(skill.id);G.prepareDraft();G.shoot(0,100);let peak=0;for(let i=0;i<1800&&G.phase==='flying';i++){G.tick(1/120);peak=Math.max(peak,G.arrows.length);}assert.notEqual(G.phase,'flying',skill.id);assert.ok(peak<=64);assert.ok(Number.isFinite(G.state.coins));}
});
test('ice applies exactly double damage, rage caps, and ricochet grows additively',()=>{
  const ice=ready('ice').G;armor(ice);ice.projectileHit(ice.bricks[0],{damage:1});assert.equal(ice.bricks[0].hp,98);
  const rage=ready('rage').G;armor(rage);rage.combo=500;rage.projectileHit(rage.bricks[0],{damage:2});assert.equal(rage.bricks[0].hp,95);
  const kinetic=ready('ricochet').G,a=kinetic.addArrow(390,600,0,-24);for(let i=0;i<20;i++)kinetic.onRicochet(a);assert.equal(a.rebounds,4);assert.equal(a.pierce,10);assert.equal(a.damage,3.2);
});
test('execution threshold is 25 percent and crits deal triple rather than fourfold damage',()=>{
  const G=ready('execute').G;armor(G);const b=G.bricks[0];G.projectileHit(b,{damage:50});assert.equal(b.hp,50);G.projectileHit(b,{damage:25});assert.ok(!G.bricks.includes(b));
  const critical=ready('critical').G;armor(critical);let crits=0;for(const b of critical.bricks.slice(0,60)){critical.projectileHit(b,{damage:2});assert.ok(b.hp===98||b.hp===94);if(b.hp===94)crits++;}assert.ok(crits>0&&crits<60);
});
test('pulse and legion trigger every third shot and counters persist',()=>{
  const {G,read}=ready('pulse');armor(G);G.shoot(0,100);finish(G);const loaded=boot(read()).G;
  loaded.shoot(0,100);finish(loaded);assert.equal(loaded.state.skillRuntime.shots,2);
   const target=loaded.bricks.find(b=>b.x===102),hp=target.hp;loaded.shoot(0,100);park(loaded);finish(loaded);assert.ok(Math.abs(target.hp-(hp-loaded.damage()*1.2))<1e-8);
  const legion=ready('legion').G;armor(legion);for(let i=1;i<=3;i++){legion.shoot(0,100);assert.equal(legion.arrows.length,i===3?7:1);finish(legion);}
});
test('slow damage upgrades leave typical mid and late game bricks needing multiple hits',()=>{
  const G=ready().G;for(const [level,tier] of [[1,0],[5,10],[10,15],[25,22],[100,35],[1000,50]]){G.state.level=level;G.state.up.arrow=tier;assert.ok(G.damage()<G.baseHp(),`${level}/${tier}`);assert.ok(Math.ceil(G.baseHp()/G.damage())<=6);}
});
test('reset clears the level skill and reopens the first draft',()=>{const G=ready('titan').G;G.reset();assert.equal(G.state.level,1);assert.equal(Object.keys(G.state.skills).length,0);assert.equal(G.phase,'draft');});
test('ordinary homing prioritizes an exposed nearby core instead of steering away from it',()=>{
  const G=ready('seeking').G;G.spawnCore();const arrow=G.addArrow(340,G.core.y,20,0);G.beforePhysics(.1);assert.ok(Math.abs(arrow.body.velocity.y)<1e-8);assert.ok(arrow.body.velocity.x>0);
});
test('weighted draws are unique and observed inclusion matches displayed probabilities',()=>{
  const {G}=boot(),counts=Object.fromEntries(G.skillCatalog.map(s=>[s.id,0])),n=60000;
  assert.equal(new Set(G.skillCatalog.map(s=>s.weight)).size,75);
  assert.ok(Math.abs(G.skillCatalog.reduce((sum,s)=>sum+s.chance,0)-3)<1e-10);
  for(let i=0;i<n;i++){const options=G.rollSkills();assert.equal(new Set(options).size,3);for(const id of options)counts[id]++;}
  for(const s of G.skillCatalog){assert.ok(s.tier in G.skillTiers);assert.ok(Math.abs(counts[s.id]/n-s.chance)<.006,s.id);}
  for(const [common,rare] of [['white','blue'],['blue','gold']])assert.ok(Math.min(...G.skillCatalog.filter(s=>s.tier===common).map(s=>s.chance))>Math.max(...G.skillCatalog.filter(s=>s.tier===rare).map(s=>s.chance)));
});
test('new arrow builds deliver their advertised damage, cadence and projectile counts',()=>{
  const rapid=ready('rapid').G;rapid.shoot(0,100);assert.equal(rapid.nextShotAt,.18);assert.equal(rapid.arrows[0].damage,2.7);
  const heavy=ready('heavy').G;assert.equal(heavy.damage(),3.6);assert.equal(heavy.penetration(),4);
  const rail=ready('railgun').G;assert.equal(rail.damage(),8);assert.equal(rail.penetration(),14);
  const swarm=ready('swarmqueen').G;swarm.shoot(0,100);assert.equal(swarm.arrows.length,9);assert.equal(swarm.arrows.filter(a=>a.homing&&a.damage===3&&a.pierce===3).length,8);
  const growing=ready('growing').G;armor(growing);for(let i=1;i<=12;i++){growing.shoot(0,100);assert.equal(growing.arrows[0].damage,2*(1+Math.min(i,10)*.2));finish(growing);}
});
test('poison, delayed bombs, crossfire and kill-spawn effects execute',()=>{
  for(const [id,expected] of [['poison',93.2],['doubletap',94],['crossfire',92]]){
    const G=ready(id).G;armor(G);G.shoot(0,100);const b=G.bricks[0];G.projectileHit(b,{damage:2});for(let i=0;i<65;i++)G.tick(1/120);assert.ok(Math.abs(b.hp-expected)<1e-8,id);
  }
  const G=ready('nova').G;armor(G);G.shoot(0,100);G.hit(G.bricks[0],100);for(let i=0;i<12;i++)G.tick(1/120);assert.equal(G.arrows.filter(a=>a.homing).length,3);
});
test('snowburst freezes five targets and supernova bursts only on the fourth shot',()=>{
  const ice=ready('snowburst').G;armor(ice);ice.shoot(0,100);park(ice);for(let i=0;i<35;i++)ice.tick(1/120);assert.equal(ice.bricks.filter(b=>b.frozen).length,5);assert.ok(ice.bricks.filter(b=>b.frozen).every(b=>b.hp===94));
  const G=ready('supernova').G;armor(G);const b=G.bricks[0];for(let i=1;i<=4;i++){G.shoot(0,100);park(G);finish(G);assert.equal(b.hp,i===4?85:100);}
});
test('conditional damage rewards fresh targets, damaged targets and a fully drawn bow',()=>{
  const ambush=ready('ambush').G;armor(ambush);const b=ambush.bricks[0];ambush.projectileHit(b,{damage:2});assert.equal(b.hp,94);ambush.projectileHit(b,{damage:2});assert.equal(b.hp,92);
  const follow=ready('opportunist').G;armor(follow);const c=follow.bricks[0];follow.projectileHit(c,{damage:2});assert.equal(c.hp,98);follow.projectileHit(c,{damage:2});assert.equal(c.hp,92);assert.equal(follow.penetration(),4);
  for(const [power,damage,pierce] of [[94,2,2],[95,6,8],[100,6,8]]){const G=ready('sharpshooter').G;G.shoot(0,power);assert.equal(G.arrows[0].damage,damage);assert.equal(G.arrows[0].pierce,pierce);}
});
test('siphon refunds penetration and grows additively for only eight direct kills',()=>{
  const G=ready('siphon').G;armor(G);G.phase='flying';const a=G.addArrow(390,1000,0,-20);G.bricks.forEach(b=>b.hp=1);
  for(let i=0;i<12;i++)G.projectileHit(G.bricks[0],a);
  assert.equal(a.siphonKills,8);assert.equal(a.pierce,10);assert.equal(a.damage,6);
});
test('bankshot splits at most three times and children never split again',()=>{
  const G=ready('bankshot').G,a=G.addArrow(390,1000,0,-20);
  for(let i=0;i<10;i++)G.onRicochet(a);assert.equal(G.arrows.length,7);assert.equal(a.bankshots,3);
  for(const child of G.arrows.slice(1)){assert.ok(child.bankEcho&&child.homing);assert.equal(child.damage,4);assert.equal(child.pierce,2);G.onRicochet(child);}
  assert.equal(G.arrows.length,7);
  const mirror=ready('mirror').G;mirror.shoot(0,100);assert.equal(mirror.arrows.length,5);assert.deepEqual([...new Set(mirror.arrows.slice(1).map(a=>a.body.position.x))],[36,744]);
});
test('minefield fires three pulses once per arrow and frostfire doubles its blast',()=>{
  const G=ready('minefield').G;armor(G);G.shoot(0,100);const a=G.arrows[0],b=G.bricks[0];park(G);
  G.projectileHit(b,a);G.projectileHit(b,a);for(let i=0;i<70;i++)G.tick(1/120);assert.equal(b.hp,84);
  const ice=ready('frostfire').G;armor(ice);ice.shoot(0,100);park(ice);const target=ice.bricks[0];ice.projectileHit(target,ice.arrows[0]);for(let i=0;i<20;i++)ice.tick(1/120);assert.ok(target.frozen);assert.equal(target.hp,92);
});
test('orbital targets the densest cluster and stormfront affects exactly three rows',()=>{
  const G=ready('orbital').G;armor(G);const target=G.bricks.reduce((best,b)=>{const count=G.bricks.filter(t=>Math.hypot(t.x-b.x,t.y-b.y)<160).length;return count>best.count?{b,count}:best;},{count:-1}).b;
  G.shoot(0,100);park(G);finish(G);assert.equal(target.hp,82);assert.ok(G.bricks.filter(b=>Math.hypot(target.x-b.x,target.y-b.y)>=160).every(b=>b.hp===100));
  const storm=ready('stormfront').G;armor(storm);storm.shoot(0,100);park(storm);finish(storm);const hit=storm.bricks.filter(b=>b.hp<100);assert.equal(new Set(hit.map(b=>b.y)).size,3);assert.ok(hit.every(b=>b.hp===94));
});
test('reaper executes only on the third shot and chooses the four weakest bricks',()=>{
  const G=ready('reaper').G;armor(G);const targets=G.bricks.slice(0,4);targets.forEach((b,i)=>b.hp=i+1);const count=G.bricks.length;
  for(let i=1;i<=3;i++){G.shoot(0,100);park(G);finish(G);assert.equal(G.bricks.length,count-(i===3?4:0));}assert.ok(targets.every(b=>!G.bricks.includes(b)));assert.equal(G.damage(),3.2);
});
test('roulette reaches all three modes and applies each mode correctly',()=>{
  const G=ready('roulette').G,seen=new Set();armor(G);
  for(let i=0;i<45;i++){
    G.shoot(0,100);const a=G.arrows[0],mode=a.rouletteMode;seen.add(mode);const hp=G.bricks[0].hp;
    if(mode===0){assert.equal(a.damage,10);assert.equal(a.pierce,12);assert.equal(G.arrows.length,1);}
    if(mode===1){assert.equal(G.arrows.length,9);assert.ok(G.arrows.slice(1).every(a=>a.homing&&a.damage===4));}
    park(G);finish(G);assert.equal(G.bricks[0].hp,hp-(mode===2?5:0));
  }
  assert.equal(seen.size,3);
});
test('thunder lottery sometimes adds six triple-damage arcs and bounty caps at fivefold',()=>{
  const G=ready('thunderlottery').G;armor(G);let wins=0;
  for(let i=0;i<25;i++){G.bricks.forEach(b=>b.hp=100);G.shoot(0,100);park(G);G.projectileHit(G.bricks[0],G.arrows[0]);finish(G);const hit=G.bricks.filter(b=>b.hp<=94);if(hit.length){wins++;assert.equal(hit.length,6);}}
  assert.ok(wins>0&&wins<25);
  const bounty=ready('bounty').G,plain=ready().G;for(const n of [1,2,9,20])assert.equal(bounty.reward('normal',n),Math.round(plain.reward('normal',n)*(1+Math.min(8,n-1)*.5)));
  const special=ready('specialist').G;armor(special);const b=special.bricks[0];b.type='gold';special.projectileHit(b,{damage:2});assert.equal(b.hp,92);assert.equal(special.reward('gold',1),plain.reward('gold',1)*3);assert.equal(special.reward('normal',1),plain.reward('normal',1));
});
