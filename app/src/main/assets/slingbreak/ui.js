(() => {
  const G=Game,$=id=>document.getElementById(id);
  const upgrades={power:{name:'弹弓',icon:'crosshair',desc:()=>`拉力 ${Math.round(G.speed()/24*100)}% · 更远射程`},arrow:{name:'箭矢',icon:'move-up-right',desc:()=>`伤害 ${G.damage().toFixed(2)} · 穿透 ${G.penetration()} 块`},brick:{name:'砖块',icon:'blocks',desc:()=>`价值 +${Math.round((G.valueMultiplier()-1)*100)}% · 特殊率 ${(G.specialRate()*100).toFixed(1)}%`}};
  for(const [key,u] of Object.entries(upgrades)){
    const el=document.createElement('div');el.className='upgrade';el.innerHTML=`<span class="upgrade-icon"><i data-lucide="${u.icon}"></i></span><div><div class="upgrade-title"><b>${u.name}</b><small id="${key}-level"></small></div><p class="upgrade-desc" id="${key}-desc"></p></div><button class="buy-button" id="buy-${key}" aria-label="升级${u.name}"><i data-lucide="plus"></i><span></span></button>`;$('upgrades').append(el);$('buy-'+key).onclick=()=>G.buy(key);
  }
  const icons=()=>lucide.createIcons();
  G.ui=()=>{
    $('level').textContent=String(G.phase==='clearing'?G.state.level-1:G.state.level).padStart(2,'0');
    $('balance').textContent=G.fmt(G.state.coins);$('total-destroyed').textContent=G.fmt(G.state.total);$('best-combo').textContent=G.state.best;
    $('progress-label').textContent=Math.min(G.killed,G.threshold)+' / '+G.threshold;
    $('progress-bar').style.width=Math.min(100,G.killed/G.threshold*100)+'%';$('core-label').textContent=G.core?'核心已显现':G.phase==='clearing'?'核心击破':'核心解锁';
    $('core-required').textContent=G.threshold;$('core-bonus').textContent='+ '+G.fmt(G.phase==='clearing'&&G.settledBonus!==undefined?G.settledBonus:G.bonus(G.phase==='clearing'?G.state.level-1:G.state.level));
    const arrowScore=G.latestAchievement;
    $('combo').classList.toggle('visible',!!arrowScore&&arrowScore.kills>=2&&G.phase==='flying');$('combo-count').textContent=arrowScore?.kills||0;$('combo-mult').textContent='本箭连击';
     $('play-status').textContent=G.paused?'已暂停':G.phase==='clearing'?'下一关即将开始':G.phase==='entering'?'砖块入场中':G.drag?'蓄力中':G.phase==='flying'?'可继续射击':'就绪';
    if(!G.drag)$('power-readout').querySelector('b').textContent='0%';
    for(const [key,u] of Object.entries(upgrades)){
      $(key+'-level').textContent='LV. '+(G.state.up[key]+1);$(key+'-desc').textContent=u.desc();const b=$('buy-'+key),cost=G.cost(key);b.querySelector('span').textContent=G.fmt(cost);b.disabled=G.state.coins<cost||G.phase!=='ready'||G.paused;
      b.title=G.phase!=='ready'?'本箭结束后可升级':G.state.coins<cost?'还差 '+G.fmt(cost-G.state.coins)+' 金币':'升级'+u.name+' · '+G.fmt(cost)+' 金币';
    }
     $('shop-trigger').hidden=false;$('shop-balance').textContent=G.fmt(G.state.coins);
  };
  let toastTimer;
  G.toast=text=>{$('toast').textContent=text;$('toast').classList.add('visible');clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').classList.remove('visible'),2400);};
  const pause=value=>{
    if(document.getElementById('skill-library')?.open)return;
    if(G.phase==='draft')value=false;G.paused=value;G.audio.sync();G.drag=null;G.pointer=null;$('overlay').hidden=!value;G.ui();
  };
  $('resume').onclick=()=>pause(false);
  let wasPaused=false;
  $('reset').onclick=()=>{wasPaused=G.paused;pause(true);$('reset-dialog').showModal();};
  $('cancel-reset').onclick=()=>$('reset-dialog').close();
  $('reset-dialog').addEventListener('close',()=>pause(wasPaused));
  $('confirm-reset').onclick=()=>{G.reset();wasPaused=false;$('reset-dialog').close();G.toast('新的开始 · LEVEL 1');};
  const shop=$('shop');let shopClosing=false;
  const closeShop=()=>{
    if(!shop.open||shopClosing)return;
    shopClosing=true;shop.classList.add('is-closing');
    const finish=()=>{shop.classList.remove('is-closing');shopClosing=false;shop.close();};
    if(G.reduced)finish();else shop.addEventListener('animationend',finish,{once:true});
  };
  $('shop-trigger').onclick=()=>{G.drag=null;G.pointer=null;shop.showModal();};
  $('close-shop').onclick=closeShop;
  shop.addEventListener('cancel',e=>{e.preventDefault();closeShop();});
  shop.addEventListener('click',e=>{if(e.target===shop)closeShop();});
  shop.addEventListener('close',()=>$('game').focus({preventScroll:true}));
   document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!document.querySelector('dialog[open]'))pause(!G.paused);});
  document.addEventListener('visibilitychange',()=>{if(document.hidden){pause(true);if(G.phase!=='clearing')G.save();}});
  window.addEventListener('pagehide',()=>{if(G.phase!=='clearing')G.save();});
  G.ui();icons();
})();
