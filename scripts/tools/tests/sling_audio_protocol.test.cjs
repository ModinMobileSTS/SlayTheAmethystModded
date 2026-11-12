const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const test = require('node:test');
const path = require('node:path');
const root = path.resolve(__dirname, '../../../app/build/generated/packaged-assets/common/slingbreak');

function game() {
  const calls = [], samples = new Set();
  let failed = false;
  const parameter = {setValueAtTime(){},linearRampToValueAtTime(){},exponentialRampToValueAtTime(){}};
  const node = () => ({connect(){},start(){},stop(){},gain:parameter,frequency:parameter,detune:{value:0},Q:{value:0}});
  class Offline {
    constructor(channels,frames) {this.frames=frames;}
    createGain(){return node();} createOscillator(){return node();} createBufferSource(){return node();} createBiquadFilter(){return node();}
    createBuffer(channels,frames){return {getChannelData:()=>new Float32Array(frames)};}
    async startRendering(){return {getChannelData:()=>new Float32Array(this.frames)};}
  }
  const host = {
    capabilities:()=>JSON.stringify({version:2,gain:true,ui:true,music:true,batch:true,backend:'native-aaudio'}),
    begin:()=>JSON.stringify({version:1,session:1,sampleRate:48000}),
    upload(token,id){samples.add(id);return true;},commit:()=>true,
    setState(...args){calls.push(['state',...args]);return !failed;},
    snapshot:()=>JSON.stringify({failed}),release(){calls.push(['release']);},
    playBatch(token,payload){const batch=JSON.parse(payload);calls.push(['batch',batch]);return JSON.stringify(batch.map(()=>failed?-1:1));},
    uploadMusic(...args){calls.push(['uploadMusic',...args]);return true;},
    musicState(...args){calls.push(['musicState',...args]);return true;},
    musicFade(...args){calls.push(['musicFade',...args]);return true;}
  };
  const G={state:{sound:true,volume:35},paused:false};
  const sandbox={console,performance,setTimeout,clearTimeout,Float32Array,Uint8Array,DataView,Map,Promise,Math,JSON,Error,
    btoa:s=>Buffer.from(s,'binary').toString('base64'),document:{hidden:false,addEventListener(){}},Game:G};
  sandbox.window={SlingNativeAudio:host,OfflineAudioContext:Offline,addEventListener(){}};
  vm.createContext(sandbox);
  for(const file of ['audio-recipes.js','audio-music.js','audio-native.js','audio.js'])
    vm.runInContext(fs.readFileSync(path.join(root,'src/audio',file),'utf8'),sandbox);
  return {G,calls,samples,fail(){failed=true;}};
}

test('v2 keeps gain, complete sound catalogue, paused UI and music native', async()=>{
  const {G,calls,samples}=game();
  await G.audio.preload();G.audio.unlock();
  assert.equal(G.audio.diagnosticSnapshot().backend,'native-aaudio');
  assert.equal(G.audio.diagnosticSnapshot().state,'ready');
  assert.equal(samples.size,180);
  assert.ok(calls.some(c=>c[0]==='state'&&c[2]===.35&&c[3]===false));
  for(const type of ['shoot','eyehit','abyss','forge','serpent','timestop'])G.sound(type);
  await new Promise(r=>setTimeout(r,10));
  assert.equal(calls.filter(c=>c[0]==='batch').flatMap(c=>c[1]).length,6);
  G.paused=true;G.audio.sync();G.audio.cue('pick',2);
  await new Promise(r=>setTimeout(r,10));
  assert.equal(calls.filter(c=>c[0]==='batch').at(-1)[1][0].ui,true);
  G.paused=false;G.audio.sync();
  const music=G.audio.nativeMusic;
  await music.warmMusic([{freq:220,dur:.2}]);
  await music.musicVoice('pad',performance.now(),{freq:220,dur:.2});
  await new Promise(r=>setTimeout(r,10));
  assert.ok(calls.some(c=>c[0]==='uploadMusic'));
  assert.ok(calls.some(c=>c[0]==='batch'&&c[1].some(r=>r.music&&r.bus==='pad')));
  music.musicMix({pad:1},18000);music.musicFade(0,1);
  assert.ok(calls.some(c=>c[0]==='musicFade'));
  G.state.volume=0;G.audio.sync();assert.ok(calls.some(c=>c[0]==='release'));
});

test('native failure relinquishes v2 output ownership for safe Web Audio fallback',async()=>{
  const {G,fail}=game();await G.audio.preload();G.audio.unlock();
  fail();G.audio.sync();
  assert.equal(G.audio.nativeMusic,null);
  assert.equal(G.audio.diagnosticSnapshot().backend,'web-audio');
  assert.equal(G.audio.diagnosticSnapshot().native.state,'failed');
});
