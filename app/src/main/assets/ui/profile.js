'use strict';
const Characters = (() => {
  let searchRegion = 'EU', query = '', adding = false, removeKey = '';
  let itemSelection;
  const migratedProfiles = new Set();
  const labels = {Normal:'PvE', PvP:'PvP', Roleplay:'RP', 'Roleplay PvP':'RP-PvP', Mining:'Горное дело', Blacksmithing:'Кузнечное дело', Skinning:'Снятие шкур', Leatherworking:'Кожевничество', Engineering:'Инженерное дело', Alchemy:'Алхимия', Herbalism:'Травничество', Enchanting:'Наложение чар', Tailoring:'Портняжное дело', Cooking:'Кулинария', Fishing:'Рыбная ловля', 'First Aid':'Первая помощь'};
  Object.assign(labels,{Holy:'Свет',Protection:'Защита',Retribution:'Воздаяние',Discipline:'Послушание',Shadow:'Тьма',Arcane:'Тайная магия',Fire:'Огонь',Frost:'Лёд',Elemental:'Стихии',Enhancement:'Совершенствование',Restoration:'Исцеление',Balance:'Баланс','Feral Combat':'Сила зверя',Combat:'Бой',Assassination:'Ликвидация',Subtlety:'Скрытность','Beast Mastery':'Повелитель зверей',Marksmanship:'Стрельба',Survival:'Выживание',Affliction:'Колдовство',Demonology:'Демонология',Destruction:'Разрушение',Arms:'Оружие',Fury:'Неистовство'});
  const slots = {HEAD:'Голова', NECK:'Шея', SHOULDER:'Плечи', BACK:'Спина', CHEST:'Грудь', SHIRT:'Рубашка', TABARD:'Гербовая накидка', WRIST:'Запястья', HANDS:'Кисти рук', WAIST:'Пояс', LEGS:'Ноги', FEET:'Ступни', FINGER_1:'Кольцо 1', FINGER_2:'Кольцо 2', TRINKET_1:'Аксессуар 1', TRINKET_2:'Аксессуар 2', MAIN_HAND:'Правая рука', OFF_HAND:'Левая рука', RANGED:'Дальний бой'};
  const stats = {HEALTH:'Здоровье', MANA:'Мана', RAGE:'Ярость', ENERGY:'Энергия', STRENGTH:'Сила', AGILITY:'Ловкость', STAMINA:'Выносливость', INTELLECT:'Интеллект', SPIRIT:'Дух', ATTACKPOWER:'Сила атаки', ATTACK_POWER:'Сила атаки', SPELL_POWER:'Сила заклинаний', ARMOR:'Броня', CRITICALSTRIKE:'Критический удар', DODGE:'Уклонение', PARRY:'Парирование', BLOCK:'Блокирование', ATTACKSPEED:'Скорость атаки', ITEMLEVEL:'Уровень предметов'};
  Object.assign(stats, {MOVEMENTSPEED:'Скорость бега', SPELLPOWER:'Сила заклинаний', MANAREGEN:'Регенерация маны', DAMAGE:'Урон', ATTACKDPS:'Урон в секунду', HASTE:'Скорость', MASTERY:'Искусность', BONUSARMOR:'Доп. броня', LEECH:'Самоисцеление', VERSATILITY:'Универсальность', AVOIDANCE:'Избегание'});
  const leftSlots = ['HEAD','NECK','SHOULDER','BACK','CHEST','SHIRT','TABARD','WRIST'];
  const rightSlots = ['HANDS','WAIST','LEGS','FEET','FINGER_1','FINGER_2','TRINKET_1','TRINKET_2'];
  const weaponSlots = ['MAIN_HAND','OFF_HAND','RANGED'];
  const slotOrder = [...leftSlots, ...rightSlots, ...weaponSlots];
  const qualities = {POOR:'Низкое', COMMON:'Обычное', UNCOMMON:'Необычное', RARE:'Редкое', EPIC:'Эпическое', LEGENDARY:'Легендарное', ARTIFACT:'Артефакт', HEIRLOOM:'Наследуемое'};
  const optionalStats = new Set(['MOVEMENTSPEED','HASTE','MASTERY','BONUSARMOR','LEECH','VERSATILITY','AVOIDANCE']);
  const selected = () => state.characters?.characters.find(c => c.key === state.characters.selected);
  const media = (url, revision) => url ? `/media?url=${encodeURIComponent(url)}${revision ? `&amp;v=${encodeURIComponent(revision)}` : ''}` : 'classic/portrait.png';
  const fullName = c => escape(`${c.name} ${c.surname}`);
  function saved() { return state.characters?.characters || []; }
  function refreshOldEquipment() {
    const character = selected();
    if (!character || character.visualsVersion === 2 || migratedProfiles.has(character.key)) return;
    if (['searching','loading'].includes(state.characters.status)) return;
    migratedProfiles.add(character.key);
    window.Pulse?.refreshCharacter?.();
  }
  function equipmentSlot(slot, item, activeSlot) {
    const label = slots[slot] || slot;
    const quality = Object.hasOwn(qualities, item?.quality) ? item.quality.toLowerCase() : 'common';
    const placeholder = icon(slot === 'MAIN_HAND' || slot === 'RANGED' ? 'swords' : 'shield');
    return `<button class="gear-slot ${item ? `equipped quality-${quality}` : 'empty'}${item?.transmog ? ' has-transmog' : ''}" data-action="character-slot" data-value="${escape(slot)}"
      aria-pressed="${slot === activeSlot}" aria-controls="gear-tooltip" aria-label="${escape(`${label}: ${item?.name || 'пустой слот'}${item?.transmog ? ', есть трансмог' : ''}`)}">
      ${placeholder}${item?.icon ? `<img class="gear-icon" src="${media(item.icon)}" alt="" width="40" height="40">` : ''}
    </button>`;
  }
  function paperDoll(character) {
    const gear = new Map((character.gear || []).filter(item => item.slot).map(item => [item.slot, item]));
    const active = itemSelection?.key === character.key ? itemSelection.slot : '';
    const slotButton = slot => equipmentSlot(slot, gear.get(slot), active);
    const extras = [...gear.keys()].filter(slot => !slotOrder.includes(slot));
    const item = gear.get(active);
    const quality = Object.hasOwn(qualities, item?.quality) ? item.quality.toLowerCase() : 'common';
    const tooltip = active ? `<div class="gear-tooltip quality-${quality}" tabindex="-1" role="region" aria-label="Сведения о предмете">
      <div class="gear-tooltip-title"><strong>${escape(item?.name || slots[active] || active)}</strong>
      <button data-action="character-slot-close" aria-label="Закрыть сведения о предмете">×</button></div>
      ${item ? `<p class="fine">${escape(slots[active] || active)}${qualities[item.quality] ? ` · ${qualities[item.quality]}` : ''}</p>
        ${item.level ? `<p class="fine">Уровень предмета ${number(item.level)}</p>` : ''}
        ${item.transmog ? `<p class="gear-transmog">Трансмог · ${/^Hidden\s/.test(item.transmog.name) ? 'Слот скрыт' : escape(item.transmog.name)}</p>` : ''}
        ${(item.stats || []).map(stat => `<p class="gear-bonus">${stat.value > 0 ? '+' : ''}${percent.format(stat.value)} ${escape(stats[stat.type] || stat.type)}</p>`).join('')}`
      : '<p class="fine">Пустой слот</p>'}</div>` : '';
    return `<div class="paper-doll" role="group" aria-label="Экипировка персонажа">
      <div class="gear-rail">${leftSlots.map(slotButton).join('')}</div>
      <div class="character-stage">
        <img class="character-render ${character.render ? '' : 'render-portrait'}" src="${media(character.render || character.avatar, character.checkedAt)}" data-fallback="${media(character.avatar, character.checkedAt)}" alt="${character.render ? 'Снимок персонажа' : 'Портрет персонажа'}" fetchpriority="high">
      </div>
      <div class="gear-rail">${rightSlots.map(slotButton).join('')}</div>
      <div class="gear-weapons">${weaponSlots.map(slotButton).join('')}${extras.map(slotButton).join('')}</div>
      <div id="gear-tooltip">${tooltip}</div>
      ${[...gear.values()].some(item => item.transmog) ? '<p class="gear-note fine">Снимок профиля. Трансмог указан в слотах с фиолетовой меткой.</p>' : ''}
    </div>`;
  }
  function characteristics(character) {
    const entries = (character.stats || []).filter(stat => stat.type !== 'ITEMLEVEL' && !(stat.value === 0 && optionalStats.has(stat.type)));
    const baseTypes = new Set(['HEALTH','MANA','RAGE','ENERGY','STRENGTH','AGILITY','STAMINA','INTELLECT','SPIRIT']);
    const base = entries.filter(stat => baseTypes.has(stat.type));
    const combat = entries.filter(stat => !baseTypes.has(stat.type));
    const group = (title, data) => data.length ? `<div><h4>${title}</h4><ul class="character-data">${data.map(stat => {
      const value = typeof stat.min === 'number' && typeof stat.max === 'number'
        ? `${percent.format(stat.min)}–${percent.format(stat.max)}`
        : `${percent.format(stat.value)}${stat.percentage ? '%' : ''}`;
      return `<li><span>${escape(stats[stat.type] || stat.type)}</span><b>${value}</b></li>`;
    }).join('')}</ul></div>` : '';
    return `<section class="character-attributes"><h3>Характеристики</h3>
      ${entries.length ? `<div class="attribute-groups">${group('Основные', base)}${group('Боевые', combat)}</div>` : '<p class="fine">Характеристики пока недоступны.</p>'}
    </section>`;
  }
  function dismissEquipment() {
    if (!itemSelection || itemSelection.key !== selected()?.key) return false;
    const slot = itemSelection.slot;
    itemSelection = undefined;
    render();
    [...app.querySelectorAll('[data-action="character-slot"]')].find(button => button.dataset.value === slot)?.focus();
    return true;
  }
  function searchForm() {
    const board = state.characters || {};
    const busy = board.status === 'searching' || board.status === 'loading';
    return `<article class="panel character-search"><h3>Добавить персонажа</h3>
      <p class="fine">Публичный профиль Blizzard · имя и фамилия из игры</p>
      ${choices([['EU','Европа'],['US','Америка']], searchRegion, 'character-region', 'Регион персонажа')}
      <form id="character-search-form"><label for="character-query">Имя персонажа</label>
        <div class="character-search-row"><input id="character-query" name="character" type="search" autocomplete="off" maxlength="80" minlength="2" placeholder="Имя Фамилия" value="${escape(query)}" required>
        <button class="red-button" type="submit" ${busy ? 'disabled' : ''}>${busy ? 'Поиск…' : 'Найти'}</button></div></form>
      <p class="fine">Поиск доступен с 10-го уровня. Изменения в профиле могут появиться с задержкой после выхода из игры.</p>
      ${board.status === 'checked' && board.query && !board.results.length ? '<p role="status">Ничего не найдено. Проверь обе части имени и регион.</p>' : ''}
      ${(board.results || []).map(c => `<button class="character-result" data-action="character-load" data-region="${escape(c.region)}" data-value="${escape(c.slug)}">
        <img src="${media(c.avatar)}" alt="" width="44" height="44"><span><strong>${fullName(c)}</strong><small>${name(c.className)} · ${number(c.level)} ур. · ${escape(labels[c.ruleset] || c.ruleset)} · ${escape(c.region)}</small></span></button>`).join('')}
    </article>`;
  }
  function accountPanel() {
    const a = state.account;
    if (!a?.configured) return '';
    return `<article class="panel"><h3>Battle.net</h3>${a.error ? `<p class="character-error" role="status">${escape(a.error)}</p>` : ''}
      ${a.account ? `<p>${escape(a.account.battleTag || 'Аккаунт подключён')}</p>
        <div class="dialog-actions"><button class="red-button" data-action="account-refresh">Обновить список</button><button class="text-button" data-action="account-logout">Выйти</button></div>
        ${(a.account.characters || []).map(c => `<button class="character-result" data-action="character-load" data-region="${escape(c.region)}" data-value="${escape(c.slug)}"><span><strong>${fullName(c)}</strong><small>${number(c.level)} ур. · ${escape(c.region)}</small></span></button>`).join('')}`
      : `<p class="fine">Вход откроется на странице Battle.net. Пароль не передаётся приложению.</p><div class="dialog-actions"><button class="red-button" data-action="account-login" data-value="EU">Войти · EU</button><button class="red-button" data-action="account-login" data-value="US">Войти · US</button></div>`}
      ${['loading','signing-in'].includes(a.status) ? '<p class="fine" role="status">Ожидаем завершения входа или обновляем данные…</p>' : ''}</article>`;
  }
  function screen() {
    const board = state.characters || {characters:[],results:[],status:'saved'}, c = selected();
    const selector = saved().length > 1 ? `<div class="character-list">${saved().map(item => `<button data-action="character-select" data-value="${escape(item.key)}" aria-pressed="${item.key === c?.key}">${fullName(item)}<small>${name(item.className)} · ${number(item.level)} · ${escape(item.region)}</small></button>`).join('')}</div>` : '';
    const error = board.error ? `<p class="panel character-error" role="status">${escape(board.error)}</p>` : '';
    let body = '';
    if (c) {
      body = `<article class="panel character-card"><div class="character-identity"><img class="character-avatar" src="${media(c.avatar, c.checkedAt)}" alt="Портрет персонажа" width="64" height="64">
        <div><h3>${fullName(c)}</h3><p>${name(c.race)} · ${name(c.className)}</p><p class="${c.faction === 'Alliance' ? 'alliance' : 'horde'}">${name(c.faction)} · ${escape(c.region)} · ${escape(labels[c.ruleset] || c.ruleset)}</p></div></div>
        ${c.guild ? `<p class="character-guild">&lt;${escape(c.guild)}&gt;</p>` : ''}
        <div class="character-level">Уровень ${number(c.level)}${c.itemLevel ? ` · Уровень предметов ${number(c.itemLevel)}` : ''}</div>
        ${paperDoll(c)}
        ${characteristics(c)}
        <div class="character-footer">
          <p class="fine">${c.observedAt ? `Данные персонажа · ${escape(timestamp(c.observedAt))}` : c.checkedAt ? `Проверено · ${escape(timestamp(c.checkedAt))}` : ''}</p>
          <button class="red-button" data-action="character-talents">Мой билд</button>
        </div>
      </article>
      <article class="panel"><h3>Таланты в игре</h3>
        ${c.specs?.length ? `<ul class="character-data">${c.specs.map(spec => `<li><span>${escape(labels[spec.name] || spec.name)}</span><b>${number(spec.points)}</b></li>`).join('')}</ul>` : '<p class="fine">Распределение талантов пока недоступно.</p>'}</article>
      <article class="panel"><h3>Профессии</h3>${c.professions?.length ? `<ul class="character-data">${c.professions.map(job => `<li><span>${escape(labels[job.name] || job.name)}</span><b>${number(job.skill)} / ${number(job.max)}</b></li>`).join('')}</ul>` : '<p class="fine">Данные о профессиях пока недоступны.</p>'}</article>
      <div class="character-manage"><button class="red-button" data-action="character-add">${adding ? 'Скрыть поиск' : 'Добавить ещё'}</button>
        <button class="text-button" data-action="character-remove" data-value="${escape(c.key)}">Убрать из моих персонажей</button></div>
      ${removeKey === c.key ? `<aside class="panel"><p>Убрать ${fullName(c)} с этого телефона? Персонаж в игре сохранится.</p><div class="dialog-actions"><button class="red-button" data-action="character-remove-confirm" data-value="${escape(c.key)}">Убрать</button><button class="text-button" data-action="character-remove-cancel">Оставить</button></div></aside>` : ''}`;
    }
    return section('Мой персонаж') + accountPanel() + selector + error + body + ((!c || adding) ? searchForm() : '');
  }
  function action(action, value, button) {
    switch(action) {
      case 'account-login': window.Pulse?.loginAccount?.(value); return true;
      case 'account-refresh': window.Pulse?.refreshAccount?.(); return true;
      case 'account-logout': window.Pulse?.logoutAccount?.(); return true;
      case 'character-region': searchRegion = value; break;
      case 'character-load': window.Pulse?.loadCharacter(button.dataset.region, value); return true;
      case 'character-select': window.Pulse?.selectCharacter(value); return true;
      case 'character-slot': {
        const character = selected();
        if (!character) return true;
        if (itemSelection?.key === character.key && itemSelection.slot === value) return dismissEquipment();
        itemSelection = {key:character.key, slot:value};
        render();
        const tooltip = app.querySelector('.gear-tooltip');
        tooltip?.focus({preventScroll:true});
        tooltip?.scrollIntoView({block:'nearest'});
        return true;
      }
      case 'character-slot-close': return dismissEquipment();
      case 'character-add': adding = !adding; break;
      case 'character-remove': removeKey = value; break;
      case 'character-remove-cancel': removeKey = ''; break;
      case 'character-remove-confirm': window.Pulse?.removeCharacter(value); removeKey = ''; return true;
      default: return false;
    }
    render(); return true;
  }
  function submit() {
    const input = app.querySelector('#character-query');
    if (input) query = input.value.trim();
    if (query.length < 2) return;
    window.Pulse?.searchCharacters(searchRegion, query);
  }
  function input(value) { query = value; }
  return {screen, action, submit, input, selected, media, refreshOldEquipment, dismissEquipment};
})();
