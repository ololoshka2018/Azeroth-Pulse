'use strict';

const MODES = {
  PVE: {label:'PvE', description:'Обычный режим', icon:'shield'},
  PVP: {label:'PvP', description:'Мировое PvP', icon:'swords'},
  RP: {label:'RP', description:'Ролевая игра', icon:'book'},
  RPPVP: {label:'RP-PvP', description:'Ролевая игра и PvP', icon:'quest-book'}
};
const MODE_IDS = Object.keys(MODES);
const REGIONS = {EU:'Европа', US:'Америка'};
const NAV = {worlds:'Миры', roster:'Население', talents:'Таланты', news:'Новости', character:'Персонаж', settings:'Настройки'};
const THEMES = {
  classic:['Классика', 'Игровые рамки и текстуры'],
  royal:['Королевская', 'Тёмные карточки и золото'],
  modern:['Современная', 'Лаконичная тёмная тема'],
  parchment:['Пергамент', 'Светлый фон для чтения'],
  contrast:['Контрастная', 'Крупный текст и чёткие границы']
};
const SYMBOLS = {
  worlds:'M3 12h18M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Zm0 0a17 17 0 0 1 0 18 17 17 0 0 1 0-18Z',
  roster:'M3 20v-2a4 4 0 0 1 4-4h5a4 4 0 0 1 4 4v2m2-6a4 4 0 0 1 3 4v2M9.5 4a3 3 0 1 0 0 6 3 3 0 0 0 0-6Zm7 0a3 3 0 0 1 0 6',
  talents:'M12 6v15m0-15C8 3 4 3 2 4v14c4-1 7 0 10 3 3-3 6-4 10-3V4c-2-1-6-1-10 2Z',
  news:'M5 3h14v18H5zm3 5h8m-8 4h8m-8 4h5',
  character:'M4 12a8 8 0 0 1 16 0v5l-4 4v-7h-3v4h-2v-4H8v7l-4-4Z',
  settings:'M12 2v3m0 14v3M2 12h3m14 0h3M5 5l2 2m10 10 2 2M5 19l2-2M17 7l2-2m0 7a7 7 0 1 0-14 0 7 7 0 0 0 14 0Zm-4 0a3 3 0 1 0-6 0 3 3 0 0 0 6 0Z',
  shield:'M12 2l8 4v6c0 4-3 7-8 10-5-3-8-6-8-10V6Z',
  swords:'M3 3h4l12 12m2 6-5-5m-3 3 6-6m2-10h-4l-5 5M3 21l5-5m3 3-6-6m-2-6 12 12',
  book:'M4 5c0-1 1-2 2-2h13v18H6a2 2 0 0 1 0-4h13M4 5v14m4-11h7m-7 4h7',
  'quest-book':'M5 3h14v18H5zm4 5a3 3 0 1 1 4 2.8c-1 .4-1 1.2-1 2.2m0 3h.01',
  star:'m12 2 3 6.3 7 .9-5 4.9 1.2 6.9-6.2-3.3-6.2 3.3L7 14.1 2 9.2l7-.9Z',
  watch:'M20 10a8 8 0 1 0-2 8m2-14v6h-6'
};
const NAMES = {
  Warrior:'Воин', Paladin:'Паладин', Hunter:'Охотник', Rogue:'Разбойник', Priest:'Жрец',
  Shaman:'Шаман', Mage:'Маг', Warlock:'Чернокнижник', Druid:'Друид',
  Human:'Человек', Dwarf:'Дворф', 'Night Elf':'Ночной эльф', Gnome:'Гном',
  Undead:'Нежить', Orc:'Орк', Troll:'Тролль', Tauren:'Таурен',
  'High Order Skyborne':'Skyborne · Альянс', 'Windshaper Skyborne':'Skyborne · Орда',
  Alliance:'Альянс', Horde:'Орда'
};
const COLORS = {
  Warrior:'#cba176', Paladin:'#de8cb3', Hunter:'#a4c775', Rogue:'#e3ca68', Priest:'#dcd8cf',
  Shaman:'#6796d4', Mage:'#76c5e0', Warlock:'#a99bd3', Druid:'#e99459',
  Human:'#7aa7d9', Dwarf:'#7aa7d9', 'Night Elf':'#7aa7d9', Gnome:'#7aa7d9', 'High Order Skyborne':'#7aa7d9',
  Undead:'#d38266', Orc:'#d38266', Troll:'#d38266', Tauren:'#d38266', 'Windshaper Skyborne':'#d38266'
};
const CLASS_ICONS = new Set(['Warrior','Mage','Rogue','Druid','Hunter','Shaman','Priest','Warlock','Paladin']);
// WoWCensus may publish separate shares for English and Spanish client labels.
function rosterKey(label) {
  return label.trim().toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[\s-]+/g, ' ');
}
const ROSTER_NAMES = new Map([
  ...Object.keys(COLORS).map(label => [rosterKey(label), label]),
  ['guerrero','Warrior'], ['paladin','Paladin'], ['cazador','Hunter'], ['picaro','Rogue'],
  ['sacerdote','Priest'], ['chaman','Shaman'], ['mago','Mage'], ['brujo','Warlock'], ['druida','Druid'],
  ['no muerto','Undead'], ['orco','Orc'], ['trol','Troll'], ['cielonato formavientos','Windshaper Skyborne']
]);
const STATUS = {
  saved:'Сохранённые данные', loading:'Обновление…',
  checked:'Проверка завершена', partial:'Частичное обновление',
  offline:'Нет связи · сохранённые данные'
};
const integer = new Intl.NumberFormat('ru-RU');
const percent = new Intl.NumberFormat('ru-RU',{maximumFractionDigits:1});
const app = document.querySelector('#app');
let state;
let tab = 'worlds';
let rosterMode;
let rosterKind = 'classes';
let rosterFaction = 'all';
let regionMenuOpen = false;
let expandedArticle = null;
let appUpdatesOpen = false;
let newsFilter = 'all';
let dropsTimer;

