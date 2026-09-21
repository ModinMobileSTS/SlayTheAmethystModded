(() => {
  const G=Game,$=id=>document.getElementById(id),draft=$('skill-draft'),library=$('skill-library');
  const baseUi=G.ui;let draftKey='',ownedKey='',libraryPaused=false,selecting=false,lastOptions=[];
  const reducedMotion=matchMedia('(prefers-reduced-motion: reduce)');
  async function selectSkill(id,button){
    if(selecting||G.phase!=='draft'||G.paused)return;
    selecting=true;
    G.audio.unlock();
    const cards=[...$('draft-options').children];
    cards.forEach(card=>card.disabled=true);
    button.classList.add('is-selected');draft.classList.add('is-selecting');
    if(!reducedMotion.matches){
      const animations=cards.map(card=>card.animate(card===button?[
        {transform:'scale(1)',opacity:1},{transform:'translateY(-7px) scale(1.035)',opacity:1,offset:.4},{transform:'translateY(-3px) scale(1.015)',opacity:1}
      ]:[{transform:'scale(1)',opacity:1},{transform:'translateY(10px) scale(.96)',opacity:.25}],{duration:260,easing:'cubic-bezier(.22,1,.36,1)',fill:'forwards'}));
      await Promise.all(animations.map(animation=>animation.finished.catch(()=>{})));
      const exit=draft.animate([{opacity:1,transform:'none'},{opacity:0,transform:'translateY(-12px) scale(.97)'}],{duration:180,easing:'ease-in',fill:'forwards'});
      draft.classList.add('is-leaving');
      await exit.finished.catch(()=>{});
      animations.forEach(animation=>animation.cancel());exit.cancel();
    }
    const chosen=G.chooseSkill(id);
    if(chosen){draft.close();$('game').focus({preventScroll:true});}
    draft.classList.remove('is-selecting','is-leaving');button.classList.remove('is-selected');
    cards.forEach(card=>card.disabled=false);selecting=false;
  }
  const icons=()=>lucide.createIcons();
  const rarity=skill=>`<span class="skill-rarity" data-tier="${skill.tier}"><span class="rarity-dot"></span>${G.skillTiers[skill.tier].name}</span>`;
  const chance=skill=>`<span class="skill-chance" title="每关按权重不重复抽取三个技能，此值为该技能进入三选一的概率">本轮出现率 ${(skill.chance*100).toFixed(2)}%</span>`;
  function update(){
    const owned=G.skillCatalog.filter(s=>G.skillRank(s.id)),key=JSON.stringify(G.state.skills);
    $('skill-count').textContent=owned.length?'本关生效':'待选择';
    if(key!==ownedKey){
      ownedKey=key;$('owned-skills').replaceChildren();
      if(!owned.length)$('owned-skills').textContent='每关选择一个强力被动';
      for(const skill of owned){const tag=document.createElement('span');tag.textContent=G.skillTiers[skill.tier].name+' · '+skill.name+' · 通关失效';tag.title=skill.describe(1);$('owned-skills').append(tag);}
      if(owned.length>6){const tag=document.createElement('span');tag.textContent='另 '+(owned.length-6)+' 种';$('owned-skills').append(tag);}
    }
     if(G.phase==='draft'){
       $('play-status').textContent='选择本关被动';
       lastOptions=G.state.draft?.options||lastOptions;
      const nextKey=JSON.stringify(G.state.draft)+key;
      if(nextKey!==draftKey){
         draftKey=nextKey;$('draft-options').replaceChildren();
        for(const id of G.state.draft.options){
          const skill=G.skillCatalog.find(s=>s.id===id),rank=G.skillRank(id),button=document.createElement('button');
          button.className='skill-card';button.dataset.skill=id;button.setAttribute('aria-label','本关选择'+skill.name);
          button.innerHTML=`<span class="skill-card-top"><span class="skill-emblem"><i data-lucide="${skill.icon}"></i></span>${rarity(skill)}</span><h3>${skill.name}</h3><span class="skill-family">${skill.family} / 通关后失效</span><p>${skill.describe(1)}</p>${chance(skill)}<span class="skill-pick">选择此技能<i data-lucide="arrow-up-right"></i></span>`;
          button.onclick=()=>selectSkill(id,button);$('draft-options').append(button);
        }
        icons();
      }
      if(!draft.open&&!window.SlingBreakIntro?.active)draft.showModal();
    }else{
      if(draft.open)draft.close();
    }
  }
   let uiQueued=false;
   G.ui=()=>{
     if(uiQueued)return;
     uiQueued=true;
      requestAnimationFrame(()=>{uiQueued=false;baseUi();update();G.updateAchievementUI?.();});
   };
  draft.addEventListener('cancel',e=>e.preventDefault());
  draft.addEventListener('keydown',e=>{if(e.key==='Escape')e.stopPropagation();});
  $('open-skills').onclick=()=>{
    libraryPaused=G.paused;G.paused=true;G.drag=null;G.audio.sync();$('library-grid').replaceChildren();
     const options=G.state.draft?.options||lastOptions;
     for(const id of options){const skill=G.skillCatalog.find(s=>s.id===id);if(!skill)continue;const item=document.createElement('article');item.className='library-item';item.innerHTML=`<span class="skill-emblem"><i data-lucide="${skill.icon}"></i></span><h3>${skill.name}</h3><span class="skill-family">${skill.family}</span><p>${skill.describe(1)}</p>`;$('library-grid').append(item);}
     if(!library.open)library.showModal();if(options.length)icons();
  };
   library.addEventListener('click',e=>{if(e.target===library)library.close();});
  library.addEventListener('close',()=>{G.paused=libraryPaused;G.audio.sync();G.ui();});
  G.ui();
})();
