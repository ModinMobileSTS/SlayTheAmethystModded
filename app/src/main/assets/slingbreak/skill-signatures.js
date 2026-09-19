(() => {
  'use strict';
  const G=window.Game,TAU=Math.PI*2;
  // Each signature uses local coordinates so acquisition, impacts and sling auras share an identity.
  G.paintSkillSignature=(ctx,id,t,r,color,accent,stage='impact')=>{
    const p=G.reduced ? .55 : t,ease=1-(1-p)**3,expand=.22+ease*.78,turn=G.reduced?0:p*1.6;
    ctx.save();ctx.scale(r,r);ctx.strokeStyle=color;ctx.fillStyle=color;ctx.lineWidth=2/r;ctx.lineCap='round';ctx.lineJoin='round';
    const line=(x,y,tx,ty,w=2)=>{ctx.lineWidth=w/r;ctx.beginPath();ctx.moveTo(x,y);ctx.lineTo(tx,ty);ctx.stroke();};
    const path=(points,close=false,fill=false)=>{ctx.beginPath();points.forEach(([x,y],i)=>i?ctx.lineTo(x,y):ctx.moveTo(x,y));if(close)ctx.closePath();fill?ctx.fill():ctx.stroke();};
    const circle=(x,y,size,start=0,end=TAU)=>{ctx.beginPath();ctx.arc(x,y,Math.max(.001,size),start,end);ctx.stroke();};
    const diamond=(x,y,size)=>path([[x,y-size],[x+size*.5,y],[x,y+size],[x-size*.5,y]],true);
    const poly=(n,size,rotation=0)=>path(Array.from({length:n},(_,i)=>[Math.cos(i*TAU/n+rotation)*size,Math.sin(i*TAU/n+rotation)*size]),true);
    const radial=(n,inner,outer,rotation=0)=>{for(let i=0;i<n;i++){const a=i*TAU/n+rotation;line(Math.cos(a)*inner,Math.sin(a)*inner,Math.cos(a)*outer,Math.sin(a)*outer);}};
    const orbit=(rx,ry,a=0)=>{ctx.beginPath();ctx.ellipse(0,0,rx,ry,a,0,TAU);ctx.stroke();};
    const arrow=(x,y,length=.5,angle=0)=>{ctx.save();ctx.translate(x,y);ctx.rotate(angle);line(0,length*.5,0,-length*.5,3);path([[-.07,-length*.5+.12],[0,-length*.5],[.07,-length*.5+.12]]);ctx.restore();};
    const coin=(x,y,size=.12)=>{circle(x,y,size);line(x,y-size*.5,x,y+size*.5);};
    const star=(x,y,size)=>{line(x-size,y,x+size,y);line(x,y-size,x,y+size);};
    const bolt=(x,y,length=.8)=>path([[x+.08,y-length/2],[x-.1,y],[x+.09,y-.04],[x-.06,y+length/2]]);
    const snow=(x,y,size)=>{ctx.save();ctx.translate(x,y);for(let i=0;i<6;i++){ctx.rotate(TAU/6);line(0,0,0,-size);path([[-size*.18,-size*.6],[0,-size*.8],[size*.18,-size*.6]]);}ctx.restore();};
    const crosshair=(size)=>{for(let i=0;i<4;i++){ctx.save();ctx.rotate(i*TAU/4);path([[-size*.22,-size],[0,-size],[0,-size*.75]]);ctx.restore();}};
    const shards=(n,size)=>{for(let i=0;i<n;i++){const a=i*TAU/n+turn*.2;ctx.save();ctx.translate(Math.cos(a)*size,Math.sin(a)*size);ctx.rotate(a);diamond(0,0,.07+.1*(1-p));ctx.restore();}};
    const secondary=fn=>{ctx.save();ctx.strokeStyle=accent;ctx.fillStyle=accent;fn();ctx.restore();};
    const sweep=(count,fn)=>{for(let i=0;i<count;i++){ctx.save();fn(i);ctx.restore();}};
    switch(id){
      case 'nailburst':
        path([[-.35,.35],[-.2,.05],[.2,.05],[.35,.35]],true);sweep(3,i=>{ctx.rotate((i-1)*.48);arrow(0,-expand*.5,.55);});secondary(()=>shards(5,expand*.8));break;
      case 'fusepath':
        path([[-.8,.4],[-.3,-.25],[.25,.2],[.8,-.45]]);secondary(()=>{const x=-.8+ease*1.6;star(x,-.05,.16);circle(x,-.05,.23);});break;
      case 'rhythm':
        sweep(3,i=>{const x=(i-1)*.48;circle(x,0,i===2?.2:.1);line(x,.3,x,-.3-(i===2?.2:0),i===2?4:2);});secondary(()=>{circle(.48,0,expand*.45);radial(8,.65,expand*.9);});break;
      case 'reboundaim':
        path([[-.8,.6],[-.4,-.4],[.65,.1]],false);line(-.7,-.5,-.1,-.5,4);secondary(()=>{circle(.65,.1,.2);star(.65,.1,.1);});break;
      case 'overkill':
        path([[-.5,-.55],[.5,-.55],[.5,.55],[-.5,.55]],true);secondary(()=>{line(-.25,0,.25,0,3);line(0,-.25,0,.25,3);});sweep(3,i=>{const y=.7-(p+i*.22)%1*1.4;circle(.65,y,.06);});break;
      case 'pendulum':
        circle(0,-.55,.08);const swing=G.reduced?0:Math.sin(p*TAU)*.5;line(0,-.55,swing,.4,3);circle(swing,.4,.18);secondary(()=>{arrow(-.65,0,.5,-.3);arrow(.65,0,.5,.3);});break;
      case 'teslanet':
        poly(3,expand*.8,-Math.PI/2);secondary(()=>sweep(3,i=>{ctx.rotate(i*TAU/3);circle(0,-expand*.8,.12);bolt(0,-.25,.35);}));break;
      case 'undertow':
        sweep(3,i=>{const size=Math.max(.12,.95-p*.65-i*.18);circle(0,0,size,turn+i,turn+i+Math.PI*1.5);});secondary(()=>{radial(6,.1,.3,turn);circle(0,0,.12);});break;
      case 'chronicle':
        circle(0,0,.65,.2,TAU-.4);path([[.4,-.65],[.63,-.4],[.76,-.68]]);line(0,0,-Math.sin(p*TAU)*.38,-Math.cos(p*TAU)*.38,3);secondary(()=>{diamond(0,0,.28);circle(0,0,.82,Math.PI*.7,Math.PI*1.2);});break;
      case 'firewheel':
        circle(0,0,.5);ctx.rotate(turn*2);sweep(2,i=>{ctx.rotate(i*Math.PI);path([[.4,-.3],[.8,-.1],[.55,.25],[.45,.05]],true);});secondary(()=>{circle(0,0,.25);radial(8,.58,.78,turn);});break;
      case 'worldfold':
        sweep(2,i=>{const x=(i?1:-1)*(.8-ease*.55);line(x,-.8,x,.8,4);path([[x,-.5],[0,0],[x,.5]]);});secondary(()=>{line(0,-expand,0,expand,3);diamond(0,0,.35);});break;
      case 'starforge':
        circle(0,0,.3);sweep(3,i=>{ctx.rotate(i*TAU/3+turn);circle(.15,0,.5,0,Math.PI*1.3);});secondary(()=>{radial(12,.75,expand,turn);circle(0,0,.42);});break;
      case 'trident':
        sweep(3,i=>{ctx.rotate((i-1)*.32);arrow(0,-expand*.35,.8);});secondary(()=>path([[-.55,.2],[0,.4],[.55,.2]]));break;
      case 'echo':
        sweep(3,i=>{ctx.globalAlpha*=1-i*.24;ctx.translate((i-1)*.22,(i-1)*.22);arrow(0,-p*.3,.65);path([[-.3,.1],[-.3,.35],[.15,.35]]);});break;
      case 'titan':
        sweep(2,i=>{const side=i?1:-1;path([[side*.15,.5],[side*expand*.65,.15],[side*expand*.65,-.35],[side*.3,-.55]],false);});secondary(()=>poly(4,expand*.5,Math.PI/4));break;
      case 'piercer':
        path([[-.08,.65],[0,-expand],[.08,.65],[0,.4]],true);sweep(3,i=>orbit(.16+i*.09,.05,(i-1)*.12));break;
      case 'meteor':
        sweep(5,i=>{const x=(i-2)*.28,y=((p+i*.16)%1)*1.3-.65;line(x-.22,y-.48,x,y,3);diamond(x,y,.1);secondary(()=>line(x-.15,y-.42,x+.02,y-.05));});break;
      case 'hunters':
        sweep(4,i=>{ctx.rotate(i*TAU/4);const d=.8-expand*.45;path([[-.12,d+.2],[0,d],[.12,d+.2]]);line(0,d+.28,0,d+.42);});crosshair(.24);break;
      case 'seeking':
        crosshair(.7-expand*.35);circle(0,0,.28);ctx.rotate(turn);circle(0,0,.58,0,Math.PI*.7);secondary(()=>line(0,0,.7,0));break;
      case 'ricochet':
        path([[-.8,.6],[0,-.2],[.75,.55]]);line(-.35,-.3,.35,-.3,4);sweep(3,i=>circle(0,-.2,.15+i*.14+expand*.1,0,Math.PI));break;
      case 'blast':
        poly(12,expand*.65,turn*.1);radial(10,expand*.75,expand);secondary(()=>circle(0,0,expand*.42));break;
      case 'lightning':
        path([[-.8,-.4],[-.35,-.1],[.1,-.25],[.65,.4]]);[[-.8,-.4],[-.35,-.1],[.1,-.25],[.65,.4]].forEach(([x,y])=>{circle(x,y,.1);secondary(()=>star(x,y,.06));});break;
      case 'ice':
        snow(0,0,expand*.8);secondary(()=>poly(6,expand*.5,Math.PI/6));break;
      case 'shatter':
        shards(9,expand*.75);secondary(()=>radial(6,.1,expand*.5,Math.PI/6));break;
      case 'critical':
        ctx.rotate(-.35);line(-expand,-.08,expand,.08,5);secondary(()=>line(-.1,-expand*.65,.1,expand*.65,3));shards(4,expand*.6);break;
      case 'execute':
        line(-.6,-.6,.6,.6,4);line(.6,-.6,-.6,.6,4);sweep(4,i=>{ctx.rotate(i*TAU/4);path([[-.13,-.8+expand*.2],[0,-.65+expand*.2],[.13,-.8+expand*.2]]);});break;
      case 'rage':
        sweep(5,i=>{const x=(i-2)*.23;path([[x,.45],[x-.1,-.1],[x+.02,-.7+Math.abs(i-2)*.15],[x+.12,-.05],[x+.06,.3]]);});secondary(()=>radial(7,.65,.8,turn));break;
      case 'sweep':
        sweep(3,i=>{const y=(i-1)*.17;line(-expand,y,expand,y,i===1?4:1.5);});path([[expand-.2,-.25],[expand,0],[expand-.2,.25]]);break;
      case 'lance':
        diamond(0,0,expand);line(0,.9,0,-.9,3);secondary(()=>sweep(3,i=>{ctx.translate(0,(i-1)*.35);orbit(.2,.06);}));break;
      case 'pulse':
        sweep(4,i=>{const d=(p+i*.22)%1;ctx.globalAlpha*=1-d;circle(0,0,.15+d*.85);});secondary(()=>path([[-.65,0],[-.25,0],[-.12,-.2],[.05,.2],[.2,0],[.65,0]]));break;
      case 'aftershock':
        sweep(4,i=>path([[-expand,(i-2)*.16],[-.4,(i-2)*.16-.08],[0,(i-2)*.16+.06],[.4,(i-2)*.16-.1],[expand,(i-2)*.16]]));line(0,-.55,-.1,.65,3);break;
      case 'storm':
        sweep(3,i=>bolt((i-1)*.4,(i%2)*.15,1.25));secondary(()=>orbit(.8,.22));break;
      case 'blizzard':
        sweep(6,i=>{const a=i*TAU/6+turn;const d=.35+expand*.4;snow(Math.cos(a)*d,Math.sin(a)*d,.13);});circle(0,0,expand*.55,turn,turn+Math.PI*1.5);break;
      case 'prism':
        poly(3,.42,-Math.PI/2);sweep(6,i=>{ctx.strokeStyle=i%2?accent:color;const a=-Math.PI/2+(i-2.5)*.24;line(0,.12,Math.cos(a)*expand,Math.sin(a)*expand);});break;
      case 'alchemist':
        ctx.save();ctx.rotate(Math.PI/4);ctx.strokeRect(-.35,-.35,.7,.7);ctx.restore();secondary(()=>{coin(0,0,.22);star(-.55,-.35,.12);star(.55,.35,.08);});break;
      case 'mint':
        sweep(5,i=>{const y=.4-i*.17-p*.18;orbit(.36,.11);ctx.translate(0,y);orbit(.36,.11);});secondary(()=>coin(0,-.6,.16));break;
      case 'treasury':
        poly(4,.48,Math.PI/4);poly(4,.32,Math.PI/4);sweep(8,i=>{const a=i*TAU/8;coin(Math.cos(a)*expand*.85,Math.sin(a)*expand*.85,.09);});break;
      case 'bargain':
        circle(-.24,-.25,.13);circle(.24,.25,.13);line(-.35,.45,.35,-.45,3);secondary(()=>path([[-.7,-.3],[-.7,.6],[.2,.6],[.55,.25]]));break;
      case 'forge':
        path([[-.55,.18],[-.2,.18],[-.1,.5],[.3,.5],[.42,.18],[.65,.08]],false);ctx.save();ctx.rotate(-.6+p*.8);ctx.strokeRect(-.18,-.65,.48,.22);line(.02,-.45,.02,.04,4);ctx.restore();secondary(()=>radial(7,.15,expand*.7,-Math.PI/2));break;
      case 'resonance':
        poly(4,.25,0);sweep(3,i=>{ctx.rotate((i-1)*.5);orbit(.45+i*.12,.18);});break;
      case 'corehunter':
        poly(4,.2,0);sweep(4,i=>{ctx.rotate(i*TAU/4+turn);path([[.8,.3],[.55,.2],[.25,0],[.4,-.04]]);});break;
      case 'legion':
        sweep(7,i=>arrow((i-3)*.18,Math.abs(i-3)*.1-expand*.2,.5));secondary(()=>path([[-.8,.25],[-.8,.45],[.8,.45],[.8,.25]]));break;
      case 'decay':
        poly(6,.7,turn*.1);sweep(6,i=>{ctx.rotate(i*TAU/6);path([[0,-.65],[.06,-.4],[-.09,-.2],[0,-.06]]);});secondary(()=>circle(0,0,.15*(1-p)+.05));break;
      case 'rapid':
        sweep(4,i=>{const y=(i-1.5)*.25-p*.15;path([[-.35,y+.15],[0,y-.1],[.35,y+.15]]);});break;
      case 'heavy':
        path([[-.5,.25],[-.5,-.4],[0,-.65],[.5,-.4],[.5,.25]],false);secondary(()=>sweep(4,i=>{ctx.rotate(i*TAU/4);ctx.strokeRect(.35+expand*.15,-.1,.18,.2);}));line(-.7,.5,.7,.5,4);break;
      case 'growing':
        sweep(4,i=>{const size=.2+i*.17;ctx.globalAlpha*=.4+i*.2;path([[-size,.5-i*.17],[0,-.1-i*.2],[size,.5-i*.17]]);});secondary(()=>star(0,-.8,.12));break;
      case 'doubletap':
        circle(0,0,.55);circle(0,0,.38,-Math.PI/2,-Math.PI/2+p*TAU);line(0,0,Math.sin(p*TAU)*.28,-Math.cos(p*TAU)*.28,3);secondary(()=>{circle(-.7,0,.1);circle(.7,0,.1);});break;
      case 'poison':
        sweep(5,i=>{const x=Math.sin(i*2.4)*.5,y=.4-p*.8+i*.06;circle(x,y,.07+(i%3)*.04);});path([[-.25,.35],[0,.65],[.25,.35]]);break;
      case 'nova':
        sweep(3,i=>{ctx.rotate(i*TAU/3);diamond(0,-expand*.7,.2);line(0,-.12,0,-expand*.45);});secondary(()=>star(0,0,.25));break;
      case 'crossfire':
        line(-expand,0,expand,0,5);line(0,-expand,0,expand,5);secondary(()=>poly(4,expand*.5,Math.PI/4));break;
      case 'jackpot':
        sweep(3,i=>{ctx.translate((i-1)*.38,-Math.sin(p*Math.PI)*.15);ctx.strokeRect(-.15,-.28,.3,.5);coin(0,-.02,.08);});secondary(()=>{star(-.65,-.5,.15);star(.65,-.5,.15);path([[-.5,.5],[0,.65],[.5,.5]]);});break;
      case 'snowburst':
        sweep(5,i=>{const a=i*TAU/5-Math.PI/2;snow(Math.cos(a)*expand*.65,Math.sin(a)*expand*.65,.2);});break;
      case 'supernova':
        circle(0,0,.2+expand*.2);radial(16,expand*.55,expand,turn*.1);secondary(()=>{orbit(expand,expand*.28,-.35);circle(0,0,expand*.75);});break;
      case 'swarmqueen':
        poly(6,.25,Math.PI/6);sweep(8,i=>{const a=i*TAU/8+turn;ctx.translate(Math.cos(a)*expand*.7,Math.sin(a)*expand*.7);ctx.rotate(a);poly(6,.1);line(-.1,0,-.25,0);});break;
      case 'railgun':
        line(-.08,.9,-.08,-.9,4);line(.08,.9,.08,-.9,4);secondary(()=>line(0,.95,0,-.95,2));sweep(4,i=>{ctx.translate(0,(i-1.5)*.35);orbit(.26,.07);});break;
      case 'ambush':
        sweep(2,i=>{ctx.rotate(i?-.55:.55);path([[-.06,.55],[-.06,-.35],[0,-.65],[.06,-.35],[.06,.55]],true);line(-.2,.25,.2,.25,3);});break;
      case 'siphon':
        sweep(3,i=>{const a=i*TAU/3+turn;ctx.beginPath();ctx.moveTo(Math.cos(a)*.8,Math.sin(a)*.8);ctx.quadraticCurveTo(Math.cos(a+1)*.5,Math.sin(a+1)*.5,0,0);ctx.stroke();diamond(Math.cos(a)*(.8-expand*.55),Math.sin(a)*(.8-expand*.55),.1);});break;
      case 'opportunist':
        sweep(3,i=>{ctx.rotate(-.5);line((i-1)*.24,-expand*.7,(i-1)*.24,expand*.7,3);});secondary(()=>crosshair(.75-expand*.2));break;
      case 'sharpshooter':
        circle(0,0,.5);circle(0,0,.36,0,TAU*.75);crosshair(.7);line(0,-.85,0,.85);secondary(()=>star(0,0,.13));break;
      case 'bounty':
        sweep(5,i=>{const x=(i-2)*.27,y=.3-Math.abs(i)*.16;coin(x,y,.1);if(i<4)line(x+.12,y,x+.16,y-.16);});secondary(()=>path([[-.65,.65],[.05,.4],[.65,-.5]]));break;
      case 'bankshot':
        path([[-.65,.6],[0,-.2],[.65,.6]]);sweep(2,i=>arrow(i?-.55:.55,.15,.5,i?-.7:.7));secondary(()=>poly(6,.2));break;
      case 'minefield':
        sweep(3,i=>{const a=i*TAU/3;ctx.translate(Math.cos(a)*.4,Math.sin(a)*.4);circle(0,0,.22);radial(4,.24,.31,Math.PI/4);secondary(()=>circle(0,0,.07));});break;
      case 'frostfire':
        ctx.save();ctx.translate(-.23,0);snow(0,0,.5);ctx.restore();secondary(()=>{ctx.beginPath();ctx.arc(.1,0,expand*.6,-Math.PI/2,Math.PI/2);ctx.stroke();sweep(5,i=>{const a=(i-2)*.5;line(Math.cos(a)*expand*.5,Math.sin(a)*expand*.5,Math.cos(a)*expand*.85,Math.sin(a)*expand*.85);});});break;
      case 'thunderlottery':
        ctx.save();ctx.rotate(turn*.2);ctx.strokeRect(-.33,-.33,.66,.66);[[-.18,-.18],[.18,-.18],[0,0],[-.18,.18],[.18,.18]].forEach(([x,y])=>circle(x,y,.035));ctx.restore();secondary(()=>{bolt(-.65,0);bolt(.65,0);});break;
      case 'mirror':
        sweep(2,i=>{const x=(i?1:-1)*(.65-expand*.15);ctx.translate(x,0);orbit(.13,.58);arrow(i?-.2:.2,0,.45,i?-Math.PI/2:Math.PI/2);});secondary(()=>line(0,-.5,0,.5));break;
      case 'specialist':
        sweep(5,i=>{const a=i*TAU/5;ctx.translate(Math.cos(a)*.6,Math.sin(a)*.6);poly(i+3,.12,turn);});secondary(()=>poly(4,.22));break;
      case 'reaper':
        ctx.save();ctx.rotate(-.5+turn*.5);circle(0,0,expand*.7,Math.PI*.8,Math.PI*1.9);line(-.2,.7,-.2,-.55,3);path([[-.2,-.55],[.2,-.7],[.65,-.35]],false);ctx.restore();secondary(()=>diamond(0,0,.18));break;
      case 'cascade':
        sweep(4,i=>{const d=(p+i*.15)%1;ctx.translate((i-1.5)*.28,Math.sin(i*2)*.2);poly(7,.12+d*.24,i);});secondary(()=>path([[-.7,0],[-.25,.2],[.25,-.2],[.7,0]]));break;
      case 'orbital':
        orbit(.8,.25,-.3);crosshair(.4);sweep(3,i=>{const x=(i-1)*.28;line(x,-1,x,-.05,3);secondary(()=>diamond(x,-.05,.12));});break;
      case 'roulette':
        circle(0,0,.7);sweep(3,i=>{ctx.rotate(i*TAU/3+turn);line(0,-.3,0,-.7);if(i===0)arrow(.25,-.3,.3);if(i===1)poly(3,.24);if(i===2)star(.25,-.3,.1);});secondary(()=>path([[-.1,-.9],[0,-.73],[.1,-.9]],true));break;
      case 'stormfront':
        sweep(3,i=>{const y=(i-1)*.38;line(-.8,y,.8,y,3);sweep(4,j=>{const x=(j-1.5)*.35;line(x+.07,y-.25,x-.04,y-.07);});});break;
      case 'boomerang':
        ctx.rotate(turn);path([[-.7,.3],[0,-.45],[.7,.3],[0,-.1]],true);secondary(()=>{circle(0,0,.78,.2,Math.PI*1.5);path([[-.12,-.82],[0,-.78],[-.06,-.65]]);});break;
      case 'wormhole':
        sweep(2,i=>{ctx.translate(i?.4:-.4,0);orbit(.2,.65,i?-.2:.2);secondary(()=>orbit(.13,.5,i?-.2:.2));});ctx.setLineDash([3/r,5/r]);line(-.35,0,.35,0);ctx.setLineDash([]);break;
      case 'buzzsaw':
        circle(0,0,.52);circle(0,0,.2);path(Array.from({length:36},(_,i)=>{const a=i*TAU/36+turn,size=i%3===0?.85:i%3===1?.68:.8;return[Math.cos(a)*size,Math.sin(a)*size];}),true);secondary(()=>radial(6,.24,.48,turn));break;
      case 'siegebreaker':
        sweep(4,i=>{ctx.save();ctx.translate((i%2?1:-1)*expand*.42,(i<2?-1:1)*expand*.3);ctx.rotate((i-1.5)*p*.5);ctx.strokeRect(-.2,-.12,.4,.24);ctx.restore();});secondary(()=>path([[-.18,.9],[-.18,.1],[-.35,.1],[0,-.65],[.35,.1],[.18,.1],[.18,.9]],false));break;
      case 'spectral':
        ctx.setLineDash([5/r,4/r]);sweep(3,i=>{ctx.translate((i-1)*.3,0);orbit(.12,.6);});ctx.setLineDash([]);secondary(()=>{line(-.9,0,.9,0);path([[.6,-.14],[.9,0],[.6,.14]]);});break;
      case 'transmute':
        ctx.strokeRect(-.7,-.2,.35,.4);secondary(()=>{poly(3,.25,-Math.PI/2);diamond(.6,0,.3);});sweep(3,i=>star((i-1)*.35,-.55+Math.sin(i+turn)*.08,.06));line(-.25,.4,.65,.4);break;
      case 'threadweaver':
        path([[-.75,.5],[-.25,-.55],[.35,.45],[.8,-.4]]);[[-.75,.5],[-.25,-.55],[.35,.45],[.8,-.4]].forEach(([x,y])=>circle(x,y,.07));secondary(()=>{ctx.beginPath();ctx.moveTo(-.75,.55);ctx.bezierCurveTo(-.1,.95,.2,-.9,.8,-.35);ctx.stroke();});break;
      case 'infection':
        sweep(5,i=>{ctx.rotate(i*TAU/5+turn*.15);orbit(.13,.34);diamond(0,-.55*expand,.13);});secondary(()=>{circle(0,0,.13);path([[0,.3],[.1,.7],[-.15,.9]]);});break;
      case 'contract':
        path([[-.4,-.6],[.4,-.6],[.4,.5],[.2,.35],[0,.5],[-.2,.35],[-.4,.5]],true);secondary(()=>{coin(0,-.16,.18);path([[-.18,.15],[-.03,.28],[.25,-.02]]);});break;
      case 'timeslip':
        path([[-.38,-.6],[.38,-.6],[.25,-.25],[-.25,.25],[-.38,.6],[.38,.6],[.25,.25],[-.25,-.25]],true);secondary(()=>{line(-.17,-.3,.17,-.3);line(-.2,.45,.2,.45);line(0,-.05,0,.22);});circle(0,0,.8,-Math.PI/2,-Math.PI/2+(G.reduced ? .75 : 1-p*.6)*TAU);break;
    }
    // Fine secondary glints provide a shared material without flattening the silhouettes.
    if(stage!=='aura'&&!G.reduced){ctx.globalAlpha*=.55;secondary(()=>sweep(3,i=>{const a=i*2.4+turn;star(Math.cos(a)*expand*.95,Math.sin(a)*expand*.95,.025+.025*(1-p));}));}
    ctx.restore();
  };
})();