function escape(value) {
  return String(value ?? '').replace(/[&<>"']/g, character => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]));
}
function versionLabel(value) {
  return String(value).replace(/^([1-9]\d*\.\d+)\.0$/, '$1');
}
function name(value) { return escape(NAMES[value] || value || ''); }
function number(value) { return Number.isSafeInteger(value) ? integer.format(value) : '—'; }
function symbol(key) {
  return `<svg class="game-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75"
    stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${SYMBOLS[key]}"/></svg>`;
}
function icon(key) {
  if (state.theme !== 'classic' && (state.theme !== 'royal' || key === 'watch')) return symbol(key);
  return `<img class="game-icon" src="classic/${key}.png" alt="" draggable="false">`;
}
function classIcon(key) {
  const variant = CLASS_ICONS.has(key) ? ` class-${key.toLowerCase()}` : '';
  return `<span class="class-icon${variant}" aria-hidden="true"></span>`;
}
function calendarDay(value) {
  if (!value) return 'дата неизвестна';
  return new Intl.DateTimeFormat('ru-RU',{day:'numeric',month:'short',timeZone:'UTC'}).format(new Date(value));
}
function timestamp(value) {
  if (!value) return 'время неизвестно';
  const time = new Date(value);
  if (Number.isNaN(time.getTime())) return 'время неизвестно';
  return new Intl.DateTimeFormat('ru-RU',{day:'numeric',month:'short',hour:'2-digit',minute:'2-digit',timeZoneName:'short'}).format(time);
}
function freshness(record, status, observed, label = 'Срез') {
  const initial = record?.origin === 'initial' ||
    (!record?.origin && !record?.verifiedAt && !record?.checkedAt && !record?.checked);
  const checked = record?.verifiedAt || record?.checkedAt || record?.checked;
  const outcome = status === 'loading' ? 'Проверяем…' :
    status === 'offline' ? 'Нет связи · сохранённые данные' :
    status === 'partial' ? 'Проверена часть данных' :
    initial ? 'Начальные данные' : status === 'saved' || record?.origin === 'saved' ? 'Сохранённые данные' : 'Данные проверены';
  return `<div class="data-freshness"><p>${outcome}</p>
    ${observed ? `<p>${label} · ${escape(observed.length === 10 ? calendarDay(observed) : timestamp(observed))}</p>` : ''}
    <p>${checked ? `Последняя успешная проверка · ${escape(timestamp(checked))}` : 'Успешных проверок на этом устройстве ещё нет'}</p></div>`;
}

function appUpdateDialog() {
  const update = state.appUpdates;
  const labels = {saved:update.release ? 'Сведения о последней версии сохранены' : 'Обновления ещё не проверялись', loading:'Проверяем обновления…',
    checked:update.available ? 'Доступна новая версия приложения' : 'Установлена актуальная версия',
    unpublished:'Релизы приложения на GitHub ещё не опубликованы', offline:'Проверка недоступна · сохранённые сведения'};
  const release = update.release;
  const download = state.apkDownload || {status:"idle",progress:0};
  const downloading = ["downloading","verifying"].includes(download.status);
  return `<div class="dialog-shade"><section class="panel app-dialog" role="dialog" aria-modal="true" aria-labelledby="update-heading">
    <div class="dialog-title"><h2 id="update-heading">Azeroth Pulse</h2>
      <button class="red-button" data-action="close-app-updates" aria-label="Закрыть обновления">×</button></div>
    <p>Установлена версия ${escape(versionLabel(update.installed))}</p>
    <p class="fine" role="status">${labels[update.status] || labels.saved}</p>
    ${update.checkedAt ? `<p class="fine">Проверено · ${escape(timestamp(update.checkedAt))}</p>` : ''}
    ${release ? `<h3>Версия ${escape(versionLabel(release.version))}</h3><p class="fine">Выпуск · ${escape(timestamp(release.publishedAt))}</p>
      <div class="app-release-notes">${escape(release.notes || 'Описание изменений пока не опубликовано.')}</div>` : ''}
    <div class="dialog-actions">${update.available && !downloading ? `<button class="red-button" data-action="${['ready','permission'].includes(download.status) ? 'install-app-update' : 'download-app-update'}">${['ready','permission'].includes(download.status) ? 'Установить обновление' : 'Обновить приложение'}</button>` : ''}
      <button class="red-button" data-action="check-app-updates" ${update.status === 'loading' ? 'disabled' : ''}>Проверить обновления</button></div>
    ${downloading ? `<p role="status">${download.status === 'verifying' ? 'Проверяем файл и подпись…' : `Загружаем обновление · ${download.progress}%`}</p><progress max="100" value="${download.progress}" aria-label="Загрузка обновления"></progress><button class="text-button" data-action="cancel-app-download">Отменить загрузку</button>` : ''}
    ${download.error ? `<p class="character-error" role="status">${escape(download.error)}</p>` : ''}
    ${download.status === 'permission' ? '<p>Разреши Azeroth Pulse устанавливать обновления в настройках Android, затем вернись сюда.</p>' : ''}
    ${update.available ? '<p class="fine">Загрузка из GitHub Releases внутри приложения. Проверяются SHA-256, версия и подпись. Установку подтверждает Android.</p>' : ''}
  </section></div>`;
}

function settingsScreen() {
  return section('Настройки') + `<article class="panel settings-panel">
    <h3>Azeroth Pulse</h3>
    <p>Версия ${escape(state.appUpdates.installed)} ${state.testing ? '· тестовая сборка' : ''}</p>
    <h3 id="theme-heading">Тема оформления</h3>
    <div class="theme-options" role="group" aria-labelledby="theme-heading">${Object.entries(THEMES).map(([id,[label,detail]]) =>
      `<button class="theme-choice" data-action="app-theme" data-value="${id}" aria-pressed="${id === state.theme}">
        <span class="theme-swatch" aria-hidden="true"></span>
        <span><strong>${label}</strong><small>${detail}</small></span>
      </button>`).join('')}</div>
    <h3>Размер текста</h3>${choices([[90,'Меньше'],[100,'Обычный'],[115,'Крупнее'],[130,'Большой']],state.textScale || 100,'text-scale','Размер текста')}
    <p class="fine">Настройка сохраняется на этом телефоне.</p>
    <div class="dialog-actions">
      <button class="red-button" data-action="app-updates">Обновления</button>
      <button class="red-button" data-action="open-source" data-value="github">GitHub проекта</button>
      <button class="red-button" data-action="open-source" data-value="issues">Сообщить об ошибке</button>
    </div>
    <h3>Источники</h3><p>Перепись и популярность: WoWCensus. Персонажи и новости: Blizzard. Деревья талантов: Armory WoW Forever. Подборки билдов: Mobalytics, MmonsteR, METAMMO.</p>
    <h3>О статистике</h3><p>Перепись показывает замеченных персонажей. Это выборка, а не текущий онлайн. Числа по расам и классам приблизительные.</p>
    <h3>Материалы и права</h3><p>Независимый фанатский проект. Оригинальный код — MIT. Игровые текстуры, значки, шрифт и названия World of Warcraft принадлежат Blizzard Entertainment и соответствующим правообладателям. Они не распространяются по MIT.</p>
    <p class="fine">Приложение не одобрено Blizzard. Перевод выполняется на устройстве через Google Translate / ML Kit.</p>
    <div class="dialog-actions"><button class="text-button" data-action="open-source" data-value="licenses">Лицензии и источники</button><button class="text-button" data-action="open-source" data-value="privacy">Обработка данных</button></div>
  </article>`;
}

function countLabel(value, label) {
  return `<div class="stat"><strong>${number(value)}</strong><span>${label}</span></div>`;
}
function choices(entries, selected, action, aria) {
  return `<div class="chips" role="group" aria-label="${aria}">${entries.map(([id, label]) =>
    `<button data-action="${action}" data-value="${id}" aria-pressed="${id === selected}">${label}</button>`).join('')}</div>`;
}
function modes(selected, action) {
  const items = MODE_IDS.map(id => [id, MODES[id].label]);
  return choices(items, selected, action, 'Режим ' + state.region);
}
function section(title, detail = '') {
  return `<header class="section-head"><h2>${title}</h2>${detail ? `<p>${detail}</p>` : ''}</header>`;
}
function medal(key) { return `<span class="medal">${icon(key)}</span>`; }

function factionBalance(realm) {
  const {alliance, horde, total} = realm;
  if (!Number.isSafeInteger(alliance) || !Number.isSafeInteger(horde) || alliance <= 0 || horde <= 0 || alliance + horde !== total) {
    return '<p class="fine factions">Неполное покрытие фракций</p>';
  }
  const a = alliance / total * 100;
  return `<div class="factions">
    <div class="faction-labels"><span class="alliance">Альянс ${number(alliance)} · ${percent.format(a)}%</span>
    <span class="horde">Орда ${number(horde)} · ${percent.format(100 - a)}%</span></div>
    <div class="meter" aria-hidden="true"><span style="width:${a}%"></span><span style="width:${100 - a}%"></span></div>
  </div>`;
}
function worldCard(id) {
  const info = MODES[id];
  const {realms} = state.snapshot;
  const realm = realms[id];
  const favorite = state.favorite === id;
  const label = favorite ? `Мой мир · ${info.description}` : info.description;
  const summary = realm ? `<div class="stat-grid">
    ${countLabel(realm.total,'Замечено')}${countLabel(realm.active24h,'За 24 часа')}
    </div>${factionBalance(realm)}` : '<p class="fine">Перепись пока недоступна.</p>';
  return `<article class="panel ${id.toLowerCase()} ${favorite ? 'featured' : ''}">
    <header class="realm-head">${medal(info.icon)}<div class="realm-heading"><h3>${state.region} · ${info.label}</h3><small>${label}</small></div>
      <button class="favorite" data-action="favorite" data-value="${id}" aria-pressed="${favorite}" aria-label="Избранный мир ${info.label}">${icon('star')}</button>
    </header>${summary}
    ${freshness(realm, state.sourceStatus['census.' + id] || state.sourceStatus.census, realm?.scanDate, 'Перепись')}
    <div class="panel-foot"><small>${realm ? `За 7 дней · ${number(realm.active7d)} · ${calendarDay(realm.scanDate)}` : ''}</small></div>
  </article>`;
}
function worlds() {
  const ids = [state.favorite, ...MODE_IDS.filter(id => id !== state.favorite)];
  return section(state.region === 'US' ? 'Миры Америки' : 'Миры Европы') + ids.map(worldCard).join('');
}
function roster() {
  const realm = state.snapshot.realms[rosterMode];
  let key = rosterKind;
  let total = realm?.total;
  if (rosterKind === 'classes' && rosterFaction === 'alliance') {
    key = 'classesAlliance';
    total = realm?.alliance;
  } else if (rosterKind === 'classes' && rosterFaction === 'horde') {
    key = 'classesHorde';
    total = realm?.horde;
  }
  const shares = new Map();
  for (const [label, share] of Object.entries(realm?.[key] || {})) {
    const canonical = ROSTER_NAMES.get(rosterKey(label)) || label;
    shares.set(canonical, (shares.get(canonical) || 0) + share);
  }
  const rows = [...shares].sort(([,a],[,b]) => b-a);
  const maxShare = rows[0]?.[1] || 100;
  const results = rows.map(([label, share]) => {
    const approximate = Number.isSafeInteger(total) ? `≈ ${number(Math.round(total * share / 100))}` : '—';
    return `<li>${rosterKind === 'classes' ? classIcon(label) : ''}<div class="roster-entry"><div class="roster-label"><span>${name(label)}<small>${percent.format(share)}%</small></span><strong>${approximate}</strong></div>
      <div class="roster-track" aria-hidden="true"><span style="width:${Math.max(0,Math.min(100,100*share/maxShare))}%;background-color:${COLORS[label] || '#bb9259'}"></span></div></div></li>`;
  }).join('');
  return section('Население') +
    modes(rosterMode,'roster-mode') +
    choices([['classes','Классы'],['races','Расы']],rosterKind,'roster-kind','Раздел переписи') +
    (rosterKind === 'classes' ? choices([['all','Все'],['alliance','Альянс'],['horde','Орда']],rosterFaction,'roster-faction','Фракция') : '') +
    `<article class="panel ${rosterMode.toLowerCase()}"><div class="roster-title"><h3>${state.region} · ${MODES[rosterMode].label}</h3><span>${number(total)} в выборке</span></div>
      <p class="fine">Перепись · ${calendarDay(realm?.scanDate)}</p>
      ${freshness(realm, state.sourceStatus['census.' + rosterMode] || state.sourceStatus.census, realm?.scanDate, 'Перепись')}
      ${results ? `<ul class="roster">${results}</ul>` : '<p class="fine">Данных по этой выборке пока нет.</p>'}
      <p class="fine estimate">≈ Примерное число персонажей в выборке</p>
    </article>`;
}

function newsReader(id) {
  const reader = window.Pulse ? state.news.reader : {id, status:'failed'};
  if (!reader || reader.id !== id || reader.status === 'loading') {
    return '<p class="fine" role="status">Загружаем полный текст…</p>';
  }
  if (reader.status === 'failed') {
    return `<p class="fine" role="status">Полный текст не загрузился.</p>
      <button class="red-button" data-action="read-news" data-value="${escape(id)}">Повторить</button>`;
  }
  const showOriginal = reader.showOriginal;
  const languageButton = reader.body.translation !== 'original'
    ? `<button class="red-button" data-action="article-language" data-value="${showOriginal ? 'russian' : 'original'}">${showOriginal ? 'Показать на русском' : 'Показать оригинал'}</button>` : '';
  if (showOriginal && !reader.original) {
    const failed = reader.originalStatus === 'failed';
    return `<p class="fine" role="status">${failed ? 'Оригинал не загрузился.' : 'Загружаем оригинал…'}</p>
      ${languageButton}${failed ? ' <button class="red-button" data-action="article-language" data-value="original">Повторить</button>' : ''}`;
  }
  const {blocks, translation} = showOriginal ? reader.original : reader.body;
  const translating = reader.status === 'translating';
  const note = translation === 'official' ? 'Текст Blizzard на русском' :
    translation === 'machine' ? 'Автоматический перевод · Google Translate' :
    translating ? 'Переводим полный текст…' :
    reader.status === 'translation-failed' ? 'Перевод не загрузился. Доступен английский текст.' : 'Оригинал Blizzard на английском';
  const translate = translation === 'original' && !translating && !showOriginal ?
    '<button class="red-button" data-action="translate-article">Перевести с Google</button><p class="fine">При первом переводе загрузится языковой пакет.</p>' : '';
  const text = blocks.map(block => {
    const value = escape(block.text);
    if (block.type === 'heading') return `<h4>${value}</h4>`;
    if (block.type === 'bullet') return `<p class="article-bullet" data-depth="${Math.max(0, Math.min(3, Number(block.depth) || 0))}">${value}</p>`;
    if (block.type === 'quote') return `<blockquote>${value}</blockquote>`;
    if (block.type === 'row') return `<p class="article-row">${value}</p>`;
    return `<p>${value}</p>`;
  }).join('');
  return `<p class="fine" role="status">${note}</p>${languageButton}${translate}
    <div class="article-text" lang="${translation === 'original' ? 'en' : 'ru'}">${text}</div>
    <button class="red-button" data-action="close-news" data-value="${escape(id)}">Свернуть</button>`;
}

function dropRequirement(campaign) {
  if (campaign.type === 'subscription') return 'За подписку';
  const hours = Math.floor(campaign.minutes / 60);
  const minutes = campaign.minutes % 60;
  return ['За просмотр', hours ? hours + ' ч' : '', minutes ? minutes + ' мин' : ''].filter(Boolean).join(' ');
}

function dropsScreen(campaigns, now) {
  const cards = campaigns.map(campaign => {
    const started = Date.parse(campaign.startsAt) <= now;
    return `<article class="panel news-card drops-card">
      <p class="fine">${started ? 'Идёт сейчас' : 'Скоро'} · ${dropRequirement(campaign)}</p>
      <h3>${escape(campaign.title)}</h3>
      <p class="news-summary">${escape(campaign.description)}</p>
      ${started ? '' : `<p>Начало · ${escape(timestamp(campaign.startsAt))}</p>`}
      <p>До ${escape(timestamp(campaign.endsAt))}</p>
      <button class="text-button" data-action="drop-source" data-value="${escape(campaign.id)}">Официальные условия</button>
    </article>`;
  }).join('');
  return `<section aria-label="Кампании Twitch Drops">
    ${state.drops.offline ? '<p class="fine">Проверка недоступна · показан сохранённый список</p>' : ''}
    ${cards || '<p class="panel">В списке сейчас нет активных или объявленных кампаний.</p>'}
    <div class="drops-actions">
      <button class="red-button" data-action="open-source" data-value="twitch-watch">Смотреть на Twitch</button>
      <button class="red-button" data-action="open-source" data-value="twitch-inventory">Мои Drops</button>
    </div>
    <p class="fine">Для наград за просмотр выбери прямой эфир с Drops Enabled и привяжи Battle.net к Twitch. Прогресс и награды доступны в «Моих Drops».</p>
  </section>`;
}

function newsScreen() {
  const now = Date.now();
  const campaigns = state.drops.campaigns.filter(campaign => Date.parse(campaign.endsAt) > now);
  const boundaries = campaigns.flatMap(campaign => [Date.parse(campaign.startsAt), Date.parse(campaign.endsAt)]);
  const next = Math.min(...boundaries.filter(time => time > now));
  if (Number.isFinite(next)) dropsTimer = setTimeout(render, Math.min(next - now, 2_147_483_647));
  const header = section('Новости', 'WoW: Forever · Blizzard Entertainment') +
    choices([['all','Все новости'],['saved','Избранное'],['drops','Twitch Drops']],newsFilter,'news-filter','Раздел новостей');
  if (newsFilter === 'drops') return header + dropsScreen(campaigns, now);
  const featured = campaigns.find(campaign => campaign.type === 'watch' && Date.parse(campaign.startsAt) <= now);
  const dropNotice = newsFilter === 'all' && featured ? `<aside class="panel news-card drops-card">
    <p class="fine">Twitch Drops · Идёт сейчас</p>
    <h3>${escape(featured.title)}</h3>
    <p>${dropRequirement(featured)} · до ${escape(timestamp(featured.endsAt))}</p>
    <button class="red-button" data-action="news-filter" data-value="drops">К Twitch Drops</button>
  </aside>` : '';
  const news = state.news;
  const modelLoading = news.translationStatus === 'loading';
  const translationPanel = news.untranslated > 0 ? `<aside class="panel translation-panel">
    <p>${modelLoading ? 'Готовим русский перевод…' : news.translationStatus === 'failed' ? 'Перевод пока недоступен. Можно повторить попытку.' : 'Для новых публикаций на английском доступен перевод на русский.'}</p>
    ${modelLoading ? '' : '<button class="red-button" data-action="translate-news">Перевести с Google</button><small>В первый раз приложение загрузит языковой пакет.</small>'}
    </aside>` : '';
  const cards = news.items.filter(article => newsFilter !== 'saved' || article.bookmarked).map(article => {
    const id = escape(article.id);
    const open = expandedArticle === article.id;
    return `<article class="panel news-card" id="news-${id}">
    <p class="fine">${escape(timestamp(article.publishedAt))}</p>
    ${article.unread ? '<span class="unread-label">Новое</span>' : ''}
    <h3>${escape(article.title || 'Новая публикация WoW: Forever')}</h3>
    ${article.summary ? `<p class="news-summary">${escape(article.summary)}</p>` : ''}
    ${article.translation === 'machine' ? '<p class="fine">Автоматический перевод · Google Translate</p>' : ''}
    ${article.translation === 'editorial' ? '<p class="fine">Краткое изложение на русском</p>' : ''}
    <button class="red-button" data-action="${open ? 'close-news' : 'read-news'}" data-value="${id}"
      aria-expanded="${open}" aria-controls="article-${id}">${open ? 'Свернуть' : 'Читать полностью'}</button>
    <button class="text-button news-save" data-action="bookmark-news" data-value="${id}" aria-pressed="${Boolean(article.bookmarked)}">${article.bookmarked ? '★ Сохранено' : '☆ Сохранить'}</button>
    <div class="news-reader" id="article-${id}" ${open ? '' : 'hidden'}>${open ? newsReader(article.id) : ''}</div>
    </article>`;
  }).join('');
  return header + dropNotice +
    freshness(news, news.status, null) + translationPanel +
    (cards || '<p class="fine news-empty">Новости пока не загрузились.</p>');
}

function render(scrollToTop = false) {
  clearTimeout(dropsTimer);
  const scroll = window.scrollY;
  const root = document.documentElement;
  if (state.theme === 'classic') root.removeAttribute('data-theme');
  else root.dataset.theme = state.theme;
  root.style.fontSize = `${(state.theme === 'contrast' ? 16 : 14) * (state.textScale || 100) / 100}px`;
  const focusedTheme = app.querySelector('.theme-choice:focus')?.dataset.value;
  const profileFocus = app.querySelector('#character-query:focus');
  const inputPosition = profileFocus?.selectionStart;
  Talents.rememberFolds(app);
  const focused = app.querySelector('.app-dialog :focus')?.dataset.action;
  const dialogScroll = app.querySelector('.app-dialog')?.scrollTop || 0;
  const scanDates = Object.values(state.snapshot.realms).map(realm => realm.scanDate).filter(Boolean).sort();
  const scan = scanDates[scanDates.length - 1];
  const isNews = tab === 'news';
  const isTalents = tab === 'talents';
  const isCharacter = tab === 'character';
  const isSettings = tab === 'settings';
  const showRegion = !isCharacter && !isSettings;
  const showRefresh = showRegion || (isCharacter && Characters.selected());
  const status = isCharacter ? state.characters.status : isNews ? state.news.status : isTalents ? Talents.status() : state.status;
  const statusLabel = isTalents ? ({
    loading:'Проверяем билды…', checked:'Билды проверены', partial:'Проверена часть подборок',
    offline:'Проверка недоступна · сохранённые билды', saved:'Сохранённые билды'
  })[status] : STATUS[status];
  const unread = state.news.unread;
  const body = isCharacter ? Characters.screen() : isSettings ? settingsScreen() : isNews ? newsScreen() : isTalents ? Talents.screen(state.talentStats) : tab === 'worlds' ? worlds() : roster();
  app.innerHTML = `<div class="game-window">
    <header class="window-title${showRegion ? '' : ' personal-title'}">${showRegion ? `<button class="region-toggle" data-action="region-menu" aria-label="Регион: ${REGIONS[state.region]} (${state.region})"
      aria-expanded="${regionMenuOpen}" aria-controls="region-options"><span>${state.region}</span><span aria-hidden="true">▾</span></button>` : ''}
      <div class="window-brand"><h1>Azeroth Pulse</h1><button class="app-version" data-action="app-updates"
        aria-label="Версия приложения ${escape(versionLabel(state.appUpdates.installed))} и обновления">FOREVER · v${escape(versionLabel(state.appUpdates.installed))}${state.appUpdates.available ? ' ●' : ''}</button></div>
      ${showRefresh ? `<button class="red-button refresh" data-action="refresh" ${status === 'loading' ? 'disabled' : ''}>${icon('watch')}${isCharacter && status === 'loading' ? 'Обновляем…' : 'Обновить'}</button>` : ''}
    </header>
    <div class="region-menu" id="region-options" ${regionMenuOpen ? '' : 'hidden'}>
      <p>Регион игры</p>
      ${choices(Object.entries(REGIONS).map(([id,label]) => [id, `${id} · ${label}`]), state.region, 'region', 'Регион игры')}
    </div>
    ${showRegion ? `<div class="status-line" role="status"><span>${escape(statusLabel || STATUS.saved)}</span><span>${isNews ? 'Лента Forever' : isTalents ? 'Каталог талантов' : `Перепись · ${calendarDay(scan)}`}</span></div>` : ''}
    ${state.appUpdates.available ? '<aside class="app-update-notice"><button data-action="app-updates">Доступно обновление Azeroth Pulse · Что нового →</button></aside>' : ''}
    <main${isCharacter && status === 'loading' ? ' aria-busy="true"' : ''}>${body}</main>
    </div>
    <nav class="nav" aria-label="Разделы"><div class="action-bar">${Object.entries(NAV).map(([id,label]) => {
      const count = id === 'news' ? unread : id === 'talents' ? state.talentChanges.unread : 0;
      const badge = count > 0 ? `<b class="news-badge">${count}</b>` : '';
      const alertLabel = id === 'news' ? `Новости, новых публикаций: ${unread}` :
        `Таланты, новых изменений: ${count}`;
      return `<button data-action="nav" data-value="${id}" ${tab === id ? 'aria-current="page"' : ''}${badge ? ` aria-label="${alertLabel}"` : ''}><span class="action-icon icon-${id}" aria-hidden="true">${state.theme === 'classic' ? '' : symbol(id)}${badge}</span><span>${label}</span></button>`;
    }).join('')}</div></nav>${appUpdatesOpen ? appUpdateDialog() : ''}`;
  if (appUpdatesOpen) {
    app.querySelector('.app-dialog').scrollTop = dialogScroll;
    app.querySelector(`.app-dialog [data-action="${focused || 'close-app-updates'}"]`)?.focus({preventScroll:true});
  }
  if (profileFocus && !scrollToTop) {
    const input = app.querySelector('#character-query');
    input?.focus({preventScroll:true});
    try { input?.setSelectionRange(inputPosition,inputPosition); } catch (_) {}
  }
  if (focusedTheme) app.querySelector(`.theme-choice[data-value="${focusedTheme}"]`)?.focus({preventScroll:true});
  window.scrollTo(0, scrollToTop ? 0 : scroll);
}

window.dismissPulseDialog = () => {
  if (!appUpdatesOpen) {
    if (expandedArticle !== null) { expandedArticle = null; window.Pulse?.closeNews(); render(); return true; }
    if (tab === 'character' || tab === 'settings') {
      if (tab === 'character' && Characters.dismissEquipment()) return true;
      tab = 'worlds'; render(true); return true;
    }
    return false;
  }
  appUpdatesOpen = false;
  render();
  (app.querySelector('.settings-panel [data-action="app-updates"]') || app.querySelector('[data-action="app-updates"]'))?.focus({preventScroll:true});
  return true;
};

app.addEventListener('keydown', event => {
  if (!appUpdatesOpen) return;
  if (event.key === 'Escape') { event.preventDefault(); window.dismissPulseDialog(); }
  if (event.key !== 'Tab') return;
  const buttons = [...app.querySelectorAll('.app-dialog button:not(:disabled)')];
  const index = buttons.indexOf(document.activeElement);
  if (event.shiftKey && index <= 0) { event.preventDefault(); buttons.at(-1)?.focus(); }
  else if (!event.shiftKey && index === buttons.length - 1) { event.preventDefault(); buttons[0]?.focus(); }
});

app.addEventListener('click', event => {
  const button = event.target.closest('button[data-action]');
  if (!button || !state) return;
  const {action,value} = button.dataset;
  switch (action) {
    case 'app-theme':
      if (!Object.hasOwn(THEMES, value)) return;
      if (window.Pulse?.setTheme) window.Pulse.setTheme(value);
      else { state.theme = value; render(); }
      return;
    case 'text-scale': window.Pulse?.setTextScale?.(Number(value)); if (!window.Pulse) { state.textScale = Number(value); render(); } return;
    case 'open-source': window.Pulse?.openSource?.(value); return;
    case 'character-talents': tab = 'talents'; Talents.useCharacter(Characters.selected()); render(true); Talents.load(); return;
    case 'news-filter':
      if (value === 'drops' && expandedArticle !== null) {
        expandedArticle = null;
        window.Pulse?.closeNews();
      }
      newsFilter = value;
      render(true);
      return;
    case 'drop-source': window.Pulse?.openDropSource?.(value); return;
    case 'bookmark-news': window.Pulse?.bookmarkNews?.(value); return;
    case 'install-app-update': window.Pulse?.installAppUpdate?.(); return;
    case 'cancel-app-download': window.Pulse?.cancelAppDownload?.(); return;
    case 'app-updates':
      appUpdatesOpen = true;
      render();
      window.Pulse?.checkAppUpdates?.();
      return;
    case 'close-app-updates': window.dismissPulseDialog(); return;
    case 'check-app-updates': window.Pulse?.checkAppUpdates?.(); return;
    case 'download-app-update': window.Pulse?.downloadAppUpdate?.(); return;
    case 'refresh':
      if (tab === 'character') window.Pulse?.refreshCharacter?.();
      else if (tab === 'news') window.Pulse?.refreshNews();
      else if (tab === 'talents') {
        Talents.check(true);
        window.Pulse?.refreshTalents(true);
      }
      else window.Pulse?.refresh();
      return;
    case 'region-menu': regionMenuOpen = !regionMenuOpen; break;
    case 'region':
      regionMenuOpen = false;
      render();
      if (value !== state.region) {
        if (window.Pulse) window.Pulse.selectRegion(value);
        else previewRegion(value);
      }
      return;
    case 'read-news': {
      const replaced = expandedArticle !== null && expandedArticle !== value;
      expandedArticle = value;
      render();
      if (replaced) document.getElementById('news-' + value)?.scrollIntoView({block:'start'});
      window.Pulse?.readNews(value);
      return;
    }
    case 'close-news':
      expandedArticle = null;
      window.Pulse?.closeNews();
      render();
      document.getElementById('news-' + value)?.scrollIntoView({block:'nearest'});
      return;
    case 'translate-news': window.Pulse?.translateNews(); return;
    case 'translate-article': window.Pulse?.translateArticle(); return;
    case 'article-language': window.Pulse?.showOriginal(value === 'original'); return;
    case 'favorite':
      state.favorite = value;
      window.Pulse?.favorite(value);
      break;
    case 'nav':
      if (value !== 'news' && expandedArticle !== null) {
        expandedArticle = null;
        window.Pulse?.closeNews();
      }
      regionMenuOpen = false;
      tab = value;
      render(true);
      if (tab === 'news') window.Pulse?.checkDrops?.();
      if (tab === 'talents') {
        Talents.load();
        Talents.check(false);
        window.Pulse?.refreshTalents(false);
      }
      if (tab === 'character') Characters.refreshOldEquipment();
      return;
    case 'roster-mode': rosterMode = value; break;
    case 'roster-kind': rosterKind = value; break;
    case 'roster-faction': rosterFaction = value; break;
    default:
      if (Characters.action(action,value,button)) return;
      if (tab !== 'talents' || !Talents.action(action, value)) return;
      if (action === 'talent-node' && button.classList.contains('talent-step')) {
        render();
        app.querySelector('.talent-tip')?.scrollIntoView({block:'center'});
        return;
      }
  }
  render();
});

window.refreshVisibleTalents = () => {
  if (tab === 'talents' && !document.hidden) Talents.check(false);
};
if (window.Pulse) window.setInterval(window.refreshVisibleTalents, 60_000);

app.addEventListener('change', event => {
  if (tab === 'talents' && event.target.id === 'talent-level') {
    Talents.action('talent-level', event.target.value);
    render();
    app.querySelector('#talent-level')?.focus({preventScroll:true});
  }
});

window.acceptPulseState = encoded => {
  const oldRegion = state?.region;
  const oldCharacter = state?.characters?.selected;
  state = JSON.parse(encoded);
  if (!Object.hasOwn(REGIONS, state.region)) state.region = 'EU';
  state.news ||= {items:[], unread:0, untranslated:0, status:'saved', translationStatus:'idle'};
  state.drops ||= {campaigns:[], offline:false};
  state.sourceStatus ||= {};
  state.appUpdates ||= {installed:'1.1.1', status:'saved', available:false};
  if (!Object.hasOwn(THEMES, state.theme)) state.theme = 'classic';
  state.characters ||= {characters:[],selected:'',results:[],status:'saved'};
  state.talentChanges ||= {entries:[], unread:0, latest:0};
  if (!MODE_IDS.includes(state.favorite)) state.favorite = 'PVE';
  if (oldRegion !== state.region) {
    rosterMode = state.favorite;
  }
  render(oldRegion !== state.region && tab !== 'news');
  if (tab === 'character' && oldCharacter !== state.characters.selected) Characters.refreshOldEquipment();
};

app.addEventListener('submit', event => { if (event.target.id === 'character-search-form') { event.preventDefault(); Characters.submit(); } });
app.addEventListener('input', event => { if (event.target.id === 'character-query') Characters.input(event.target.value); });
app.addEventListener('toggle', event => Talents.foldChanged(event.target), true);
app.addEventListener('error', ({target}) => {
  if (target.matches('.gear-icon')) target.hidden = true;
  if (!target.matches('.character-render')) return;
  const fallback = target.dataset.fallback;
  if (!fallback || target.getAttribute('src') === fallback) {
    target.hidden = true;
    return;
  }
  target.classList.add('render-portrait');
  target.alt = 'Портрет персонажа';
  target.src = fallback;
}, true);

let previewSnapshots;
function previewRegion(region) {
  window.acceptPulseState(JSON.stringify({...state, region, snapshot:previewSnapshots[region], favorite:'PVE'}));
}

if (window.Pulse) {
  document.documentElement.dataset.nativeInsets = '';
  window.acceptPulseState(window.Pulse.initial());
} else {
  Promise.all(['../seed.json','../seed-us.json','../news-seed.json','../drops.json'].map(file => fetch(file).then(response => response.json())))
    .then(([EU, US, articles, drops]) => {
      previewSnapshots = {EU, US};
      const items = articles.map(article => ({...article, title:article.titleRu, summary:article.summaryRu}));
      window.acceptPulseState(JSON.stringify({region:'EU', snapshot:EU, favorite:'PVE', status:'saved', drops,
        news:{items, unread:items.length, untranslated:0, status:'saved', translationStatus:'idle'}}));
    });
}
