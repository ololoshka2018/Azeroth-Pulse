const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const root = path.resolve(__dirname,'../app/src/main/assets');
const initial = {
  region:'EU',favorite:'PVE',status:'saved',snapshot:JSON.parse(fs.readFileSync(path.join(root,'seed.json'))),sourceStatus:{},
  appUpdates:{installed:'0.18.0',status:'checked',available:false},apkDownload:{status:'idle'},textScale:100,
  characters:{characters:[],selected:'',results:[],status:'saved'},account:{configured:false},
  talentChanges:{entries:[{id:2,className:'Mage',at:'2026-11-08T12:00:00Z',items:['Обновлён билд мага.'],unread:true},{id:1,className:'Rogue',at:'2026-11-08T11:00:00Z',items:['Изменён порядок талантов.'],unread:true}],unread:2,latest:2},
  news:{items:JSON.parse(fs.readFileSync(path.join(root,'news-seed.json'))).slice(0,3).map(a=>({...a,title:a.titleRu,summary:a.summaryRu,unread:true,bookmarked:false})),unread:3,untranslated:0,status:'saved',translationStatus:'idle'}
};
const character = {key:'eu:judgement-judy',name:'Judgement',surname:'Judy',slug:'judgement-judy',region:'EU',className:'Paladin',race:'Human',faction:'Alliance',ruleset:'Normal',level:24,itemLevel:16,guild:'Circle of Lore',avatar:'',render:'',checkedAt:'2026-11-08T15:00:00Z',observedAt:'2026-11-08T14:00:00Z',gear:[{name:'Sword of the Fallen',slot:'MAIN_HAND',quality:'RARE',level:23,stats:[{type:'STRENGTH',value:6}]}],stats:[{type:'HEALTH',value:677}],professions:[{name:'Mining',skill:116,max:150}],specs:[{name:'Holy',points:3},{name:'Retribution',points:12}]};
const mime = {'.html':'text/html','.js':'text/javascript','.css':'text/css','.json':'application/json','.png':'image/png','.ttf':'font/ttf'};
const server = http.createServer((req,res)=>{
  const file = path.resolve(root,'.'+new URL(req.url,'http://localhost').pathname);
  if (!file.startsWith(root+path.sep)) {res.writeHead(403);res.end();return;}
  try {res.setHeader('Content-Type',mime[path.extname(file)]||'application/octet-stream');res.end(fs.readFileSync(file));}
  catch {res.writeHead(404);res.end();}
});
(async()=>{
  await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const browser=await chromium.launch({headless:true,executablePath:process.env.PULSE_CHROMIUM || undefined,args:['--no-sandbox']});
  try {
    const page=await browser.newPage({viewport:{width:320,height:800}}), errors=[];
    page.on('pageerror',e=>errors.push(e.message));
    await page.addInitScript(({initial,character})=>{
      window.__model=JSON.parse(localStorage.getItem('qa-state')||JSON.stringify(initial));
      const save=()=>{localStorage.setItem('qa-state',JSON.stringify(window.__model));window.acceptPulseState?.(JSON.stringify(window.__model));};
      window.__save=save;
      const choiceKey=()=>`qa-choice-${window.__model.characters.selected||'general'}`;
      window.Pulse={initial:()=>JSON.stringify(window.__model),refresh(){},refreshNews(){},refreshTalents(){},refreshTalentCatalog(){},cachedTalentCatalog:()=>'',talentClass:()=>window.__model.characters.characters[0]?.className||'Rogue',talentLevel:()=>9,saveTalents(){},talentPreferences:()=>localStorage.getItem(choiceKey())||'{}',saveTalentPreferences:s=>localStorage.setItem(choiceKey(),s),
        checkAppUpdates(){},downloadAppUpdate(){window.__model.apkDownload={status:'downloading',progress:37};save();},installAppUpdate(){},cancelAppDownload(){window.__model.apkDownload={status:'idle'};save();},
        searchCharacters(region,q){window.__model.characters={...window.__model.characters,searchRegion:region,query:q,results:[character],status:'checked'};save();},loadCharacter(){window.__model.characters.characters=[character];window.__model.characters.selected=character.key;save();},selectCharacter(key){window.__model.characters.selected=key;save();},refreshCharacter(){},removeCharacter(){window.__model.characters.characters=[];window.__model.characters.selected='';save();},
        readNews(id){const n=window.__model.news;n.items.find(x=>x.id===id).unread=false;n.unread=n.items.filter(x=>x.unread).length;n.reader={id,status:'ready',body:{translation:'official',blocks:[{type:'p',text:'Тест полного текста новости.'}]}};save();},closeNews(){},showOriginal(){},translateNews(){},translateArticle(){},bookmarkNews(id){const a=window.__model.news.items.find(x=>x.id===id);a.bookmarked=!a.bookmarked;save();},newsViewed(){throw Error('whole-feed read is forbidden');},
        talentChangesViewed(id){const s=window.__model.talentChanges;s.entries.find(x=>x.id===id).unread=false;s.unread=s.entries.filter(x=>x.unread).length;save();},
        setTextScale(value){window.__model.textScale=value;save();},openSource(){},favorite(value){window.__model.favorite=value;save();},selectRegion(id){window.__model.region=id;save();}
      };
    },{initial,character:{...character,visualsVersion:2,gear:character.gear.map(item=>({...item,transmog:{id:2763,name:'Fisherman Knife'}}))}});
    const url=`http://127.0.0.1:${server.address().port}/ui/index.html`;
    await page.goto(url); await page.getByRole('heading',{name:'Миры Европы'}).waitFor();
    const fits=async label=>assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1),`Overflow: ${label}`);
    const nav = page.getByRole('navigation',{name:'Разделы'});
    const characterTab = nav.getByRole('button',{name:'Персонаж'});
    const settingsTab = nav.getByRole('button',{name:'Настройки'});
    const navFits = async () => assert.ok(await nav.getByRole('button').evaluateAll(buttons =>
      buttons.length === 6 && buttons.every(button => {
        const target = button.getBoundingClientRect();
        const label = button.lastElementChild.getBoundingClientRect();
        return target.width >= 44 && label.left >= target.left && label.right <= target.right;
      })), 'Bottom bar targets and labels fit');
    await fits('worlds 320');
    await navFits();
    await page.locator('[data-action="nav"][data-value="news"]').click();
    assert.equal(await page.evaluate(()=>window.__model.news.unread),3);
    await page.locator('[data-action="bookmark-news"]').first().click();
    await page.locator('[data-action="news-filter"][data-value="saved"]').click();assert.equal(await page.locator('.news-card').count(),1);
    await page.locator('[data-action="read-news"]').click();assert.equal(await page.evaluate(()=>window.__model.news.unread),2);await fits('news saved');
    await page.locator('[data-action="nav"][data-value="talents"]').click();await page.locator('.talent-node').first().waitFor();
    assert.equal(await page.evaluate(()=>window.__model.talentChanges.unread),2);
    assert.equal(await page.locator('.talent-change-log').getAttribute('open'),null);
    await page.locator('.talent-change-log > summary').click();
    await page.locator('[data-change-id="1"] > summary').click();
    await page.waitForFunction(()=>window.__model.talentChanges.unread===1);
    await fits('talents');
    await characterTab.click();await page.locator('#character-query').fill('Judgement Judy');
    await page.evaluate(()=>window.__save());assert.equal(await page.locator('#character-query').inputValue(),'Judgement Judy');
    await page.locator('#character-search-form button').click();await page.locator('[data-action="character-load"]').click();
    await page.getByRole('heading',{name:'Judgement Judy'}).waitFor();await fits('character');
    assert.equal(await characterTab.getAttribute('aria-current'),'page');
    assert.doesNotMatch(await page.locator('main').innerText(),/Armory/);
    assert.equal(await page.getByRole('button',{name:'Обновить',exact:true}).count(),1);
    const equipment = page.getByRole('group',{name:'Экипировка персонажа'});
    assert.equal(await equipment.locator('.gear-slot').count(),19);
    await page.getByRole('heading',{name:'Характеристики'}).waitFor();
    assert.equal(await page.locator('.character-attributes b').first().textContent(),'677');
    const mainHand = equipment.getByRole('button',{name:/Правая рука: Sword of the Fallen/});
    const tooltip = equipment.getByRole('region',{name:'Сведения о предмете'});
    await mainHand.click();
    assert.match(await tooltip.innerText(),/Sword of the Fallen[\s\S]*Редкое[\s\S]*\+6 Сила/);
    assert.match(await tooltip.innerText(),/Трансмог · Fisherman Knife/);
    await fits('item tooltip');
    assert.ok(await page.evaluate(()=>window.dismissPulseDialog()));
    assert.equal(await tooltip.count(),0);
    assert.equal(await mainHand.getAttribute('aria-pressed'),'false');
    await equipment.getByRole('button',{name:'Голова: пустой слот'}).click();
    assert.match(await tooltip.innerText(),/Пустой слот/);
    await tooltip.getByRole('button',{name:'Закрыть сведения о предмете'}).click();
    await page.locator('[data-action="character-talents"]').click();await page.locator('#talent-level').waitFor();assert.equal(await page.locator('#talent-level').inputValue(),'24');
    await page.locator('[data-action="talent-goal"][data-value="pvp"]').click();
    const choice=await page.evaluate(()=>JSON.parse(localStorage.getItem('qa-choice-eu:judgement-judy')));assert.equal(choice.goal,'pvp');assert.equal(choice.className,'Paladin');
    await page.reload();await page.locator('[data-action="nav"][data-value="talents"]').click();await page.locator('[data-action="talent-goal"][aria-pressed="true"]').waitFor();
    assert.equal(await page.locator('[data-action="talent-goal"][aria-pressed="true"]').getAttribute('data-value'),'pvp');
    await settingsTab.click();await page.locator('[data-action="text-scale"][data-value="130"]').click();await fits('large settings');
    assert.equal(await settingsTab.getAttribute('aria-current'),'page');
    await navFits();
    await nav.getByRole('button',{name:/Таланты/}).click();await fits('large talents');
    await characterTab.click();await fits('large character');
    await settingsTab.click();await page.locator('[data-action="text-scale"][data-value="100"]').click();
    await page.evaluate(()=>{window.__model.appUpdates={installed:'0.18.0',status:'checked',available:true,release:{version:'0.19.0',publishedAt:'2026-11-09T00:00:00Z',notes:'Тест обновления'}};window.__save();});
    await page.locator('.settings-panel [data-action="app-updates"]').click();await page.locator('[data-action="download-app-update"]').click();assert.equal(await page.locator('progress').getAttribute('value'),'37');await fits('update dialog');
    await page.locator('[data-action="cancel-app-download"]').click();await page.locator('[data-action="close-app-updates"]').click();
    assert.equal(await settingsTab.getAttribute('aria-current'),'page');
    await page.evaluate(()=>{window.__model.appUpdates={installed:'0.18.0',status:'checked',available:false};window.__save();});
    await characterTab.click();
    fs.mkdirSync(path.resolve(__dirname,'../build/ui-qa'),{recursive:true});
    await page.screenshot({path:path.resolve(__dirname,'../build/ui-qa/character-320.png'),fullPage:true});
    await page.setViewportSize({width:412,height:915});await fits('character 412');
    await navFits();
    assert.deepEqual(errors,[]);console.log('PASS: 320/412px layout, large text, per-entry read state, bookmarks, live profile flow, per-character build restore, update progress.');
  } finally {await browser.close();server.close();}
})().catch(e=>{console.error(e);server.close();process.exitCode=1;});
