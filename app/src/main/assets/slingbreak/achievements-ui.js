(() => {
  'use strict';
  const G=window.Game,$=id=>document.getElementById(id),panel=$('earnings');
  // Match reading order to the visual bottom placement.
  $('arena').after(panel);
  const pops=document.createElement('div');pops.className='achievement-pops';pops.setAttribute('aria-live','polite');$('arena').append(pops);
  const achievementQueue=[],visibleAchievements=[];
  const popDuration=1800,popInterval=420,maxPending=3;
  let popFrame=0,popLast=0,popClock=0,nextPopAt=0;
  function createPop(item){
    const el=document.createElement('div');el.className='achievement-pop';
    const title=document.createElement('strong');title.textContent=item.count>1?`${item.name} x ${item.count}`:item.name;
    const source=item.arrow===null?'多箭齐发':`第 ${item.arrow} 支箭`;
    const detail=document.createElement('span');detail.textContent=item.count>1?`${source} · ${item.count} 项成就 · 倍率奖励合计 +${item.bonus.toFixed(2)}`:`${source} · 成就倍率 +${item.bonus.toFixed(2)}`;
    el.append(title,detail);pops.append(el);
    visibleAchievements.push({el,born:popClock,y:0,burst:item.count>1});
  }
  function playAchievementQueue(time){
    popFrame=0;
    const dt=Math.min(50,popLast?time-popLast:0);popLast=time;
    const paused=G.paused||document.hidden||!!document.querySelector('dialog[open]');
    if(!paused)popClock+=dt;
    const view=G.view;
    if(view){
      // Anchor the bottom entry beneath the sling, using the canvas world transform.
      const canvas=$('game'),arena=$('arena');
      const c=canvas.getBoundingClientRect(),r=arena.getBoundingClientRect();
      const baseline=Math.min(r.height-30,c.top-r.top+view.offsetY+(G.origin.y+140)*view.scale);
      pops.style.top=baseline+'px';
      pops.style.setProperty('--pop-x',(c.left-r.left+view.offsetX+G.origin.x*view.scale)+'px');
    }
    if(!paused){
      for(let i=visibleAchievements.length-1;i>=0;i--){
        if(popClock-visibleAchievements[i].born>=popDuration){visibleAchievements[i].el.remove();visibleAchievements.splice(i,1);}
      }
      if(achievementQueue.length&&popClock>=nextPopAt){
        if(visibleAchievements.length>=3)visibleAchievements.shift().el.remove();
        createPop(achievementQueue.shift());nextPopAt=popClock+popInterval;
      }
      const spacing=Math.max(58,...visibleAchievements.map(p=>p.el.offsetHeight+12));
      visibleAchievements.forEach((p,i)=>{
        const age=popClock-p.born,target=-(visibleAchievements.length-1-i)*spacing;
        p.y=G.reduced?target:p.y+(target-p.y)*(1-Math.exp(-dt/95));
        const enter=Math.min(1,age/280),exit=Math.max(0,(age-(popDuration-400))/400);
        const peak=p.burst?1.18:1.12;
        const scale=G.reduced?1:enter<.65?.7+(peak-.7)*enter/.65:peak-(peak-1)*(enter-.65)/.35;
        const lift=G.reduced?0:18*(1-enter)-20*exit;
        p.el.style.opacity=G.reduced?'1':String(Math.min(1,age/100)*(1-exit));
        p.el.style.transform=`translate(-50%, -100%) translateY(${p.y+lift}px) scale(${scale})`;
      });
    }
    if(achievementQueue.length||visibleAchievements.length)popFrame=requestAnimationFrame(playAchievementQueue);
    else popLast=0;
  }
  G.showAchievement=(item,score)=>{
    // Bound playback debt; overflow shares one burst without losing reward totals.
    if(achievementQueue.length>=maxPending){
      const burst=achievementQueue[achievementQueue.length-1];
      burst.count++;burst.bonus+=item.bonus;
      if(burst.arrow!==score.id)burst.arrow=null;
      if(item.bonus>=burst.bestBonus){burst.name=item.name;burst.bestBonus=item.bonus;}
    }else achievementQueue.push({name:item.name,bonus:item.bonus,bestBonus:item.bonus,arrow:score.id,count:1});
    if(!popFrame)popFrame=requestAnimationFrame(playAchievementQueue);
  };
  const resetGame=G.reset;
  G.reset=()=>{
    achievementQueue.length=0;visibleAchievements.length=0;pops.replaceChildren();
    cancelAnimationFrame(popFrame);popFrame=0;popLast=0;popClock=0;nextPopAt=0;
    return resetGame();
  };
  const exact=n=>Math.floor(n).toLocaleString('en-US');
  let shownMoney=G.shotMoney,shownWallet=G.state.coins,targetMoney=shownMoney,targetWallet=shownWallet;
  let frame=0,last=0,event=0,library='';
  function animate(time){
    const fraction=1-Math.exp(-Math.min(64,time-(last||time-16))/85);last=time;
    shownMoney+=(targetMoney-shownMoney)*fraction;shownWallet+=(targetWallet-shownWallet)*fraction;
    if(Math.abs(targetMoney-shownMoney)<1)shownMoney=targetMoney;
    if(Math.abs(targetWallet-shownWallet)<1)shownWallet=targetWallet;
    $('shot-money').textContent='+ '+G.fmt(shownMoney);$('live-wallet').textContent=exact(shownWallet);
    if(shownMoney!==targetMoney||shownWallet!==targetWallet)frame=requestAnimationFrame(animate);else{frame=0;last=0;}
  }
  function pulse(name){panel.classList.remove(name);void panel.offsetWidth;panel.classList.add(name);}
  panel.addEventListener('animationend',e=>{if(e.animationName==='cash-rise')panel.classList.remove('paying');if(e.animationName==='cash-flash')panel.classList.remove('celebrating');});
  G.updateAchievementUI=()=>{
    const delta=G.state.coins-targetWallet;
    if(delta>0){$('money-burst').textContent='+'+G.fmt(delta);pulse('paying');}
    if(G.shotMoney<targetMoney||G.reduced)shownMoney=G.shotMoney;
    if(G.state.coins<targetWallet||G.reduced)shownWallet=G.state.coins;
    targetMoney=G.shotMoney;targetWallet=G.state.coins;
    if(!frame)frame=requestAnimationFrame(animate);
    const s=G.latestAchievement;
    $('achievement-mult').textContent=(s?.mult||1).toFixed(2);
    $('base-mult').textContent=(s?.baseMult||1).toFixed(2);
    $('total-mult').textContent='×'+((s?.mult||1)*(s?.baseMult||1)).toFixed(2);
    $('earning-state').textContent=G.phase==='clearing'?'核心奖金已到账':G.phase==='flying'?'持续入账':G.phase==='ready'&&G.shotMoney?'已入账':'等待开弓';
    const e=G.achievementEvent;
    if(e&&e.serial!==event){event=e.serial;$('achievement-ticker').textContent=`第 ${e.arrow} 支箭 · ${e.name} · 成就倍率 +${e.bonus.toFixed(2)}`;$('achievement-ticker').classList.add('hot');pulse('celebrating');}
    else if(!e){event=0;$('achievement-ticker').textContent='每支箭独立挑战 · 精彩操作，整箭加薪';$('achievement-ticker').classList.remove('hot');}
    const stamp=JSON.stringify(G.state.achievements||{});
    if(stamp!==library){
      library=stamp;const h=G.state.achievements||{};
      $('achievement-count').textContent=G.achievementCatalog.filter(a=>h[a.id]>0).length+' / '+G.achievementCatalog.length;
      $('achievement-list').innerHTML=G.achievementCatalog.map(a=>`<div class="achievement-item ${h[a.id]?'earned':''}"><b>${a.name}</b><span>倍率 +${a.bonus.toFixed(2)}</span><small>${a.description} · ${h[a.id]?'已达成 '+h[a.id]+' 次':'尚未达成'}</small></div>`).join('');
    }
  };
  G.ui();
})();
