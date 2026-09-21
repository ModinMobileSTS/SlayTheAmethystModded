(() => {
  'use strict';
  const G=window.Game,S=G.state,{Body}=Matter;
  const entry=(id,name,family,icon,max,describe)=>({id,name,family,icon,max:1,describe});
  const catalog=G.skillCatalog=[
    entry('trident','三叉齐射','箭术','git-fork',3,r=>`每次射击额外发射 5 支扇形箭，继承全部伤害与穿透。`),
    entry('echo','回响齐射','箭术','copy',2,r=>`松弦后自动重放 ${r} 次齐射，完整复制主箭与扇形箭。`),
    entry('titan','泰坦之力','力量','biceps-flexed',1,()=>`本关所有箭矢与伤害型被动的基础伤害提高 100%。`),
    entry('piercer','贯星长矛','箭术','move-up-right',4,r=>`主箭与齐射箭额外穿透 ${r*4} 块砖，突破普通升级的穿透上限。`),
    entry('meteor','流星雨','元素','cloud-lightning',3,r=>`每次射击召唤 6 支自上而下的流星箭，造成 2 倍伤害。`),
    entry('hunters','追猎蜂群','箭术','crosshair',3,r=>`每次射击额外释放 4 支追踪箭，自动转向附近砖块。`),
    entry('seeking','必中引导','箭术','scan-line',2,r=>`所有箭矢自动追踪前方 ${180+r*70} 范围内的砖块，优先锁定特殊块。`),
    entry('ricochet','动能回收','箭术','corner-up-right',1,()=>`反弹恢复 2 次穿透，并增加初始箭伤的 15%；每支箭最多触发 4 次。`),
    entry('blast','爆裂箭头','元素','bomb',3,r=>`每次直接击碎都引发爆炸，对附近砖块造成 1.5 倍伤害。`),
    entry('lightning','连锁电容','元素','zap',3,r=>`每次命中额外电击最近 3 块砖，造成 1.2 倍伤害。`),
    entry('ice','永冻箭簇','元素','snowflake',1,()=>`每次命中冻结周围 95 范围内的砖块；冰冻使后续伤害变为 2 倍，不再额外叠乘。`),
    entry('shatter','碎冰风暴','元素','sparkles',3,r=>`冻结砖块碎裂后爆发冰震，对邻近砖块造成 1.5 倍伤害，每箭最多 ${r*6} 次。`),
    entry('critical','致命准星','力量','focus',1,()=>`箭矢有 50% 概率暴击，造成 3 倍伤害。`),
    entry('execute','斩灭法则','力量','scissors',1,()=>`箭命中后剩余生命不高于 25% 的砖块立即被处决。`),
    entry('rage','连击狂热','力量','flame',1,()=>`本箭每击碎 4 块砖，后续箭矢伤害提高 25%，最多提高 150%。`),
    entry('sweep','横扫千军','力量','arrow-left-right',2,r=>`每箭前 ${r} 次命中横扫目标所在整行，造成 2 倍伤害。`),
    entry('lance','天际贯穿','力量','arrow-up-down',2,r=>`每箭前 ${r} 次命中贯穿目标所在整列，造成 2 倍伤害。`),
    entry('pulse','末日脉冲','元素','radio',1,()=>`每射出 3 箭，对场上所有可破坏砖块造成 1.2 倍基础伤害。`),
    entry('aftershock','地裂余震','元素','waves',2,r=>`爆破砖碎裂后追加 ${r} 次大范围余震，每次造成 2 倍伤害。`),
    entry('storm','雷神降临','元素','zap-off',3,r=>`电弧砖改为跃击 ${4+r*3} 个目标，每次造成 2 倍伤害。`),
    entry('blizzard','绝对零度','元素','wind',2,r=>`冰晶砖冻结范围扩大至 ${160+r*30}，并对范围内砖块造成 ${r} 倍伤害，享受易碎加成。`),
    entry('prism','万华棱镜','箭术','split',3,r=>`分裂砖改为生成 ${4+r*2} 支箭，每支可穿透 ${2+r} 块砖。`),
    entry('alchemist','点石成金','财富','coins',2,r=>`金矿砖的金币倍率从 3 倍提高到 ${3+r*5} 倍，与其他收益被动相乘。`),
    entry('mint','黄金时代','财富','badge-dollar-sign',Infinity,r=>`所有砖块掉落的金币变为 ${r+1} 倍，核心奖金独立计算。`),
    entry('treasury','核心宝库','财富','gem',Infinity,r=>`核心通关奖金变为 ${1+r*2} 倍，不计入普通连击收益。`),
    entry('bargain','超频工坊','财富','percent',2,r=>`弹弓、箭矢和砖块升级只需原价的 ${Math.round(100*.6**r)}%。`),
    entry('forge','神匠赐福','财富','hammer',1,()=>`每关第一次购买升级，额外免费提升同一项目 2 级。`),
    entry('resonance','核心共振','核心','diamond',2,r=>`核心解锁门槛从 60% 降至 ${60-r*15}% 的砖块。`),
    entry('corehunter','终焉引力','核心','orbit',1,()=>`核心出现后，箭矢额外获得 4 次穿透，并在靠近核心时自动转向它。`),
    entry('legion','万箭归宗','箭术','layers',1,()=>`每第 3 次射击追加 6 支扇形箭。`),
    entry('decay','衰弱领域','力量','heart-crack',1,()=>`进入关卡时，全场砖块生命削减 35%；获得此技能时也立即生效。`),
    entry('rapid','疾风快弦','箭术','timer',1,()=>`射击间隔从 1 秒缩短至 0.18 秒，所有箭矢伤害提高 35%。`),
    entry('heavy','破城重弩','力量','anvil',1,()=>`箭矢伤害提高 80%，额外穿透 2 块砖。`),
    entry('growing','越战越勇','力量','trending-up',1,()=>`每次射击增加 20% 基础伤害，第 10 箭起达到 3 倍伤害；本关持续生效。`),
    entry('doubletap','延迟引爆','元素','alarm-clock',1,()=>`每次命中埋下炸弹，0.2 秒后对周围 115 范围造成 2 倍基础伤害。`),
    entry('poison','蚀骨剧毒','元素','flask-conical',1,()=>`命中后追加 3 次毒伤，每次为基础伤害的 80%，间隔 0.15 秒。`),
    entry('nova','碎星新生','箭术','asterisk',1,()=>`每轮前 8 次击碎各释放 3 支追踪箭，造成 1.5 倍伤害，穿透 3 块砖。`),
    entry('crossfire','十字审判','力量','plus',1,()=>`每支箭第一次命中，对目标所在整行和整列追加 3 倍基础伤害。`),
    entry('jackpot','幸运暴富','财富','dices',1,()=>`每块砖有 25% 概率掉落 10 倍金币，其余也有 1.5 倍；核心奖金不变。`),
    entry('snowburst','冰河冲击','元素','snowflake',1,()=>`每次射击冻结随机 5 块砖，随即各造成 1.5 倍基础伤害，冰冻再翻倍。`),
    entry('supernova','超新星爆发','元素','sun',1,()=>`每第 4 次射击轰击全场，造成 5 倍基础伤害；平时箭矢伤害提高 50%。`),
    entry('swarmqueen','蜂群女王','箭术','bug',1,()=>`每次射击释放 8 支追踪箭，各造成 1.5 倍伤害，穿透 3 块砖。`),
    entry('railgun','歼星轨道炮','力量','rocket',1,()=>`所有箭矢造成 4 倍伤害，额外穿透 12 块砖。`),
    entry('ambush','破阵先锋','力量','swords',1,()=>`箭矢命中满血砖块时造成 3 倍伤害，开路一箭更凶猛。`),
    entry('siphon','饮血长箭','箭术','pipette',1,()=>`箭矢直接击碎砖块时返还 1 次穿透，并提高初始箭伤的 25%；每支箭最多触发 8 次。`),
    entry('opportunist','穷追猛打','力量','target',1,()=>`箭矢命中受伤砖块时造成 3 倍伤害，并额外穿透 2 块砖。`),
    entry('sharpshooter','满弓狙击','箭术','telescope',1,()=>`蓄力达到 95% 时，主箭造成 3 倍伤害并额外穿透 6 块砖。`),
    entry('bounty','连杀赏金','财富','wallet',1,()=>`每支箭每多击碎一块，金币倍率再增加 50%，第 9 块起为 5 倍；与连击收益相乘。`),
    entry('bankshot','弹射工厂','箭术','shuffle',1,()=>`每支箭前 3 次反弹，各释放 2 支追踪箭，造成 2 倍伤害、穿透 2 块砖；追踪箭不再分裂。`),
    entry('minefield','脉冲雷区','元素','disc',1,()=>`每支箭首次命中布下雷区，连续爆炸 3 次，每次对 135 范围造成 2 倍基础伤害。`),
    entry('frostfire','冰火连爆','元素','thermometer-snowflake',1,()=>`每次命中冻结周围 100 范围，随后引爆，造成 1.5 倍基础伤害，冰冻再翻倍。`),
    entry('thunderlottery','雷霆骰子','元素','dice-5',1,()=>`每次命中有 35% 概率释放大闪电，跃击最近 6 块砖，各造成 3 倍基础伤害。`),
    entry('mirror','镜像夹击','箭术','columns-2',1,()=>`每次射击从战场左右两侧各发射 2 支追踪箭，造成 1.5 倍伤害、穿透 3 块砖。`),
    entry('specialist','异能收割','财富','badge-plus',1,()=>`箭矢对特殊砖块造成 4 倍伤害，特殊砖块金币再乘 3 倍。`),
    entry('reaper','死神点名','力量','skull',1,()=>`每第 3 次射击，直接处决剩余生命最低的 4 块砖；平时箭矢伤害提高 60%。`),
    entry('cascade','连环殉爆','元素','bomb',1,()=>`每轮飞行前 16 块被击碎的砖各引爆 145 范围，造成 2.5 倍基础伤害；爆炸击碎也能续爆。`),
    entry('orbital','轨道轰炸','元素','satellite',1,()=>`每次射击锁定砖块最密集的位置，连续轰炸 3 次，每次对 160 范围造成 3 倍基础伤害。`),
    entry('roulette','命运轮盘','核心','disc-3',1,()=>`每箭随机获得一种力量：5 倍伤害并多穿透 10 块；追加 8 支双倍伤害追踪箭；全场受到 2.5 倍基础伤害。`),
    entry('stormfront','天幕裁决','元素','cloud-rain',1,()=>`每次射击随机选中 3 行砖块，逐行轰击，每块受到 3 倍基础伤害。`),
    entry('boomerang','回旋天轮','箭术','undo-2',1,()=>`箭矢额外穿透 6 块；飞行 0.55 秒后反向折返，伤害变为 3 倍，再获得 6 次穿透，沿途二次收割。`),
    entry('wormhole','折跃猎手','箭术','waypoints',1,()=>`每箭前 3 次命中后，传送至远处另一块砖旁并重新瞄准；每次折跃箭伤提高初始值的 50%，返还 2 次穿透。`),
    entry('buzzsaw','行刑锯盘','力量','disc-3',1,()=>`每次射击在最密集的一行放出巨型锯盘，横切整个战场；每块砖最多被锯 3 次，每次受到 1.5 倍基础伤害。`),
    entry('siegebreaker','城墙粉碎机','力量','pickaxe',1,()=>`箭矢伤害翻倍。命中合金障碍可直接撞碎并继续前进，碎片冲击周围 155 范围，造成 4 倍基础伤害。`),
    entry('spectral','幽界漫游','箭术','scan',1,()=>`箭矢无视所有合金障碍，伤害翻倍，额外穿透 6 块砖；每穿过一面障碍，再为该箭增加 50% 初始伤害。`),
    entry('transmute','万物炼成','元素','flask-round',1,()=>`每次射击将 4 块普通砖炼成爆破、电弧、冰晶或分裂砖，并削去一半当前生命。把普通砖阵变成连锁机关。`),
    entry('threadweaver','缝天之线','力量','spline',1,()=>`每支箭前 5 个不同命中点依次连成切割线；每条线对沿线砖块追加 3 倍基础伤害，可以穿过障碍。`),
    entry('infection','寄生花园','元素','sprout',1,()=>`每箭首次命中种下寄生花。0.3 秒后花朵绽放，造成 3 倍基础伤害，并向最近 2 块砖传播；每箭最多感染 15 块。`),
    entry('contract','猎金契约','财富','scroll-text',1,()=>`场上标记 3 个悬赏目标，对它们的直接伤害翻倍。全部击碎领取当前核心奖金的 40%，并给场上箭矢补充 4 次穿透；每关最多完成 3 轮。`),
    entry('timeslip','弦上时停','箭术','hourglass',1,()=>`拉弓时所有箭矢与重力进入 20% 速度，松弦恢复。射击间隔缩短至 0.25 秒，箭矢伤害翻倍，可在空中布下连续攻势。`),
    entry('nailburst','破壳礼炮','箭术','party-popper',1,()=>`每支主箭首次命中，沿前进方向炸出 3 支碎钉箭，各造成 90% 基础伤害、穿透 2 块；碎钉不再分裂。`),
    entry('fusepath','燃线速递','元素','route',1,()=>`箭矢额外穿透 3 块；前 4 个命中点连成导火线，0.25 秒后逐段烧穿沿线砖块，造成 1.5 倍基础伤害。`),
    entry('rhythm','三拍重音','力量','music-2',1,()=>`箭矢额外穿透 3 块；每支箭每第 3 次命中造成 4 倍箭伤，返还 2 次穿透，并震伤周围 100 范围，造成 1 倍基础伤害。`),
    entry('reboundaim','撞墙开窍','箭术','corner-up-right',1,()=>`每支箭前 3 次反弹后自动瞄准最近的未命中砖块，返还 1 次穿透，箭伤增加初始值的 50%。`),
    entry('overkill','余力借条','力量','ticket',1,()=>`直接击碎后，溢出伤害加 50% 基础伤害存入箭矢，下次命中释放，最多储存 4 倍基础伤害；并保底再穿透 1 块。`),
    entry('pendulum','左右开弓','箭术','chevrons-left-right',1,()=>`奇数次射击追加左右两支侧翼箭，各造成 1.2 倍伤害、穿透 2 块；偶数次射击主箭造成 3 倍伤害、额外穿透 3 块。`),
    entry('teslanet','三角禁区','元素','triangle',1,()=>`每次射击在前方 3 块砖间架设三角电网，连续通电 3 次；边线及内部砖块每次受到 1.5 倍基础伤害。`),
    entry('undertow','潮汐回卷','元素','waves',1,()=>`每箭首次命中掀起半径 240 的回卷浪潮，三道波环由外向内依次碾压，每块砖受到 2 倍基础伤害；最后中心 110 范围再爆发 3 倍伤害。`),
    entry('chronicle','伤痕重映','力量','history',1,()=>`每支主箭记录发射后 1 秒内造成的伤害；随后对仍存活的受伤砖块重演一次，每块最多追加 4 倍基础伤害。`),
    entry('firewheel','日轮护航','元素','sun',1,()=>`每支主箭携带半径 90 的双焰日轮，持续灼烧环带上的砖块，每 0.18 秒造成 1 倍基础伤害；每块每箭最多灼烧 4 次。`),
    entry('worldfold','天地对折','力量','fold-horizontal',1,()=>`每次射击召唤两面光墙，从战场两侧向瞄准列合拢，扫过的砖块各受 3 倍基础伤害；合拢后沿该列释放宽 150 的终结光刃，造成 6 倍伤害。`),
    entry('starforge','吞星熔炉','元素','eclipse',1,()=>`每次射击在砖阵中点燃熔炉，1.2 秒内每块被击碎的砖都为它充能；随后爆发 4 倍基础伤害，每层再加 40% 伤害和 12 范围，最多 16 层，基础范围 170。`)
  ];
  G.skillTiers={white:{name:'普通',level:1},blue:{name:'稀有',level:2},gold:{name:'传说',level:3}};
  const tiers={
    white:['echo','piercer','seeking','ricochet','shatter','execute','rage','aftershock','storm','blizzard','prism','alchemist','treasury','bargain','forge','resonance','corehunter','legion','poison','ambush','siphon','opportunist','sharpshooter','bounty'],
    blue:['titan','blast','ice','critical','sweep','lance','pulse','mint','decay','rapid','heavy','growing','jackpot','snowburst','bankshot','minefield','frostfire','thunderlottery','mirror','specialist'],
    gold:['trident','meteor','hunters','lightning','doubletap','nova','crossfire','supernova','swarmqueen','railgun','reaper','cascade','orbital','roulette','stormfront']
  };
  for(const [tier,ids] of Object.entries(tiers))ids.forEach((id,i)=>Object.assign(catalog.find(s=>s.id===id),{tier,weight:({white:140,blue:65,gold:18}[tier])-i*({white:2,blue:1,gold:1}[tier])}));
  for(const [id,tier,weight] of [
    ['boomerang','blue',62.5],['transmute','blue',59.5],['contract','blue',56.5],['timeslip','blue',53.5],
    ['wormhole','gold',12.5],['buzzsaw','gold',11.5],['siegebreaker','gold',10.5],['spectral','gold',9.5],['threadweaver','gold',8.5],['infection','gold',7.5],
    ['nailburst','white',105],['fusepath','white',103],['rhythm','white',101],['reboundaim','white',99],['overkill','white',97],['pendulum','white',95],
    ['teslanet','blue',55],['undertow','blue',53],['chronicle','blue',51],['firewheel','blue',49],
    ['worldfold','gold',10],['starforge','gold',9]
  ])Object.assign(catalog.find(s=>s.id===id),{tier,weight});
  const totalWeight=catalog.reduce((sum,s)=>sum+s.weight,0);
  // Sum all three ordered draw positions to get the actual inclusion probability.
  for(const s of catalog){
    let chance=s.weight/totalWeight;
    for(const a of catalog)if(a!==s){
      chance+=a.weight/totalWeight*s.weight/(totalWeight-a.weight);
      for(const b of catalog)if(b!==s&&b!==a)chance+=a.weight/totalWeight*b.weight/(totalWeight-a.weight)*s.weight/(totalWeight-a.weight-b.weight);
    }
    s.chance=chance;
  }
  G.rollSkills=()=>{
    const pool=[...catalog],selected=[];
    while(selected.length<3){
      let roll=Math.random()*pool.reduce((sum,s)=>sum+s.weight,0),index=pool.length-1;
      for(let i=0;i<pool.length;i++){roll-=pool[i].weight;if(roll<0){index=i;break;}}
      selected.push(pool.splice(index,1)[0].id);
    }
    return selected;
  };
  const byId=new Map(catalog.map(s=>[s.id,s]));
  const rank=G.skillRank=id=>S.skillScopeVersion===2&&S.skillChosenLevel===S.level&&S.skills?.[id]===1?1:0;
  function normalize(){
    if(S.skillScopeVersion!==2){
      if(S.skillRuntime?.decay)G.bricks.forEach(b=>{b.hp=Math.min(b.max,b.hp/.65);});
      S.skills={};S.skillChosenLevel=0;S.skillRuntime=null;S.skillScopeVersion=2;
    }
    if(S.skillChosenLevel!==S.level)S.skills={};
    const active=catalog.find(s=>rank(s.id));S.skills=active?{[active.id]:1}:{};
    if(!Number.isInteger(S.skillChosenLevel)||S.skillChosenLevel<0)S.skillChosenLevel=0;
    if(!S.skillRuntime||S.skillRuntime.level!==S.level)S.skillRuntime={level:S.level,shots:0,forge:false,decay:false};
  }
  normalize();
  const base={damage:G.damage,penetration:G.penetration,reward:G.reward,cost:G.cost,bonus:G.bonus,generate:G.generate,shoot:G.shoot,buy:G.buy,tick:G.tick,clear:G.clear};
  G.damage=()=>base.damage()*(1+rank('titan')+.35*rank('rapid')+.8*rank('heavy')+.5*rank('supernova')+.6*rank('reaper')+3*rank('railgun')+Math.min(10,S.skillRuntime.shots)*.2*rank('growing'));
  G.penetration=()=>base.penetration()+4*rank('piercer')+2*rank('heavy')+12*rank('railgun')+2*rank('opportunist');
  G.reward=(type,n)=>Math.round(base.reward(type,n)*(1+rank('mint'))*(type==='gold'?(3+rank('alchemist')*5)/3:1)*(rank('jackpot')?(Math.random()<.25?10:1.5):1)*(1+rank('bounty')*Math.min(8,Math.max(0,n-1))*.5)*(type!=='normal'&&rank('specialist')?3:1));
  G.cost=key=>Math.max(1,Math.ceil(base.cost(key)*.6**rank('bargain')));
  G.bonus=level=>Math.round(base.bonus(level)*(1+2*rank('treasury')));
  G.specialConfig=type=>type==='lightning'&&rank('storm')?{links:4+3*rank('storm'),damage:2}:type==='frost'&&rank('blizzard')?{radius:160+30*rank('blizzard'),damage:rank('blizzard')}:type==='prism'&&rank('prism')?{shards:4+2*rank('prism'),pierce:2+rank('prism')}:{};
  const shuffled=list=>{const a=[...list];for(let i=a.length-1;i>0;i--){const j=Math.floor(Math.random()*(i+1));[a[i],a[j]]=[a[j],a[i]];}return a;};
  function applyLevelPassives(){
    G.threshold=Math.ceil(G.initial*(.6-.15*rank('resonance')));
    if(rank('decay')&&!S.skillRuntime.decay){G.bricks.forEach(b=>{b.hp*=.65;b.flash=.2;});S.skillRuntime.decay=true;G.ring(390,320,'#b39a80',340);}
    if(G.killed>=G.threshold&&!G.core)G.spawnCore();
  }
  G.prepareDraft=()=>{
    normalize();applyLevelPassives();
    if(S.skillChosenLevel===S.level){S.draft=null;G.save();return;}
    const eligible=catalog,old=S.draft;
    if(!(old?.level===S.level&&Array.isArray(old.options)&&old.options.length===3&&new Set(old.options).size===3&&old.options.every(id=>eligible.some(s=>s.id===id)))){
      S.draft={level:S.level,options:G.rollSkills()};
    }
    G.phase='draft';G.drag=null;G.save();G.ui();
  };
  G.chooseSkill=id=>{
    const skill=byId.get(id);
    if(G.phase!=='draft'||G.paused||!skill||S.draft?.level!==S.level||!S.draft.options.includes(id)||S.skillChosenLevel===S.level||rank(id)>=skill.max)return false;
    S.skills={[id]:1};S.skillChosenLevel=S.level;S.draft=null;
    const duration=G.reduced?0:.85;
    G.boardEntrance=duration?{start:G.time,end:G.time+duration}:null;
    G.phase=duration?'entering':'ready';
    applyLevelPassives();G.save();G.ui();G.sound('upgrade');G.toast?.(skill.name+' · 仅本关有效');return true;
  };
  let jobs=[],uses={},effectBudget=0;
  const resetShot=()=>{uses={};effectBudget=120;};
  const take=(id,limit)=>{if((uses[id]||0)>=limit)return false;uses[id]=(uses[id]||0)+1;return true;};
  function enqueue(delay,fn){if(effectBudget--<=0)return;jobs.push({at:G.time+delay,level:S.level,arrow:G.activeArrow,fn});}
  const nearby=(x,y,r)=>G.bricks.filter(b=>Math.hypot(b.x-x,b.y-y)<r);
  function area(x,y,r,damage,color='#e8a475'){
    if(G.phase!=='flying')return;G.ring(x,y,color,r);G.sound('boom',1,x);
    const skill=catalog.find(s=>rank(s.id));if(skill)G.skillFX?.(skill.id,x,y,{r,force:true});
    nearby(x,y,r).forEach(b=>G.hit(b,damage,1));
  }
  function arc(x,y,count,damage){
    const targets=[...G.bricks].sort((a,b)=>Math.hypot(a.x-x,a.y-y)-Math.hypot(b.x-x,b.y-y)).slice(0,count);
    let from={x,y};targets.forEach(b=>{G.bolts.push({x:from.x,y:from.y,tx:b.x,ty:b.y,life:.35});from=b;G.hit(b,damage,1);});G.sound('lightning',1,x);
    const skill=catalog.find(s=>rank(s.id));if(skill)G.skillFX?.(skill.id,x,y,{kind:'electric',r:115});
  }
  function launch(x,y,angle,speed,damage=G.damage(),pierce=G.penetration(),homing=false){
    const a=G.addArrow(x,y,Math.sin(angle)*speed,-Math.cos(angle)*speed,pierce);if(a){a.damage=damage;a.homing=homing;}return a;
  }
  function fan(angle,speed,extras){
    for(let i=0;i<extras;i++){const side=i%2?-1:1,offset=(Math.floor(i/2)+1)*.1*side;launch(G.origin.x,G.origin.y,angle+offset,speed);}
  }
  G.shoot=(dx,dy)=>{
     if(!['ready','flying'].includes(G.phase)||G.paused||G.arrows.length>=64||G.time<G.nextShotAt||Math.hypot(dx,dy)<10||dy<5)return false;
     if(G.phase==='ready')resetShot();
     S.skillRuntime.shots++;
     const result=base.shoot(dx,dy);if(!result){S.skillRuntime.shots--;return false;}
     effectBudget=120;
     const main=G.arrows[G.arrows.length-1],v=main.body.velocity,speed=Math.hypot(v.x,v.y),angle=Math.atan2(v.x,-v.y);
     return G.withArrow(main,()=>{
    fan(angle,speed,rank('trident')*5);
    if(rank('rapid'))G.nextShotAt=G.time+.18;
    if(rank('swarmqueen'))for(let i=0;i<8;i++)launch(G.origin.x,G.origin.y,angle+(i-3.5)*.12,speed,G.damage()*1.5,3,true);
    if(rank('snowburst'))shuffled(G.bricks).slice(0,5).forEach((b,i)=>enqueue(.06*i,()=>{if(!G.bricks.includes(b))return;b.frozen=true;G.ring(b.x,b.y,'#9fc9d9',45);G.hit(b,G.damage()*1.5,1);}));
    if(rank('supernova')&&S.skillRuntime.shots%4===0)enqueue(.18,()=>{G.ring(390,440,'#dfbb53',650);G.skillFX?.('supernova',390,440,{kind:'nova',r:650,duration:1.1,force:true});G.sound('core');[...G.bricks].forEach(b=>G.hit(b,G.damage()*5,1));});
    if(rank('sharpshooter')&&Math.hypot(dx,dy)>=95){main.damage*=3;main.pierce+=6;main.color='#d4af47';G.float(G.origin.x,G.origin.y-45,'满弓狙击','#9b7629',16);G.skillFX?.('sharpshooter',G.origin.x,G.origin.y,{kind:'power',r:180});}
    if(rank('mirror'))for(const x of [36,744])for(let i=0;i<2;i++)launch(x,G.origin.y-150-i*90,x<390?.65:-.65,speed,G.damage()*1.5,3,true);
    if(rank('reaper')&&S.skillRuntime.shots%3===0)enqueue(.15,()=>{
      [...G.bricks].sort((a,b)=>a.hp-b.hp).slice(0,4).forEach(b=>{G.bolts.push({x:b.x,y:100,tx:b.x,ty:b.y,life:.4});G.float(b.x,b.y-20,'处决','#a56b80',15);G.hit(b,b.hp,1);});G.sound('lightning');
    });
    if(rank('orbital')&&G.bricks.length){
      const target=G.bricks.reduce((best,b)=>{const count=nearby(b.x,b.y,160).length;return count>best.count?{x:b.x,y:b.y,count}:best;},{count:-1});
      G.ring(target.x,target.y,'#d4af47',160);
      G.skillFX?.('orbital',target.x,target.y,{kind:'mark',r:160,duration:.8});
      for(let i=0;i<3;i++)enqueue(.12+i*.18,()=>area(target.x,target.y,160,G.damage()*3,'#d4af47'));
    }
    if(rank('roulette')){
      const mode=Math.floor(Math.random()*3);main.rouletteMode=mode;
      G.float(G.origin.x,G.origin.y-45,['命运 · 贯星','命运 · 群猎','命运 · 天罚'][mode],'#ac852f',18);
      if(mode===0){main.damage*=5;main.pierce+=10;main.color='#d4af47';}
      if(mode===1)for(let i=0;i<8;i++)launch(G.origin.x,G.origin.y,angle+(i-3.5)*.14,speed,G.damage()*2,3,true);
      if(mode===2)enqueue(.16,()=>{G.ring(390,440,'#d4af47',650);G.skillFX?.('roulette',390,440,{kind:'nova',r:650,duration:1,force:true});G.sound('core');[...G.bricks].forEach(b=>G.hit(b,G.damage()*2.5,1));});
    }
    if(rank('stormfront'))shuffled([...new Set(G.bricks.map(b=>b.y))]).slice(0,3).forEach((y,i)=>enqueue(.1+i*.14,()=>{
      G.skillFX?.('stormfront',390,y,{kind:'beam',force:true});
      G.bolts.push({x:35,y,tx:745,ty:y,life:.4});G.bricks.filter(b=>b.y===y).forEach(b=>G.hit(b,G.damage()*3,1));G.sound('lightning');
    }));
    for(let i=0;i<rank('echo');i++)enqueue(.24*(i+1),()=>{launch(G.origin.x,G.origin.y,angle,speed);fan(angle,speed,rank('trident')*5);G.sound('shoot');G.skillFX?.('echo',G.origin.x,G.origin.y,{r:110});});
    const meteorTargets=shuffled(G.bricks).slice(0,rank('meteor')*6);
    meteorTargets.forEach((b,i)=>enqueue(.04*i,()=>{const a=G.addArrow(b.x,142,(Math.random()*2-1)*9,18,2);if(a){a.damage=G.damage()*2;a.color='#d38b63';}}));
    for(let i=0;i<rank('hunters')*4;i++)launch(G.origin.x+(i%2?36:-36),G.origin.y,angle+(i%2?.25:-.25),speed,G.damage(),G.penetration(),true);
    if(rank('legion')&&S.skillRuntime.shots%3===0){fan(angle,speed,6);G.skillFX?.('legion',G.origin.x,G.origin.y,{kind:'split',r:190});}
    if(rank('pulse')&&S.skillRuntime.shots%3===0)enqueue(.18,()=>{G.ring(390,320,'#adc76e',500);G.skillFX?.('pulse',390,320,{kind:'nova',r:550,duration:.9,force:true});G.sound('core');[...G.bricks].forEach(b=>G.hit(b,G.damage()*1.2,1));});
     G.save();return true;
     });
  };
  G.projectileHit=(b,a)=>{
    if(!G.bricks.includes(b))return;
    const visual=catalog.find(s=>rank(s.id));
    if(visual&&!['critical','execute','ambush','opportunist','specialist','siphon','sharpshooter','bargain','forge','treasury','alchemist','mint','jackpot','bounty','reaper','orbital','stormfront','storm','blizzard','prism','aftershock','pulse','supernova','legion','resonance','sweep','lance','crossfire'].includes(visual.id)){
      G.skillFX?.(visual.id,b.x,b.y,{kind:['doubletap','minefield'].includes(visual.id)?'mark':undefined,r:rank('ice')?95:70});
    }
    if(rank('poison'))b.skillMarkUntil=G.time+.55;
    if(rank('ice'))nearby(b.x,b.y,55+rank('ice')*40).forEach(t=>{t.frozen=true;t.flash=.18;});
    let damage=a.damage*(1+Math.min(1.5,Math.floor(G.combo/4)*.25)*rank('rage'));
    if(rank('ambush')&&b.hp>=b.max){damage*=3;G.skillFX?.('ambush',b.x,b.y,{r:80});}
    if(rank('opportunist')&&b.hp<b.max){damage*=3;G.skillFX?.('opportunist',b.x,b.y,{r:80});}
    if(rank('specialist')&&b.type!=='normal'){damage*=4;G.skillFX?.('specialist',b.x,b.y,{r:85});}
    if(rank('critical')&&Math.random()<.5){damage*=3;G.float(b.x,b.y-13,'暴击','#ce805c',13);G.skillFX?.('critical',b.x,b.y,{r:100});}
    if(rank('execute')&&b.hp-damage*(b.frozen?2:1)<=b.max*.25){damage=Math.max(damage,b.hp);G.skillFX?.('execute',b.x,b.y,{r:95});}
    const position={x:b.x,y:b.y};G.hit(b,damage);
    if(rank('siphon')&&!G.bricks.includes(b)&&(a.siphonKills||0)<8){a.siphonBase??=a.damage;a.siphonKills=(a.siphonKills||0)+1;a.pierce++;a.damage=a.siphonBase*(1+a.siphonKills*.25);G.skillFX?.('siphon',b.x,b.y,{r:75});}
    if(rank('minefield')&&!a.minefield){a.minefield=true;for(let i=0;i<3;i++)enqueue(.12+i*.18,()=>area(position.x,position.y,135,G.damage()*2,'#d3b468'));}
    if(rank('frostfire')){nearby(position.x,position.y,100).forEach(t=>{t.frozen=true;t.flash=.2;});enqueue(.09,()=>area(position.x,position.y,100,G.damage()*1.5,'#e89a79'));}
    if(rank('thunderlottery')&&Math.random()<.35){G.float(position.x,position.y-22,'雷霆大奖','#b59a39',15);enqueue(.07,()=>arc(position.x,position.y,6,G.damage()*3));}
    if(rank('poison'))for(let i=1;i<=3;i++)enqueue(.15*i,()=>{if(G.bricks.includes(b)){G.ring(b.x,b.y,'#91b646',25);G.hit(b,G.damage()*.8,1);}});
    if(rank('doubletap'))enqueue(.2,()=>area(position.x,position.y,115,G.damage()*2));
    if(rank('crossfire')&&!a.crossfire){a.crossfire=true;enqueue(.08,()=>{
      G.skillFX?.('crossfire',position.x,position.y,{kind:'beam',force:true});
      G.bolts.push({x:50,y:position.y,tx:730,ty:position.y,life:.4},{x:position.x,y:145,tx:position.x,ty:820,life:.4});
      G.bricks.filter(t=>Math.abs(t.y-position.y)<12||Math.abs(t.x-position.x)<16).forEach(t=>G.hit(t,G.damage()*3,1));G.sound('lightning');
    });}
    if(!G.bricks.includes(b)&&rank('blast'))enqueue(.055,()=>area(position.x,position.y,125,G.damage()*1.5));
    if(rank('lightning'))enqueue(.065,()=>arc(position.x,position.y,3,G.damage()*1.2));
    if(rank('sweep')&&take('sweep',rank('sweep')))enqueue(.08,()=>{G.skillFX?.('sweep',position.x,position.y,{kind:'beam',force:true});G.bolts.push({x:50,y:position.y,tx:730,ty:position.y,life:.4});G.bricks.filter(t=>Math.abs(t.y-position.y)<12).forEach(t=>G.hit(t,G.damage()*2,1));G.sound('lightning');});
    if(rank('lance')&&take('lance',rank('lance')))enqueue(.08,()=>{G.skillFX?.('lance',position.x,position.y,{kind:'beam',force:true});G.bolts.push({x:position.x,y:145,tx:position.x,ty:505,life:.4});G.bricks.filter(t=>Math.abs(t.x-position.x)<16).forEach(t=>G.hit(t,G.damage()*2,1));G.sound('lightning');});
  };
  G.onBrickDestroyed=b=>{
    if(rank('cascade')&&take('cascade',16))enqueue(.09,()=>area(b.x,b.y,145,G.damage()*2.5,'#e89a79'));
    if(rank('nova')&&take('nova',8))enqueue(.08,()=>{for(let i=0;i<3;i++)launch(b.x,b.y,(i-1)*.8,22,G.damage()*1.5,3,true);});
    if(b.frozen&&rank('shatter')&&take('shatter',rank('shatter')*6))enqueue(.09,()=>area(b.x,b.y,65,G.damage()*1.5,'#9fc9d9'));
    if(b.type==='bomb'&&rank('aftershock'))for(let i=0;i<rank('aftershock');i++)enqueue(.16+i*.12,()=>area(b.x,b.y,112,G.damage()*2));
  };
  G.onRicochet=a=>{
    if(rank('bankshot')&&!a.bankEcho&&(a.bankshots||0)<3){
      a.bankshots=(a.bankshots||0)+1;
      G.withArrow(a,()=>{const p=a.body.position;for(const offset of [-.5,.5]){const child=launch(p.x,p.y,offset,24,G.damage()*2,2,true);if(child)child.bankEcho=true;}});
    }
    if(!rank('ricochet')||(a.rebounds||0)>=4)return;
    a.reboundBase??=a.damage;a.rebounds=(a.rebounds||0)+1;a.pierce+=2;a.damage=a.reboundBase*(1+.15*a.rebounds);
  };
  G.guideArrows=(dt,arrows)=>{
    for(const a of arrows){
      const p=a.body.position,v=a.body.velocity;
      if(G.core&&rank('corehunter')&&!a.coreBoost){a.pierce+=4;a.coreBoost=true;}
      const reach=a.homing?350:180+rank('seeking')*70;
      const guiding=rank('corehunter')||a.homing||rank('seeking');
      const toCore=G.core&&guiding&&Math.hypot(p.x-G.core.x,p.y-G.core.y)<(rank('corehunter')?320:reach);
      if(!toCore&&!a.homing&&!rank('seeking'))continue;
       let target=toCore?G.core:null,best=Infinity;
       if(!target)for(const b of G.bricks){
         if(a.hit.has(b.body.id)||(b.x-p.x)*v.x+(b.y-p.y)*v.y<=0)continue;
         const distance=Math.hypot(b.x-p.x,b.y-p.y);if(distance>=reach)continue;
         const score=distance*(b.type==='normal'?1:.7);if(score<best){best=score;target=b;}
       }
      if(!target)continue;
      const angle=Math.atan2(v.y,v.x),desired=Math.atan2(target.y-p.y,target.x-p.x),delta=Math.atan2(Math.sin(desired-angle),Math.cos(desired-angle));
      const turn=Math.max(-dt*4,Math.min(dt*4,delta)),speed=Math.hypot(v.x,v.y);
      Body.setVelocity(a.body,{x:Math.cos(angle+turn)*speed,y:Math.sin(angle+turn)*speed});a.color=toCore?'#91b646':'#8873b1';
    }
  };
  G.hasPendingEffects=()=>jobs.length>0;
  G.beforePhysics=dt=>G.guideArrows(dt,G.arrows);
  G.tick=dt=>{
    if(G.paused||G.phase==='draft')return;
    if(G.phase==='flying'){
      const due=jobs.filter(j=>j.at<=G.time);jobs=jobs.filter(j=>j.at>G.time);
      for(const job of due)if(job.level===S.level&&G.phase==='flying')G.withArrow(job.arrow,job.fn);
    }
    base.tick(dt);
  };
  G.buy=key=>{
    const result=base.buy(key);
    if(result&&rank('forge')&&!S.skillRuntime.forge){S.skillRuntime.forge=true;S.up[key]+=2;G.save();G.ui();G.toast?.('神匠赐福 · 额外提升 2 级');}
    return result;
  };
  G.generate=restore=>{jobs=[];resetShot();normalize();base.generate(restore);G.prepareDraft();G.save();G.ui();};
  G.clear=()=>{if(G.phase==='clearing')return;jobs=[];G.settledBonus=G.bonus();base.clear();S.skills={};try{localStorage.setItem('slingbreak-save-v1',JSON.stringify(S));}catch{}G.ui();};
  G.prepareDraft();
})();
