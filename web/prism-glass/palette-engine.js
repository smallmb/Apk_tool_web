/* Dark Glass Palette Engine — no network dependency */
(function(global){
  const clamp=(v,a=0,b=1)=>Math.min(b,Math.max(a,v));
  const hexToRgb=hex=>{const h=hex.replace('#','');return {r:parseInt(h.slice(0,2),16),g:parseInt(h.slice(2,4),16),b:parseInt(h.slice(4,6),16)}};
  const rgbToHex=({r,g,b})=>'#'+[r,g,b].map(v=>Math.round(clamp(v,0,255)).toString(16).padStart(2,'0')).join('').toUpperCase();
  const mix=(a,b,t)=>{const x=hexToRgb(a),y=hexToRgb(b);t=clamp(t);return rgbToHex({r:x.r+(y.r-x.r)*t,g:x.g+(y.g-x.g)*t,b:x.b+(y.b-x.b)*t})};
  const lum=hex=>{const c=hexToRgb(hex);const q=[c.r,c.g,c.b].map(v=>{v/=255;return v<=.03928?v/12.92:Math.pow((v+.055)/1.055,2.4)});return .2126*q[0]+.7152*q[1]+.0722*q[2]};
  const rgbString=hex=>{const c=hexToRgb(hex);return `${c.r},${c.g},${c.b}`};
  function derive(palette,opts={}){
    const [c1,c2,c3,c4,c5]=palette.colors;
    const intensity=clamp(opts.intensity??.55), ambient=clamp(opts.ambientStrength??.42), tint=clamp(opts.surfaceTint??.28), accentUsage=clamp(opts.accentUsage??.34);
    const dark1=mix('#020406',c1,.18+.28*intensity);
    const dark2=mix('#05080C',c2,.12+.22*intensity);
    const surface=mix('#11161A',c1,.10+.24*tint);
    const surfaceStrong=mix('#080C10',c2,.08+.20*tint);
    const surfaceSoft=mix('#171D22',c3,.05+.12*tint);
    let accent=lum(c2)<.12?c3:c2;
    if(lum(accent)<.12) accent=c5;
    const accent2=lum(c3)<.14?c4:c3;
    const highlight=c5;
    return {
      '--palette-c1':c1,'--palette-c2':c2,'--palette-c3':c3,'--palette-c4':c4,'--palette-c5':c5,
      '--canvas-base':dark1,'--canvas-secondary':dark2,
      '--surface-base-rgb':rgbString(surface),'--surface-strong-rgb':rgbString(surfaceStrong),'--surface-soft-rgb':rgbString(surfaceSoft),
      '--accent-primary':accent,'--accent-primary-rgb':rgbString(accent),'--accent-secondary':accent2,'--accent-secondary-rgb':rgbString(accent2),
      '--highlight':highlight,'--highlight-rgb':rgbString(highlight),
      '--ambient-primary-rgb':rgbString(c2),'--ambient-secondary-rgb':rgbString(c5),
      '--text-primary':'#F3F6F5','--text-secondary':'#B7C0BD','--text-tertiary':'#8F9995',
      '--border-subtle':'rgba(255,255,255,.065)','--border-default':'rgba(255,255,255,.105)','--border-strong':'rgba(255,255,255,.16)',
      '--palette-intensity':String(intensity),'--ambient-strength':String(ambient),'--surface-tint':String(tint),'--accent-usage':String(accentUsage),'--ambient-primary-alpha':String(.04+ambient*.12),'--ambient-secondary-alpha':String(.02+ambient*.07),'--accent-glow-alpha':String(.20+accentUsage*.58)
    };
  }
  function create(presets,root=document.documentElement){
    const map=new Map(presets.map(p=>[p.id,p]));
    const state={preset:presets[0]?.id||'',intensity:.55,ambientStrength:.42,surfaceTint:.28,accentUsage:.34,effects:true,custom:null};
    const api={
      getState:()=>({...state}), getPalette:()=>state.custom||map.get(state.preset), getPresets:()=>presets.slice(),
      setPalette(id){if(!map.has(id))throw new Error('Unknown palette: '+id);state.preset=id;state.custom=null;return api.apply()},
      setCustomPalette(colors,name='custom'){if(!Array.isArray(colors)||colors.length!==5)throw new Error('Custom palette requires exactly 5 colors');state.custom={id:'CUSTOM',name,colors:colors.map(v=>v.toUpperCase()),effect:{type:'none'}};return api.apply()},
      setOptions(opts={}){Object.assign(state,opts);return api.apply()},
      apply(){const p=api.getPalette();if(!p)return api;const tokens=derive(p,state);Object.entries(tokens).forEach(([k,v])=>root.style.setProperty(k,v));root.dataset.palette=p.id;root.dataset.paletteEffect=(state.effects&&p.effect?.type!=='none')?p.effect.type:'none';root.dispatchEvent(new CustomEvent('darkglass:palettechange',{detail:{palette:p,state:{...state},tokens}}));return api;},
      derive
    };
    return api;
  }
  global.DarkGlassPaletteEngine={create,derive,mix,hexToRgb,rgbToHex,lum};
})(window);
