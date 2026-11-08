/* Read-only extraction of WoWCensus's public rendered table. No private APIs. */
(function () {
  'use strict';
  const text = document.body ? document.body.innerText : '';
  // The previous shared beta must never be mistaken for the EU release.
  if (!/Forever has no player-visible realms/i.test(text)) return {ok:false, reason:'edition'};
  const scan = text.match(/Last scan\s*([A-Z][a-z]{2,8}\s+\d{1,2},\s*\d{4})/i);
  if (!scan) return {ok:false, reason:'date'};
  const months = {Jan:1,Feb:2,Mar:3,Apr:4,May:5,Jun:6,Jul:7,Aug:8,Sep:9,Oct:10,Nov:11,Dec:12};
  const parts = scan[1].match(/([A-Za-z]+)\s+(\d+),\s*(\d+)/);
  const month = months[parts[1].slice(0,3)];
  if (!month) return {ok:false, reason:'date'};
  const sourceDate = parts[3]+'-'+String(month).padStart(2,'0')+'-'+parts[2].padStart(2,'0');
  const parsed = new Date(sourceDate+'T00:00:00Z');
  if (!Number.isFinite(parsed.getTime()) || parsed.toISOString().slice(0,10)!==sourceDate) return {ok:false,reason:'date'};
  if (sourceDate < '2026-11-04') return {ok:false, reason:'beta'};
  function number(s) {
    const t=s.trim().replace(/[,\s\u00a0\u202f]/g,'');
    if (!/^\d+$/.test(t)) return null;
    const n=Number(t); return Number.isSafeInteger(n) && n>=0 ? n : null;
  }
  const realms = [];
  for (const table of document.querySelectorAll('table')) {
    const headers = [...table.querySelectorAll('thead th')].map(e=>e.textContent.trim());
    if (headers.length<5 || !/Characters/i.test(headers[1]) || !/Balance/i.test(headers[2]) || !/7\s*Days/i.test(headers[3])) continue;
    for (const tr of table.querySelectorAll('tbody tr')) {
      const c=[...tr.querySelectorAll('td')].map(e=>e.textContent.trim());
      if (c.length<5) continue;
      const match=c[0].match(/^EU\s+(PvE|PvP|RP[\s-]*PvP|RP)$/i);
      if (!match) continue;
      const id=match[1].replace(/[\s-]/g,'').toUpperCase();
      const observed=number(c[1]);
      if (observed===null) continue;
      const balance=c[2].match(/^(\d+(?:\.\d+)?)\s*%\s*\/\s*(\d+(?:\.\d+)?)\s*%$/);
      const a=balance ? Number(balance[1]) : null;
      const h=balance ? Number(balance[2]) : null;
      const validBalance=balance && a>=0 && h>=0 && a<=100 && h<=100 && Math.abs(a+h-100)<=1;
      realms.push({id, name: id==='RPPVP'?'RP PvP':id==='PVE'?'PvE':id==='PVP'?'PvP':'RP', observed, active7d:number(c[3]), online:null, alliancePercent:validBalance?a:null, hordePercent:validBalance?h:null, topClass:c[4].replace(/^Image\s*/i,''), sourceDate, sourceDatePrecision:'day'});
    }
  }
  if (!realms.length || new Set(realms.map(r=>r.id)).size!==realms.length) return {ok:false,reason:'rows'};
  return {ok:true,sourceDate,realms};
})()
