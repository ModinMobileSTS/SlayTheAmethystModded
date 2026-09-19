(() => {
  'use strict';
  const G=window.Game;
  let serial=0;
  const entry=(id,name,bonus,description,test)=>({id,name,bonus,description,test});
  G.achievementCatalog=[
    entry('double','一箭四雕',.5,'同一支箭击碎 4 块砖',s=>s.kills>=4),
    entry('five','势如破竹',1,'同一支箭击碎 5 块砖',s=>s.kills>=5),
    entry('ten','十连绝响',2,'同一支箭击碎 10 块砖',s=>s.kills>=10),
    entry('twenty','一箭封神',3,'同一支箭击碎 20 块砖',s=>s.kills>=20),
    entry('bank','借壁杀招',.75,'反弹后击碎一块砖',s=>s.bankKill),
    entry('trick','几何大师',1.25,'累计反弹 3 次后击碎砖块',s=>s.trickKill),
    entry('chain','连锁导演',1,'同一支箭的连锁伤害击碎 3 块砖',s=>s.chain>=3),
    entry('ice','冰点粉碎',.75,'同一支箭击碎 2 块冻结砖',s=>s.frozen>=2),
    entry('mix','元素交响',1.5,'同一支箭击碎 3 种特殊砖',s=>s.types.size>=3),
    entry('gold','淘金高手',1,'同一支箭击碎 2 块金矿砖',s=>s.gold>=2),
    entry('air','超长滞空',1,'飞行 2 秒后仍能击碎砖块',s=>s.airKill),
    entry('core','终结艺术',2,'击碎至少 3 块砖后命中核心',s=>s.core&&s.kills>=3)
  ];
  const history=()=>{
    if(!G.state.achievements||typeof G.state.achievements!=='object'||Array.isArray(G.state.achievements))G.state.achievements={};
    return G.state.achievements;
  };
  history();
  G.initAchievementArrow=a=>{
    a.achievement={id:++serial,kills:0,chain:0,frozen:0,gold:0,types:new Set(),unlocked:new Set(),bounces:0,base:0,paid:0,mult:1,baseMult:1};
  };
  function evaluate(a){
    const s=a.achievement;
    for(const item of G.achievementCatalog){
      if(s.unlocked.has(item.id)||!item.test(s))continue;
      s.unlocked.add(item.id);s.mult+=item.bonus;
      const h=history();h[item.id]=(Number.isFinite(h[item.id])?h[item.id]:0)+1;
      G.achievementEvent={serial:(G.achievementEvent?.serial||0)+1,name:item.name,bonus:item.bonus,arrow:s.id};
      G.showAchievement?.(item,s);
      G.sound('upgrade');
    }
    G.latestAchievement=s;
    const total=Math.round(s.base*s.mult),delta=total-s.paid;s.paid=total;
    return delta;
  }
  G.awardBrick=(b,depth)=>{
    const a=G.activeArrow;
    if(!a)return G.reward(b.type,1);
    if(!a.achievement)G.initAchievementArrow(a);
    const s=a.achievement;s.kills++;s.chain+=depth>0?1:0;s.frozen+=b.frozen?1:0;s.gold+=b.type==='gold'?1:0;
    if(b.type!=='normal')s.types.add(b.type);
    s.bankKill ||= s.bounces>0;s.trickKill ||= s.bounces>=3;s.airKill ||= a.life>=2;
    s.baseMult=G.mult(s.kills);s.base+=G.reward(b.type,s.kills);
    return evaluate(a);
  };
  G.awardCore=a=>{a.achievement.core=true;const money=evaluate(a);G.state.coins+=money;G.shotMoney+=money;};
  const ricochet=G.onRicochet;
  G.onRicochet=a=>{if(!a.achievement)G.initAchievementArrow(a);a.achievement.bounces++;ricochet?.(a);};
  const shoot=G.shoot,generate=G.generate;
  G.shoot=(dx,dy)=>{
    const fresh=G.phase==='ready',old=G.latestAchievement,event=G.achievementEvent;
    if(fresh){G.latestAchievement=null;G.achievementEvent=null;}
    const result=shoot(dx,dy);
    if(!result&&fresh){G.latestAchievement=old;G.achievementEvent=event;}
    return result;
  };
  G.generate=restore=>{G.latestAchievement=null;G.achievementEvent=null;return generate(restore);};
  const reset=G.reset;
  G.reset=()=>{G.state.achievements={};G.latestAchievement=null;G.achievementEvent=null;reset();};
})();
