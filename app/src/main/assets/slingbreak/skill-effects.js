(() => {
  'use strict';
  const G=window.Game,TAU=Math.PI*2;
  const profiles={};
  const group=(ids,kind,color)=>ids.split(' ').forEach(id=>profiles[id]={kind,color});
  group('trident echo legion prism nova mirror','split','#8060bd');
  group('hunters seeking swarmqueen bankshot','seek','#258f88');
  group('titan piercer heavy railgun sharpshooter rapid growing','power','#d36a36');
  group('critical execute rage ambush opportunist reaper','slash','#c34d68');
  group('siphon','drain','#bb4564');
  group('ricochet','bounce','#238e9b');
  group('meteor blast doubletap aftershock cascade orbital minefield','blast','#df7035');
  group('lightning storm thunderlottery stormfront','electric','#ad8517');
  group('ice shatter blizzard snowburst','frost','#258eb2');
  group('frostfire','frostfire','#298fa6');
  group('poison decay','poison','#649529');
  group('sweep lance crossfire','beam','#c75c45');
  group('pulse supernova','nova','#c28e1c');
  group('alchemist mint treasury bargain forge jackpot bounty specialist','coin','#b28719');
  group('resonance corehunter roulette','orbit','#438f9b');
  group('boomerang wormhole spectral timeslip','orbit','#649caa');
  group('buzzsaw siegebreaker threadweaver','power','#a38b60');
  group('transmute infection','poison','#859f54');
  group('contract','coin','#b69a48');
  group('nailburst pendulum','split','#b97b51');
  group('fusepath firewheel','blast','#cf7554');
  group('rhythm worldfold','power','#459c95');
  group('reboundaim','bounce','#39978c');
  group('overkill','drain','#a38d42');
  group('teslanet','electric','#a99231');
  group('undertow','frost','#419eab');
  group('chronicle','orbit','#9b789a');
  group('starforge','nova','#bd9141');
  const treatments={
    trident:['#785fb0','#53a59b','fork',.7],echo:['#708ca8','#b5a1cd','echo',.95],
    titan:['#bd7650','#e0bd67','heavy',.85],piercer:['#398c99','#a9c56e','rail',.55],
    meteor:['#d77743','#e5c273','comet',.8],hunters:['#388e7f','#b6bc58','dash',.65],
    seeking:['#3f929a','#b7bf75','scan',.7],ricochet:['#488d9e','#d4b768','zigzag',.65],
    blast:['#d67550','#e3ba66','comet',.6],lightning:['#b5952b','#77a9ae','electric',.5],
    ice:['#4099b1','#b6d4d9','crystal',.9],shatter:['#569ab5','#a8b9d8','crystal',.6],
    critical:['#c75f68','#dfb266','slash',.42],execute:['#a56179','#c3a9be','slash',.65],
    rage:['#c66546','#e3b46c','flame',.65],sweep:['#c47953','#d5b177','ribbon',.55],
    lance:['#658ba7','#c5b480','rail',.65],pulse:['#8ba248','#d0c86d','wave',1.05],
    aftershock:['#b48355','#d2b38a','zigzag',.95],storm:['#a98d35','#73a2ba','electric',.8],
    blizzard:['#75a8b9','#bbced0','helix',1.1],prism:['#9872b3','#61aaa2','fork',.85],
    alchemist:['#ad963e','#89b480','spark',.85],mint:['#b69b35','#d2c784','beads',.85],
    treasury:['#b89634','#79a39a','spark',1.15],bargain:['#7f9a54','#c7af64','dash',.7],
    forge:['#ba8650','#e1be70','spark',.75],resonance:['#4a9a91','#bac578','wave',1.05],
    corehunter:['#578b9e','#a9c47d','helix',.85],legion:['#8177a6','#c3a979','fork',.85],
    decay:['#8c9961','#b8ba92','beads',1.2],rapid:['#549d91','#b5c881','dash',.42],
    heavy:['#b27c62','#cdbb91','heavy',.8],growing:['#779e55','#d2b46c','ribbon',.9],
    doubletap:['#cb8950','#d8bd84','beads',.55],poison:['#7a9f48','#b5bd70','beads',.95],
    nova:['#8b80b9','#c7ac73','spark',.7],crossfire:['#bd7863','#d6b57a','rail',.7],
    jackpot:['#bf9c37','#d28067','spark',1.05],snowburst:['#5c9fae','#c1d7bd','crystal',.8],
    supernova:['#c69737','#db9c68','comet',1.2],swarmqueen:['#8e9d42','#65a28c','helix',.9],
    railgun:['#548e9c','#dec185','rail',.65],ambush:['#ab7265','#d4b386','slash',.5],
    siphon:['#b76178','#d9a594','helix',.85],opportunist:['#aa7e69','#c3a0ad','slash',.5],
    sharpshooter:['#9d9451','#9ac4b4','scan',.75],bounty:['#b59c4f','#8caf72','beads',.9],
    bankshot:['#5b9b91','#c7b774','fork',.75],minefield:['#b29250','#c4b47d','beads',.8],
    frostfire:['#5b9eae','#d77f59','helix',.85],thunderlottery:['#b89946','#a18eb6','electric',.85],
    mirror:['#839db1','#c1b1cd','echo',1],specialist:['#a08a58','#8fb3a1','spark',.85],
    reaper:['#89748e','#bbb0be','slash',.95],cascade:['#c58460','#d1b46c','comet',.85],
    orbital:['#879ca0','#d0ad66','rail',1.05],roulette:['#a68d63','#7eada6','helix',1.05],
    stormfront:['#7399aa','#c7b579','dash',.85],
    boomerang:['#5c9c8b','#c5b579','ribbon',.85],wormhole:['#947cab','#9dc2b4','echo',1],
    buzzsaw:['#a38b60','#d4bb79','heavy',.65],siegebreaker:['#b77b59','#a5b58b','zigzag',.8],
    spectral:['#779eb0','#c1ced0','dash',.95],transmute:['#859f54','#c9ae7b','spark',.9],
    threadweaver:['#b7748d','#c6b98c','rail',.8],infection:['#7d9f57','#bda0b5','beads',1.1],
    contract:['#b69a48','#a6b575','spark',1],timeslip:['#649caa','#b9c694','scan',1],
    nailburst:['#b97b51','#9db77b','fork',.6],fusepath:['#cf7554','#dec779','flame',.8],
    rhythm:['#b75e7e','#d8b77a','beads',.6],reboundaim:['#39978c','#c5b66c','zigzag',.6],
    overkill:['#a38d42','#78aca1','beads',.85],pendulum:['#658ca8','#c99d70','ribbon',.75],
    teslanet:['#a99231','#69a7ad','electric',.65],undertow:['#419eab','#b6cb9d','helix',.9],
    chronicle:['#9b789a','#b4c8bf','echo',1],firewheel:['#cd8150','#dec773','flame',.75],
    worldfold:['#459c95','#dbbd75','rail',1],starforge:['#bd9141','#71aaa2','comet',1.2]
  };
  for(const [id,[color,accent,trail,duration]] of Object.entries(treatments))Object.assign(profiles[id],{color,accent,trail,duration});
  const active=()=>G.skillCatalog.find(s=>G.skillRank(s.id));
  const effects=[],cooldowns=new Map();
  let lastId=null,lastState='',lastPulse=-10;
  const hud=document.createElement('div');hud.className='skill-live';
  hud.innerHTML='<span class="skill-live-icon"></span><span class="skill-live-copy"><strong></strong><small></small></span><span class="skill-live-state"><span></span><span class="skill-live-meter"><i></i></span></span>';
  document.getElementById('arena').before(hud);
  const icon=hud.querySelector('.skill-live-icon'),name=hud.querySelector('strong'),description=hud.querySelector('small'),status=hud.querySelector('.skill-live-state > span'),meter=hud.querySelector('.skill-live-meter i');
  function animate(node,cls){node.classList.remove(cls);void node.offsetWidth;node.classList.add(cls);}
  function emit(kind,x,y,color,r=70,extra={}){
    effects.push({kind,x,y,color,r,born:G.time,duration:.65,...extra});
    if(effects.length>56){const disposable=effects.findIndex(e=>!e.hero);effects.splice(disposable<0?0:disposable,1);}
  }
  G.skillFX=(id,x,y,options={})=>{
    if(!G.skillRank(id))return;
    const p=profiles[id];if(!p)return;
    const key=id+':'+(options.kind||p.kind);
    if(!options.force&&G.time-(cooldowns.get(key)??-10)<.085)return;
    cooldowns.set(key,G.time);
    const extra=Object.fromEntries(Object.entries(options).filter(([,value])=>value!==undefined));
    const source=G.activeArrow,v=source?.body.velocity;
    emit(options.kind||p.kind,x,y,p.color,options.r||75,{id,accent:p.accent,duration:p.duration,hero:!!options.force,angle:v?Math.atan2(v.y,v.x)+Math.PI/2:0,axis:id==='sweep'||id==='stormfront'?'row':id==='lance'?'column':'both',...extra});
    if(!G.reduced&&effects.length<35)G.burst(x,y,p.accent,options.kind==='mark'?2:4,.55);
    if(G.time-lastPulse>.35){lastPulse=G.time;animate(hud,'is-triggered');}
  };
  function refresh(){
    const s=active();
    if(!s){
      if(lastId!=='__none__'){
        lastId='__none__';lastState='';
        hud.style.setProperty('--skill-color','#8a9380');
        name.textContent='本关被动';description.textContent='每关选择一个强力被动，通关后失效';
        icon.innerHTML='<i data-lucide="sparkles"></i>';lucide.createIcons({root:icon});
        status.textContent='待选择';meter.style.transform='scaleX(0)';
      }
      return;
    }
    if(lastId!==s.id){
      lastId=s.id;lastState='';const p=profiles[s.id];hud.style.setProperty('--skill-color',p.color);
      name.textContent=s.name;description.textContent=s.describe(1);icon.innerHTML=`<i data-lucide="${s.icon}"></i>`;lucide.createIcons({root:icon});
    }
    const shots=G.state.skillRuntime.shots,period={legion:3,pulse:3,reaper:3,supernova:4}[s.id];
    let text='本关生效',progress=1;
    if(period){progress=shots%period/period;text=`${shots%period} / ${period} · ${shots&&shots%period===0?'已释放':'蓄能'}`;}
    if(s.id==='growing'){progress=Math.min(10,shots)/10;text=`伤害 ×${(1+progress*2).toFixed(1)}`;}
    if(s.id==='rage')text=`伤害 +${Math.round(Math.min(1.5,Math.floor(G.combo/4)*.25)*100)}%`;
    if(s.id==='forge')text=G.state.skillRuntime.forge?'免费强化已触发':'首次升级 +2 级';
    if(s.id==='resonance')text=`核心 ${G.killed} / ${G.threshold}`;
    if(s.id==='corehunter')text=G.core?'核心锁定中':'等待核心';
    if(s.id==='treasury')text=`核心奖金 +${G.fmt(G.bonus())}`;
    if(s.id==='bargain')text='升级价格 60%';
    if(s.id==='sharpshooter'){progress=Math.min(1,Math.hypot(G.drag?.dx||0,G.drag?.dy||0)/95);text=progress>=1?'狙击就绪 · ×3':'满弓蓄力';}
    const expanded=G.expandedSkillStatus?.(s.id);if(expanded){text=expanded.text;progress=expanded.progress;}
    if(text!==lastState){lastState=text;status.textContent=text;}meter.style.transform=`scaleX(${progress})`;
  }
  const choose=G.chooseSkill;
  G.chooseSkill=id=>{
    const result=choose(id);if(!result)return result;
    refresh();animate(hud,'is-acquired');const s=active(),p=profiles[id];
    emit('pickup',G.origin.x,G.origin.y,p.color,150,{id,accent:p.accent,duration:1.4,hero:true});
    if(id==='decay')G.bricks.forEach((b,i)=>{if(i%3===0)emit('poison',b.x,b.y,p.color,35,{id,accent:p.accent,duration:1});});
    return result;
  };
  const add=G.addArrow;
  G.addArrow=(...args)=>{const a=add(...args),s=active();if(a&&s){a.skillVisual=s.id;a.color=profiles[s.id].color;}return a;};
  const shoot=G.shoot;
  G.shoot=(...args)=>{const result=shoot(...args),s=active();if(result&&s){G.skillFX(s.id,G.origin.x,G.origin.y,{kind:'launch',r:105,angle:Math.atan2(-args[1],-args[0])+Math.PI/2});refresh();}return result;};
  const hit=G.hit;
  G.hit=(b,damage,depth=0)=>{
    const s=active(),alive=G.bricks.includes(b);
    if(s&&alive&&depth>0)G.skillFX(s.id,b.x,b.y,{r:48,kind:profiles[s.id].kind==='beam'?'slash':undefined});
    return hit(b,damage,depth);
  };
  const destroyed=G.onBrickDestroyed;
  G.onBrickDestroyed=(b,...args)=>{
    const s=active();if(s){
      if(profiles[s.id].kind==='coin'&&!['bargain','forge','treasury','contract'].includes(s.id)){
        if(s.id!=='alchemist'||b.type==='gold')G.skillFX(s.id,b.x,b.y,{kind:'coin',r:60});
      }
      const special={storm:'lightning',blizzard:'frost',prism:'prism',aftershock:'bomb'};
      if(special[s.id]===b.type)G.skillFX(s.id,b.x,b.y,{r:140});
      if(s.id==='nova'||s.id==='cascade'||s.id==='shatter'&&b.frozen)G.skillFX(s.id,b.x,b.y,{r:85});
    }
    return destroyed?.(b,...args);
  };
  const bounce=G.onRicochet;
  G.onRicochet=a=>{const result=bounce?.(a),s=active();if(s&&['ricochet','bankshot'].includes(s.id))G.skillFX(s.id,a.body.position.x,a.body.position.y,{kind:'bounce',r:65});return result;};
  const buy=G.buy;
   G.buy=key=>{const result=buy(key),s=active();if(result&&s&&['forge','bargain'].includes(s.id)){G.skillFX(s.id,G.origin.x,G.origin.y,{kind:'coin',r:110,force:true});refresh();}return result;};
  const award=G.awardBrick;
  G.awardBrick=(...args)=>{const value=award(...args),s=active();if(s&&profiles[s.id].kind==='coin'&&G.time-lastPulse>.35)animate(document.getElementById('earnings'),'skill-economy-flash');return value;};
  const spawn=G.spawnCore;
  G.spawnCore=(...args)=>{const absent=!G.core,result=spawn(...args),s=active();if(absent&&G.core&&s&&['resonance','corehunter','treasury'].includes(s.id))G.skillFX(s.id,G.core.x,G.core.y,{r:150,force:true});return result;};
  const clear=G.clear;
  G.clear=()=>{const s=active();if(s?.id==='treasury'&&G.phase!=='clearing'){emit('coin',390,110,profiles.treasury.color,220,{id:s.id,accent:profiles.treasury.accent,duration:1.5,hero:true});animate(document.getElementById('earnings'),'skill-economy-flash');}return clear();};
  const generate=G.generate;
  G.generate=(...args)=>{effects.length=0;cooldowns.clear();lastId=null;return generate(...args);};
  const stroke=(ctx,x,y,tx,ty)=>{ctx.beginPath();ctx.moveTo(x,y);ctx.lineTo(tx,ty);ctx.stroke();};
  const ring=(ctx,x,y,r)=>{ctx.beginPath();ctx.arc(x,y,Math.max(0,r),0,TAU);ctx.stroke();};
  function drawEffect(ctx,e){
    const t=Math.max(0,Math.min(1,(G.time-e.born)/e.duration)),fade=(1-t)**.7,expand=G.reduced?1:1-(1-t)**3;
    ctx.save();ctx.strokeStyle=e.color;ctx.fillStyle=e.color;ctx.lineCap='round';ctx.globalAlpha=fade*.85;ctx.lineWidth=2;
    if(e.kind==='beam'){
      const column=e.axis!=='row',row=e.axis!=='column';
      for(const [width,alpha,color] of [[24,.07,e.color],[9,.2,e.color],[2,.8,e.accent]]){
        ctx.lineWidth=width*(1-t*.6);ctx.strokeStyle=color;ctx.globalAlpha=fade*alpha;
        if(row)stroke(ctx,35,e.y,745,e.y);if(column)stroke(ctx,e.x,130,e.x,G.origin.y);
      }
    }
    if(e.kind==='mark'){
      ctx.globalAlpha=fade*.5;ctx.lineWidth=1;ctx.setLineDash(e.id==='orbital'?[18,5,3,5]:[5,8]);ring(ctx,e.x,e.y,e.r);ctx.setLineDash([]);
      for(let i=0;i<12;i++){const a=i*TAU/12;stroke(ctx,e.x+Math.cos(a)*(e.r-5),e.y+Math.sin(a)*(e.r-5),e.x+Math.cos(a)*(e.r+4),e.y+Math.sin(a)*(e.r+4));}
    }
    if(e.kind==='nova'||e.kind==='pickup'||e.r>=110&&e.kind!=='launch'&&e.kind!=='mark'){
      ctx.strokeStyle=e.color;ctx.globalAlpha=fade*.32;ctx.lineWidth=e.kind==='nova'?4:1.5;ring(ctx,e.x,e.y,e.r*expand);
      ctx.strokeStyle=e.accent;ctx.globalAlpha=fade*.45;ring(ctx,e.x,e.y,e.r*expand*.85);
    }
    if(e.id==='orbital'&&e.kind!=='launch'&&e.kind!=='mark'&&e.kind!=='pickup'){
      ctx.globalAlpha=fade*.16;ctx.strokeStyle=e.color;ctx.lineWidth=20*(1-t)+2;stroke(ctx,e.x,110,e.x,e.y);
      ctx.globalAlpha=fade*.8;ctx.strokeStyle=e.accent;ctx.lineWidth=2;stroke(ctx,e.x,110,e.x,e.y);
    }
    ctx.translate(e.x,e.y);
    if(e.kind==='launch')ctx.rotate(e.angle||0);
    if(e.kind==='coin')ctx.translate(0,-t*35);
    const radius=Math.min(e.r,e.kind==='pickup'?115:e.kind==='nova'?190:115);
    if(!G.reduced&&e.hero&&effects.length<24){ctx.shadowColor=e.accent;ctx.shadowBlur=8;}
    ctx.globalAlpha=fade*.9;
    G.paintSkillSignature(ctx,e.id,t,radius,e.color,e.accent,e.kind);
    ctx.restore();
  }
  function drawTrail(ctx,a,p,index){
    const trail=a.trail;if(trail.length<2)return;
    const motion=G.reduced?0:G.time,style=p.trail;
    const points=(offset=0,wave=false)=>trail.map((point,i)=>{
      const next=trail[Math.min(i+1,trail.length-1)],previous=trail[Math.max(0,i-1)],dx=next.x-previous.x,dy=next.y-previous.y,length=Math.hypot(dx,dy)||1;
      const bend=offset*(wave?Math.sin(i*.85-motion*9):1)*i/trail.length;
      return{x:point.x-dy/length*bend,y:point.y+dx/length*bend};
    });
    const trace=(list,color,width,alpha)=>{ctx.strokeStyle=color;ctx.lineWidth=width;ctx.globalAlpha=alpha;ctx.beginPath();list.forEach((p,i)=>i?ctx.lineTo(p.x,p.y):ctx.moveTo(p.x,p.y));ctx.stroke();};
    ctx.save();ctx.lineCap='round';
    trace(trail,p.color,style==='heavy'?12:style==='comet'?10:6,.12);
    if(style==='rail'){trace(points(-3),p.color,2,.6);trace(points(3),p.color,2,.6);trace(trail,p.accent,1.5,.9);}
    else if(style==='helix'){trace(points(6,true),p.color,2,.65);trace(points(-6,true),p.accent,2,.8);}
    else if(style==='echo'){trace(points(-5),p.color,2,.4);trace(points(5),p.accent,2,.5);ctx.setLineDash([8,5]);trace(trail,p.color,1.5,.8);}
    else if(style==='fork'){trace(points(-6),p.color,1.5,.5);trace(points(6),p.accent,1.5,.7);trace(trail,p.color,2,.7);}
    else if(style==='electric'||style==='zigzag'){
      const jagged=trail.map((p,i)=>({x:p.x+(i%2?4:-4),y:p.y+(i%2?-3:3)}));trace(jagged,p.color,2,.8);trace(trail,p.accent,1,.75);
    }else if(style==='dash'||style==='scan'){ctx.setLineDash(style==='scan'?[2,9]:[10,7]);trace(trail,p.color,2.5,.8);}
    else if(style==='ribbon'||style==='flame'){trace(points(4,true),p.color,4,.55);trace(points(-3,true),p.accent,2,.8);}
    else if(style==='comet'||style==='heavy'){trace(trail,p.color,style==='heavy'?5:4,.65);trace(trail,p.accent,1.5,.95);}
    else trace(trail,p.color,1.5,.45);
    ctx.setLineDash([]);
    if(['beads','spark','crystal','slash'].includes(style))for(let i=2;i<trail.length;i+=3){
      const point=trail[i],size=1+i/trail.length*2.5;ctx.strokeStyle=i%2?p.color:p.accent;ctx.lineWidth=1.5;ctx.globalAlpha=i/trail.length*.8;
      if(style==='beads')ring(ctx,point.x,point.y,size);
      else if(style==='spark'){stroke(ctx,point.x-size,point.y,point.x+size,point.y);stroke(ctx,point.x,point.y-size,point.x,point.y+size);}
      else if(style==='crystal'){ctx.save();ctx.translate(point.x,point.y);ctx.rotate(Math.PI/4);ctx.strokeRect(-size,-size,size*2,size*2);ctx.restore();}
      else stroke(ctx,point.x-4,point.y+4,point.x+4,point.y-4);
    }
    // Cap detailed projectile ornaments during large volleys; every arrow retains its styled trail.
    if(index<12){const pos=a.body.position,v=a.body.velocity;ctx.translate(pos.x,pos.y);ctx.rotate(Math.atan2(v.y,v.x)+Math.PI/2);ctx.globalAlpha=.7;
      G.paintSkillSignature(ctx,a.skillVisual,.5,style==='heavy'?16:12,p.color,p.accent,'aura');
    }
    ctx.restore();
  }
  G.drawSkillEffects=(ctx,layer)=>{
    const s=active();ctx.save();
    if(layer==='field'){
      if(s){
        const p=profiles[s.id],time=G.reduced?0:G.time;ctx.strokeStyle=p.color;ctx.fillStyle=p.color;
        for(const b of G.bricks){
          const marked=b.skillMarkUntil>G.time;
          const selected=s.id==='decay'||s.id==='execute'&&b.hp/b.max<=.25||s.id==='ambush'&&b.hp>=b.max||s.id==='opportunist'&&b.hp<b.max||s.id==='specialist'&&b.type!=='normal'||s.id==='alchemist'&&b.type==='gold';
          if(marked||selected){ctx.globalAlpha=marked?.65:.35;ctx.lineWidth=marked?2.5:1.5;ctx.strokeRect(b.x-b.w/2-3,b.y-b.h/2-3,b.w+6,b.h+6);}
          if(b.frozen){ctx.globalAlpha=.8;ctx.strokeStyle='#258eb2';for(const side of [-1,1]){stroke(ctx,b.x+side*(b.w/2-6),b.y-b.h/2,b.x+side*(b.w/2-12),b.y-b.h/2+9);}ctx.strokeStyle=p.color;}
        }
        if(G.core&&['corehunter','resonance','treasury'].includes(s.id)){ctx.globalAlpha=.7;ctx.setLineDash([7,6]);ring(ctx,G.core.x,G.core.y,62+Math.sin(time*3)*4);ctx.setLineDash([]);}
         if(['hunters','seeking','swarmqueen','bankshot','corehunter'].includes(s.id))G.arrows.slice(0,4).forEach(a=>{
           const pos=a.body.position,v=a.body.velocity;let target=G.core&&s.id==='corehunter'?G.core:null,best=Infinity;
           if(!target)for(const b of G.bricks){if(a.hit.has(b.body.id)||(b.x-pos.x)*v.x+(b.y-pos.y)*v.y<=0)continue;const distance=Math.hypot(b.x-pos.x,b.y-pos.y);if(distance<best){best=distance;target=b;}}
          if(target&&Math.hypot(target.x-pos.x,target.y-pos.y)<350){ctx.globalAlpha=.35;ctx.setLineDash([4,7]);stroke(ctx,pos.x,pos.y,target.x,target.y);ctx.setLineDash([]);ring(ctx,target.x,target.y,19);}
        });
      }
      for(let i=effects.length-1;i>=0;i--)if(G.time-effects[i].born>=effects[i].duration)effects.splice(i,1);
      effects.forEach(e=>drawEffect(ctx,e));
    }else{
      refresh();
      if(s){
        const p=profiles[s.id],time=G.reduced?0:G.time,charge=Math.min(1,Math.hypot(G.drag?.dx||0,G.drag?.dy||0)/95);
        ctx.save();ctx.translate(G.origin.x,G.origin.y);ctx.globalAlpha=.2+charge*.25;
        G.paintSkillSignature(ctx,s.id,.5+Math.sin(time*1.5)*.08,80+charge*14,p.color,p.accent,'aura');ctx.restore();
        G.arrows.forEach((a,i)=>drawTrail(ctx,a,profiles[a.skillVisual]||p,i));
      }
    }
    ctx.restore();
  };
  refresh();
})();
