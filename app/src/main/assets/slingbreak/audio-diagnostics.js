/* Opt-in diagnostics: append ?audioDebug=1, reproduce, then export JSON. */
(() => {
  'use strict';
  const enabled=new URLSearchParams(location.search).get('audioDebug')==='1';
  const capacity=4000,entries=new Array(capacity);
  let count=0,next=0,sequence=0,lastInput=null,observer=null,timer=null;
  const started=new Date().toISOString(),startedAt=performance.now();
  const errorInfo=error=>({name:error?.name||'Error',message:String(error?.message||error)});
  function record(event,data={}){
    if(!enabled)return;
    entries[next]={sequence:++sequence,atMs:performance.now(),event,...data};
    next=(next+1)%capacity;count=Math.min(count+1,capacity);
  }
  function snapshot(){
    try{return window.Game?.audio?.diagnosticSnapshot?.()||{state:'not-created'};}
    catch(error){return {snapshotError:errorInfo(error)};}
  }
  function report(){
    return {
      version:2,started,startedAtMs:startedAt,exported:new Date().toISOString(),
      environment:{userAgent:navigator.userAgent,platform:navigator.platform,
        hardwareConcurrency:navigator.hardwareConcurrency,deviceMemory:navigator.deviceMemory,
        secureContext:window.isSecureContext,visibility:document.visibilityState,
        performanceTimeOrigin:performance.timeOrigin},
      note:'Event times use performance.now() milliseconds; audio times and baseLatency/outputLatency use seconds. Rate limits use performance.now(). Output timestamps/latencies are browser reports, not microphone measurements. timestampOffsetMs is a signed clock offset, not audio latency; negative values mark future timestamps. highBaseLatency only flags a reported baseLatency of at least 50 ms, not its cause. Missing values mean unavailable. Frame gaps and long tasks do not prove audio hardware delay.',
      totalEvents:sequence,droppedEvents:sequence-count,current:snapshot(),
      entries:Array.from({length:count},(_,i)=>entries[(next-count+i+capacity)%capacity])
    };
  }
  function download(){
    record('export',{audio:snapshot()});
    const url=URL.createObjectURL(new Blob([JSON.stringify(report(),null,2)],{type:'application/json'}));
    const link=document.createElement('a');link.href=url;link.download='slingbreak-audio-'+Date.now()+'.json';
    document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),60000);
  }
  window.SlingAudioDiagnostics={enabled,record,errorInfo,snapshot,report,download,
    get lastInput(){return lastInput;}};
  if(!enabled)return;
  for(const type of ['pointerdown','pointerup','touchend','click','keydown']){
    document.addEventListener(type,event=>{
      if(event.target?.closest?.('[data-audio-diagnostics]'))return;
      const now=performance.now(),stamp=event.timeStamp;
      lastInput={type,atMs:now,eventTimeStamp:stamp,
        dispatchDelayMs:stamp>0&&stamp<=now&&now-stamp<60000?now-stamp:null,
        pointerType:event.pointerType||null,trusted:event.isTrusted};
      record('input',{input:lastInput,audio:snapshot()});
    },{capture:true,passive:true});
  }
  try{
    if(window.PerformanceObserver&&PerformanceObserver.supportedEntryTypes?.includes('longtask')){
      observer=new PerformanceObserver(list=>{for(const task of list.getEntries())record('long-task',{startMs:task.startTime,durationMs:task.duration});});
      observer.observe({entryTypes:['longtask']});
    }
  }catch(error){record('observer-error',{error:errorInfo(error)});}
  let previous=null;
  function sample(){
    const now=performance.now(),audio=snapshot();
    const elapsedMs=previous?now-previous.atMs:null;
    record('sample',{audio,elapsedMs,audioAdvanceMs:previous&&Number.isFinite(audio.currentTime)&&Number.isFinite(previous.audio.currentTime)?(audio.currentTime-previous.audio.currentTime)*1000:null});
    previous={atMs:now,audio};
  }
  function visibility(){
    record('visibility',{state:document.visibilityState,audio:snapshot()});
    clearInterval(timer);previous=null;
    if(!document.hidden){sample();timer=setInterval(sample,1000);}
  }
  document.addEventListener('visibilitychange',visibility);visibility();
  const panel=document.createElement('div');panel.dataset.audioDiagnostics='true';
  panel.style.cssText='position:fixed;right:8px;bottom:8px;z-index:2147483647;background:white;color:black;padding:8px;border:1px solid #777;display:flex;gap:8px;flex-wrap:wrap;max-width:90vw';
  function button(text,action){const b=document.createElement('button');b.type='button';b.textContent=text;b.style.cssText='min-height:44px;padding:8px';b.onclick=action;panel.append(b);}
  button('标记音频延迟',()=>record('user-delay-marker',{audio:snapshot()}));
  button('导出音频日志',download);
  button('查看日志',()=>{
    const dialog=document.createElement('dialog'),text=document.createElement('textarea'),close=document.createElement('button');
    dialog.dataset.audioDiagnostics='true';text.readOnly=true;text.value=JSON.stringify(report(),null,2);
    text.style.cssText='width:75vw;height:60vh';close.textContent='关闭';close.style.minHeight='44px';
    close.onclick=()=>dialog.close();dialog.onclose=()=>dialog.remove();dialog.append(text,close);document.body.append(dialog);dialog.showModal();text.select();
  });
  document.body.append(panel);
  record('diagnostics-start',{longTaskObserver:!!observer});
})();
