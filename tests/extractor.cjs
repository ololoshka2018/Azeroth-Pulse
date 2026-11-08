const fs=require('fs');
const vm=require('vm');
const assert=require('assert/strict');
const script=fs.readFileSync(__dirname+'/../app/src/main/assets/extract-census.js','utf8');
function page(rows,date='Nov 5, 2026',release=true){
  const cells=values=>values.map(textContent=>({textContent}));
  const table={querySelectorAll(selector){
    if(selector==='thead th')return cells(['Region / Type','Characters','Balance','7 Days','Top Class']);
    if(selector==='tbody tr')return rows.map(r=>({querySelectorAll:()=>cells(r)}));
    return [];
  }};
  const document={body:{innerText:(release?'Forever has no player-visible realms.':'Beta realms')+' Last scan '+date+' latest census observation'},querySelectorAll:()=>[table]};
  return JSON.parse(JSON.stringify(vm.runInNewContext(script,{document})));
}
const pve=['EU PvE','4,942','50% / 50%','4,942','Warrior'];
const us=['US PvE','93,712','65% / 35%','93,712','Hunter'];
let result=page([pve,us]);
assert.equal(result.ok,true);assert.equal(result.realms.length,1);assert.equal(result.realms[0].id,'PVE');
assert.equal(result.realms[0].observed,4942);assert.equal(result.realms[0].online,null);assert.equal('highestObservedLevel' in result.realms[0],false);
assert.equal(result.sourceDate,'2026-11-05');
assert.equal(page([us]).ok,false,'US-only data must never enter EU');
assert.equal(page([pve],'Oct 17, 2026').ok,false,'beta snapshots must be rejected');
assert.equal(page([pve],'Nov 99, 2026').ok,false,'invalid calendar dates must be rejected');
assert.equal(page([pve],'Nov 5, 2026',false).ok,false,'old shared-realm format is not EU release data');
result=page([['EU PvP','1 046','Alliance only','1,046','Paladin']]);
assert.equal(result.realms[0].alliancePercent,null,'partial faction coverage cannot imply 100% Alliance');
result=page([['EU RP-PvP','0','38% / 62%','0','Mage']]);
assert.equal(result.realms[0].id,'RPPVP');assert.equal(result.realms[0].observed,0);assert.equal(result.realms[0].active7d,0);
assert.equal(page([['EU PvE','4.9k','50% / 50%','—','Warrior']]).ok,false,'ambiguous rounded counts must not be invented');
assert.equal(page([pve,pve]).ok,false,'duplicate realms must not be silently counted twice');
result=page([['EU PvP','1,000','70% / 70%','—','Rogue']]);
assert.equal(result.realms[0].alliancePercent,null);assert.equal(result.realms[0].active7d,null);
const encoded=vm.runInNewContext('JSON.stringify('+script+')',{document:{body:{innerText:''},querySelectorAll:()=>[]}});
assert.equal(typeof encoded,'string');assert.equal(JSON.parse(encoded).ok,false,'WebView callback must receive a JSON string');
const seed=JSON.parse(fs.readFileSync(__dirname+'/../app/src/main/assets/seed.json','utf8'));
assert.equal('leaders' in seed,false);
assert.equal('levelCap' in seed,false);
assert.equal(typeof seed.realms.PVE.total,'number');
for(const r of Object.values(seed.realms)){assert.equal(r.online??null,null);assert.equal('highestObservedLevel' in r,false);}
console.log('PASS: EU filtering, beta isolation, missing data, partial factions, zero vs unknown, invalid counts, duplicate groups, WebView encoding, bundled snapshot');
