import http from 'node:http';
import {randomBytes, createHash, timingSafeEqual} from 'node:crypto';
import {pathToFileURL} from 'node:url';

const random = () => randomBytes(32).toString('base64url');
const sha = value => createHash('sha256').update(value).digest('base64url');
const equal = (a,b) => typeof a === 'string' && typeof b === 'string' && a.length === b.length && timingSafeEqual(Buffer.from(a),Buffer.from(b));
export function createService(config, upstream = fetch) {
  const pending = new Map(), grants = new Map(), sessions = new Map(), rates = new Map();
  const origin = new URL(config.origin);
  if (origin.protocol !== 'https:' && !config.testing) throw Error('PUBLIC_ORIGIN must use HTTPS');
  const configured = Boolean(config.clientId && config.clientSecret);
  const cleanup = () => {
    const now = Date.now();
    for (const map of [pending,grants,sessions,rates]) for (const [key,value] of map) if (value.expires <= now) map.delete(key);
  };
  const json = (res,status,body) => { res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}); res.end(JSON.stringify(body)); };
  const redirect = (res,location) => { res.writeHead(302,{'Location':location,'Cache-Control':'no-store','Referrer-Policy':'no-referrer'}); res.end(); };
  async function data(url, options) {
    const response = await upstream(url,{...options,signal:AbortSignal.timeout(15000),redirect:'error'});
    if (!response.ok) throw Error(`Upstream ${response.status}`);
    const text = await response.text(); if (text.length > 2_000_000) throw Error('Response too large');
    return JSON.parse(text);
  }
  async function account(token,region) {
    const headers = {Authorization:`Bearer ${token}`};
    const [user,profile] = await Promise.all([
      data('https://oauth.battle.net/userinfo',{headers}),
      data(`https://${region.toLowerCase()}.api.blizzard.com/profile/user/wow?namespace=profile-forever-${region.toLowerCase()}&locale=en_US`,{headers})
    ]);
    const characters = [];
    for (const a of profile.wow_accounts || []) for (const c of a.characters || []) {
      if (characters.length >= 100 || typeof c.name !== 'string' || typeof c.surname !== 'string') continue;
      const slug = (c.slug || `${c.name}-${c.surname}`).toLowerCase();
      if (!/^[\p{L}]{1,32}-[\p{L}]{1,32}$/u.test(slug)) continue;
      characters.push({name:c.name,surname:c.surname,slug,region,level:c.level,className:c.playable_class?.name || c.character_class?.name || ''});
    }
    return {battleTag:typeof user.battletag === 'string' ? user.battletag : '',region,characters};
  }
  return http.createServer(async (req,res) => {
    cleanup();
    try {
      const url = new URL(req.url,origin);
      if (req.method === 'GET' && url.pathname === '/health') return json(res,200,{configured});
      if (!configured) return json(res,503,{error:'service_not_configured'});
      const ip = req.socket.remoteAddress;
      let rate = rates.get(ip);
      if (!rate) { rate = {count:0,expires:Date.now()+60000}; rates.set(ip,rate); }
      if (++rate.count > 40 || pending.size > 1000 || sessions.size > 1000) return json(res,429,{error:'try_later'});
      if (req.method === 'GET' && url.pathname === '/auth/start') {
        const region = url.searchParams.get('region'), challenge = url.searchParams.get('challenge'), mobileState = url.searchParams.get('state');
        if (!['EU','US'].includes(region) || !/^[\w-]{43}$/.test(challenge || '') || !/^[\w-]{43}$/.test(mobileState || '')) return json(res,400,{error:'invalid_request'});
        const state = random(); pending.set(state,{region,challenge,mobileState,expires:Date.now()+600000});
        const destination = new URL('https://oauth.battle.net/authorize');
        destination.search = new URLSearchParams({client_id:config.clientId,redirect_uri:new URL('/auth/callback',origin).href,response_type:'code',scope:'openid wow.profile',state}).toString();
        return redirect(res,destination.href);
      }
      if (req.method === 'GET' && url.pathname === '/auth/callback') {
        const state = url.searchParams.get('state'), transaction = pending.get(state);
        pending.delete(state);
        if (!transaction) return json(res,400,{error:'expired_login'});
        const callback = new URL('azerothpulse://oauth/callback'); callback.searchParams.set('state',transaction.mobileState);
        if (url.searchParams.has('error') || !url.searchParams.get('code')) { callback.searchParams.set('error','cancelled'); return redirect(res,callback.href); }
        const token = await data('https://oauth.battle.net/token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded',Authorization:`Basic ${Buffer.from(`${config.clientId}:${config.clientSecret}`).toString('base64')}`},body:new URLSearchParams({grant_type:'authorization_code',code:url.searchParams.get('code'),redirect_uri:new URL('/auth/callback',origin).href})});
        const details = await account(token.access_token,transaction.region);
        const code = random(); grants.set(code,{...transaction,token:token.access_token,account:details,expires:Date.now()+90000});
        callback.searchParams.set('code',code); return redirect(res,callback.href);
      }
      if (req.method === 'POST' && url.pathname === '/auth/redeem') {
        let raw = ''; for await (const chunk of req) { raw += chunk; if (raw.length > 2048) return json(res,413,{error:'oversize_request'}); }
        const body = JSON.parse(raw), grant = grants.get(body.code);
        if (!grant || !/^[\w-]{43}$/.test(body.verifier || '') || !equal(sha(body.verifier),grant.challenge)) return json(res,400,{error:'invalid_grant'});
        grants.delete(body.code);
        const session = random(); sessions.set(sha(session),{token:grant.token,region:grant.region,account:grant.account,expires:Date.now()+3600000});
        return json(res,200,{session,account:grant.account});
      }
      const match = /^Bearer ([\w-]{43})$/.exec(req.headers.authorization || '');
      const key = match ? sha(match[1]) : '', session = sessions.get(key);
      if (!session) return json(res,401,{error:'sign_in_required'});
      if (req.method === 'GET' && url.pathname === '/account') {
        session.account = await account(session.token,session.region);
        return json(res,200,session.account);
      }
      if (req.method === 'DELETE' && url.pathname === '/session') { sessions.delete(key); return json(res,200,{ok:true}); }
      json(res,404,{error:'not_found'});
    } catch { json(res,502,{error:'service_unavailable'}); }
  });
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const origin = process.env.PUBLIC_ORIGIN;
  if (!origin) throw Error('Set PUBLIC_ORIGIN to the public HTTPS address');
  createService({origin,clientId:process.env.BATTLENET_CLIENT_ID,clientSecret:process.env.BATTLENET_CLIENT_SECRET})
    .listen(Number(process.env.PORT || 8080),'127.0.0.1');
}
