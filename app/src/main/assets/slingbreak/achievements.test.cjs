const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const Matter=require('./vendor/matter.min.js');
function boot(skill){
  let stored;
  const c={Matter,console,window:{},matchMedia:()=>({matches:true}),localStorage:{getItem:()=>null,setItem:(k,v)=>stored=v},document:{getElementById:()=>({})}};
  vm.createContext(c);for(const f of ['game.js','skills.js','achievements.js'])vm.runInContext(fs.readFileSync(__dirname+'/'+f,'utf8'),c);
  const G=c.window.Game;G.state.skills=skill?{[skill]:1}:{};G.state.skillChosenLevel=1;G.phase='ready';G.threshold=999;
  G.bricks.forEach(b=>{b.type='normal';b.hp=b.max=1;});
  return {G,read:()=>JSON.parse(stored)};
}
const arrow=G=>G.addArrow(390,970,0,-20,20);
const kill=(G,a,depth=0)=>G.withArrow(a,()=>G.hit(G.bricks[0],100,depth));
test('concurrent arrows have independent combos and retroactive achievement payouts',()=>{
  const {G}=boot(),a=arrow(G),b=arrow(G);
  kill(G,a);kill(G,b);assert.equal(G.state.coins,6);assert.equal(a.achievement.mult,1);assert.equal(b.achievement.mult,1);
  kill(G,a);assert.equal(a.achievement.mult,1);assert.equal(a.achievement.paid,6);assert.equal(G.state.coins,9);assert.equal(b.achievement.kills,1);
  kill(G,a);assert.equal(a.achievement.mult,1);assert.equal(a.achievement.kills,3);
  kill(G,a);assert.equal(a.achievement.mult,1.5);assert.equal(G.state.achievements.double,1);
  kill(G,b);kill(G,b);kill(G,b);assert.equal(G.state.achievements.double,2);
  assert.equal(G.state.coins,a.achievement.paid+b.achievement.paid);
});
test('ricochet, frozen, chain and long-flight bonuses combine once per arrow',()=>{
  const {G}=boot(),a=arrow(G);a.life=2.1;
  for(let i=0;i<3;i++)G.onRicochet(a);
  for(let i=0;i<4;i++){G.bricks[0].frozen=true;kill(G,a,1);}
  for(const id of ['bank','trick','ice','chain','air','double'])assert.equal(G.state.achievements[id],1,id);
  assert.equal(a.achievement.mult,6.25);assert.equal(G.state.coins,Math.round(a.achievement.base*6.25));
});
test('split arrows get fresh achievements, chained kills stay with the parent',()=>{
  const {G}=boot(),a=arrow(G);G.bricks[0].type='prism';kill(G,a);
  const child=G.arrows.at(-1);assert.notEqual(a.achievement.id,child.achievement.id);assert.equal(child.achievement.kills,0);
  kill(G,child);assert.equal(a.achievement.kills,1);assert.equal(child.achievement.kills,1);
});
test('delayed explosions retain their originating arrow even after another shot',()=>{
  const {G}=boot('doubletap');G.shoot(0,100);const a=G.arrows[0];
  G.withArrow(a,()=>G.projectileHit(G.bricks[0],a));
  G.time=1.1;G.shoot(0,100);const b=G.arrows.at(-1);
  for(const projectile of G.arrows){Matter.Body.setPosition(projectile.body,{x:390,y:1300});Matter.Body.setVelocity(projectile.body,{x:0,y:0});}
  G.tick(.01);assert.ok(a.achievement.kills>1);assert.equal(b.achievement.kills,0);assert.equal(G.activeArrow,undefined);
});
test('core achievement backpay is separate from core bonus and persists, reset clears it',()=>{
  const {G,read}=boot(),a=arrow(G);for(let i=0;i<3;i++)kill(G,a);
  const bonus=G.bonus();G.awardCore(a);G.clear();
  assert.equal(G.state.coins,a.achievement.paid+bonus);assert.equal(read().achievements.core,1);
  G.reset();assert.equal(Object.keys(G.state.achievements).length,0);assert.equal(G.latestAchievement,null);
});
test('all skills remain playable with per-arrow scoring and bounded projectiles',()=>{
  for(const skill of boot().G.skillCatalog){
    const {G}=boot(skill.id);G.prepareDraft();G.shoot(0,100);
    for(let i=0;i<1500&&G.phase==='flying';i++){G.tick(1/120);assert.ok(G.arrows.length<=64);}
    assert.notEqual(G.phase,'flying',skill.id);assert.ok(Number.isFinite(G.state.coins),skill.id);
  }
});
test('cascade kills remain attributed to the launching arrow and stop scheduling',()=>{
  const {G}=boot('cascade');G.shoot(0,100);const a=G.arrows[0];kill(G,a);
  Matter.Body.setPosition(a.body,{x:390,y:1350});Matter.Body.setVelocity(a.body,{x:0,y:0});
  for(let i=0;i<900&&G.phase==='flying';i++)G.tick(1/120);
  assert.ok(a.achievement.chain>=3);assert.equal(G.state.coins,a.achievement.paid);assert.equal(G.hasPendingEffects(),false);assert.equal(G.activeArrow,undefined);
});
test('bankshot children get independent achievement records',()=>{
  const {G}=boot('bankshot'),a=arrow(G);G.onRicochet(a);const child=G.arrows[1];
  assert.notEqual(child.achievement.id,a.achievement.id);kill(G,child);assert.equal(a.achievement.kills,0);assert.equal(child.achievement.kills,1);assert.equal(child.achievement.bounces,0);
});
