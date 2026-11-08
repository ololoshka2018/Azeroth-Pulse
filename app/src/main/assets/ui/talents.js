'use strict';

const Talents = (() => {
  const goals = [['leveling','Прокачка'], ['dungeons','Подземелья'], ['raid','Рейды'], ['pvp','PvP']];
  const levels = new Map();
  const catalogs = new Map();
  const syncStates = new Map();
  let data, pending, unavailable, playerClass, goal = 'leveling', buildIndex = 0, treeIndex = null, selectedNode;
  let showPath = false, showStats = false, showChanges = false;

  function load() {
    if (data || pending) return;
    pending = true;
    unavailable = false;
    fetch('talents-data.json').then(response => {
      if (!response.ok) throw new Error('Talent catalog unavailable');
      return response.json();
    }).then(catalog => {
      if (!catalog.classes?.Rogue) throw new Error('Talent catalog invalid');
      data = catalog;
      const saved = window.Pulse?.talentClass?.();
      playerClass = Object.hasOwn(data.classes, saved) ? saved : 'Rogue';
    }).catch(() => {
      unavailable = true;
    }).finally(() => {
      pending = false;
      if (tab === 'talents') {
        check(false);
        render();
      }
    });
  }

  function currentCatalog() { return catalogs.get(playerClass) || data.classes[playerClass]; }

  function check(force) {
    if (!data || !playerClass) return;
    if (!syncStates.has(playerClass)) {
      syncStates.set(playerClass, 'saved');
      try {
        const cached = window.Pulse?.cachedTalentCatalog?.(playerClass);
        if (cached) catalogs.set(playerClass, JSON.parse(cached));
      } catch (_) {
        // The bundled catalog is available when the local cache cannot be read.
      }
    }
    window.Pulse?.refreshTalentCatalog?.(playerClass, force);
  }

  function status() { return syncStates.get(playerClass) || 'saved'; }

  window.acceptTalentCatalog = (className, nextStatus, encoded) => {
    if (!data || !Object.hasOwn(data.classes, className)) return;
    syncStates.set(className, nextStatus);
    if (encoded) {
      const current = className === playerClass ? currentCatalog().builds.filter(build => build.goal === goal)[buildIndex] : null;
      const next = JSON.parse(encoded);
      catalogs.set(className, next);
      if (className === playerClass) {
        const matching = next.builds.filter(build => build.goal === goal).findIndex(build =>
          build.source === current?.source && build.sourceKey === current?.sourceKey);
        buildIndex = Math.max(0, matching);
      }
    }
    if (tab === 'talents' && className === playerClass) render();
  };

  function currentLevel() {
    if (!levels.has(playerClass)) {
      const saved = Number(window.Pulse?.talentLevel?.(playerClass) ?? 9);
      levels.set(playerClass, Number.isInteger(saved) && saved >= 9 && saved <= 60 ? saved : 9);
    }
    return levels.get(playerClass);
  }

  function setLevel(raw) {
    const level = Math.min(60, Math.max(9, Number(raw)));
    if (!Number.isInteger(level)) return;
    levels.set(playerClass, level);
    window.Pulse?.saveTalents?.(playerClass, level);
  }

  function sprite(node) {
    if (!Number.isInteger(node.icon)) return '<span class="talent-sprite empty" aria-hidden="true"></span>';
    const x = (node.icon % data.atlas.cols) / (data.atlas.cols - 1) * 100;
    const y = Math.floor(node.icon / data.atlas.cols) / (data.atlas.rows - 1) * 100;
    return `<span class="talent-sprite" style="--atlas-cols:${data.atlas.cols * 100}%;--atlas-rows:${data.atlas.rows * 100}%;--icon-x:${x}%;--icon-y:${y}%" aria-hidden="true"></span>`;
  }

  function levelCard(build, level, nodes) {
    const point = level - 9;
    const rankAt = index => build.order.slice(0, index + 1).filter(id => id === build.order[index]).length;
    const step = (index, label) => {
      const node = nodes[build.order[index]];
      return `<button class="talent-step" data-action="talent-node" data-value="${node.id}">
        ${sprite(node)}<span><small>${label}</small><strong>${escape(node.name)} <em>${rankAt(index)}/${node.max}</em></strong></span></button>`;
    };
    const path = build.order.map((id, index) => {
      const node = nodes[id];
      return `<li class="${index === point ? 'up-next' : ''}"><span>${index + 10}</span><strong>${escape(node.name)}</strong><small>${rankAt(index)}/${node.max}</small></li>`;
    }).join('');
    return `<div class="talent-level">
      <label for="talent-level">Твой уровень</label>
      <div class="talent-stepper">
        <button data-action="talent-level" data-value="${level - 1}" aria-label="Уровень ниже" ${level === 9 ? 'disabled' : ''}>−</button>
        <select id="talent-level" aria-label="Уровень персонажа">${Array.from({length:52}, (_, i) => `<option value="${i + 9}" ${i + 9 === level ? 'selected' : ''}>${i + 9}</option>`).join('')}</select>
        <button data-action="talent-level" data-value="${level + 1}" aria-label="Уровень выше" ${level === 60 ? 'disabled' : ''}>+</button>
      </div>
    </div>
    <div class="talent-next">${point ? step(point - 1, `Очко на ${level}-м уровне`) : '<p class="fine">Обычная прокачка талантов начинается с 10-го уровня.</p>'}
    ${point < 51 ? step(point, `Следующее очко · ${level + 1}-й уровень`) : '<p class="fine">Все 51 очко распределены.</p>'}</div>
    <details class="talent-path" data-talent-fold="path" ${showPath ? 'open' : ''}><summary>Порядок талантов 10–60</summary><ol>${path}</ol></details>`;
  }

  function talentDescription(node, rank) {
    const shownRank = Math.max(1, rank);
    const current = node.effects?.[shownRank - 1];
    const next = rank > 0 && rank < node.max ? node.effects?.[rank] : null;
    const line = (effect, label) => `<div class="talent-effect"><small>${label}</small>
      <p lang="${effect.lang === 'en' ? 'en' : 'ru'}">${escape(effect.text)}</p></div>`;
    return `<div class="talent-tip">
      <strong>${escape(node.name)} · ${rank}/${node.max}</strong>
      ${current ? line(current, rank ? `Эффект · ранг ${rank}` : 'Эффект · первый ранг') :
        '<p class="fine">Описание ранга пока недоступно. Проверь каталог кнопкой «Обновить».</p>'}
      ${next && next.text !== current?.text ? line(next, `Следующий ранг · ${rank + 1}/${node.max}`) : ''}
    </div>`;
  }

  function talentTree(tree, ranks, nodes, nextId) {
    const rows = Math.max(...tree.nodes.map(node => node.row)) + 1;
    const links = tree.nodes.filter(node => nodes[node.req]?.tree === node.tree).map(node => {
      const pre = nodes[node.req];
      return `<path class="${ranks[node.id] ? 'learned' : ''}" d="M${pre.col + .5} ${pre.row + .5} V${node.row + .5} H${node.col + .5}"/>`;
    }).join('');
    const tierLabels = Array.from({length:rows}, (_, row) => `<span class="talent-tier" style="--row:${row + 1}">${row * 5}</span>`).join('');
    const cells = tree.nodes.map(node => {
      const rank = ranks[node.id] || 0;
      const tip = `${node.name}, ${rank} из ${node.max}`;
      return `<button class="talent-node ${rank ? 'learned' : ''} ${nextId === node.id ? 'up-next' : ''}"
        style="--col:${node.col + 2};--row:${node.row + 1}" data-action="talent-node" data-value="${node.id}"
        aria-label="${escape(tip)}" aria-pressed="${selectedNode === node.id}">
        ${sprite(node)}<span class="talent-rank">${rank}/${node.max}</span></button>`;
    }).join('');
    const node = nodes[selectedNode];
    const detail = node?.tree === tree.index ? talentDescription(node, ranks[node.id] || 0) :
      '<p class="fine talent-hint">Нажми на талант, чтобы посмотреть его эффект.</p>';
    return `<div class="talent-grid" style="--rows:${rows}">
      <svg viewBox="0 0 4 ${rows}" preserveAspectRatio="none" aria-hidden="true">${links}</svg>
      ${tierLabels}${cells}</div>${detail}`;
  }

  function popularity(classData, current) {
    const snapshot = current?.observedAt >= data.stats.observedAt ? current : data.stats;
    const entry = snapshot.classes[playerClass];
    const specNames = Object.fromEntries(classData.trees.map(tree => [tree.en, tree.label]));
    const translated = spec => spec.endsWith(' Hybrid')
      ? spec.slice(0, -7).split('/').map(part => specNames[part] || part).join(' / ') + ' · гибрид'
      : specNames[spec] || spec;
    const rows = Object.entries(entry?.specs || {}).sort((a, b) => b[1] - a[1]);
    const classified = rows.reduce((sum, [, count]) => sum + count, 0);
    return `<details class="panel talent-popularity" data-talent-fold="stats" ${showStats ? 'open' : ''}><summary>Популярность в игре</summary>
      ${freshness(snapshot, state.talentStatsStatus || 'saved', snapshot.observedAt, 'Наблюдения')}
      ${rows.length ? `<p class="fine">Срез · ${escape(timestamp(snapshot.observedAt))} · выборка класса ${number(entry.sample)}</p>
        <p class="fine">Общая выборка осмотренных персонажей. Доля специализации среди персонажей этого класса.</p>
        <ul>${rows.map(([spec, count]) => `<li><span>${escape(translated(spec))}</span><b>${percent.format(100 * count / entry.sample)}% · ${number(count)}</b></li>`).join('')}
          ${classified < entry.sample ? `<li><span>Ветка не определена</span><b>${number(entry.sample - classified)}</b></li>` : ''}</ul>`
        : '<p class="fine">По этому классу ещё нет наблюдений.</p>'}
    </details>`;
  }

  function changesPanel() {
    const changes = state.talentChanges;
    const entries = changes.entries.map(entry => `<article class="talent-change-entry">
      <h3>${name(entry.className)}</h3><p class="fine">${escape(timestamp(entry.at))}</p>
      <ul>${entry.items.map(item => `<li>${escape(item)}</li>`).join('')}</ul></article>`).join('');
    return `<details class="panel talent-change-log" data-talent-fold="changes" ${showChanges || changes.unread > 0 ? 'open' : ''}>
      <summary>Изменения талантов и билдов${changes.unread > 0 ? ` · новых ${number(changes.unread)}` : ''}</summary>
      ${entries || '<p class="fine">Изменений после проверок пока нет. Здесь появятся новые билды, изменения очков и описаний талантов.</p>'}
    </details>`;
  }

  function screen(currentStats) {
    const heading = section('Таланты', 'Готовые билды WoW Forever') + changesPanel();
    if (!data) return heading + `<article class="panel"><p>${unavailable ? 'Не удалось открыть каталог.' : 'Открываем каталог…'}</p>
      ${unavailable ? '<button class="red-button" data-action="talent-retry">Повторить</button>' : ''}</article>`;
    const classData = currentCatalog();
    const builds = classData.builds.filter(build => build.goal === goal);
    const build = builds[buildIndex];
    const level = currentLevel();
    const ranks = build?.order ? build.order.slice(0, level - 9).reduce((counts, id) => {
      counts[id] = (counts[id] || 0) + 1;
      return counts;
    }, {}) : build?.ranks || {};
    const classButtons = Object.keys(data.classes).map(key => `<button data-action="talent-class" data-value="${key}" aria-pressed="${key === playerClass}">${classIcon(key)}<span>${name(key)}</span></button>`).join('');
    const buildButtons = builds.map((option, i) => `<button data-action="talent-build" data-value="${i}" aria-pressed="${i === buildIndex}">${escape(option.title)} <small>${option.split.join('/')}</small></button>`).join('');
    const points = classData.trees.map(tree => tree.nodes.reduce((sum, node) => sum + (ranks[node.id] || 0), 0));
    const treeTabs = classData.trees.map((tree, i) => [i, `${escape(tree.label)}<small>${points[i]}</small>`]);
    const mainTree = treeIndex ?? (build ? build.split.indexOf(Math.max(...build.split)) : 0);
    const tree = {...classData.trees[mainTree], index:mainTree};
    // Catalogs saved by 0.10.0 still work offline with the matching bundled descriptions.
    const bundled = new Map(classData.version === data.version ?
      data.classes[playerClass].trees.flatMap(group => group.nodes.map(node => [node.id, node])) : []);
    const nodes = Object.fromEntries(classData.trees.flatMap((group, i) => group.nodes.map(node => {
      const known = bundled.get(node.id);
      const effects = node.effects ?? (known && known.en === node.en && known.max === node.max ? known.effects : undefined);
      return [node.id, {...node, effects, tree:i}];
    })));
    tree.nodes = tree.nodes.map(node => nodes[node.id]);
    return heading + `<div class="talent-classes" role="group" aria-label="Класс">${classButtons}</div>
      <div class="talent-goals">${choices(goals, goal, 'talent-goal', 'Цель билда')}</div>
      <article class="panel talent-build">
        ${build ? `<header><h3>${escape(build.title)}</h3><p>${escape(build.role)} · ${build.split.join('/')}${build.order ? '' : ' · 60-й уровень'}</p></header>
        ${build.note ? `<p class="talent-note">${escape(build.note)}</p>` : ''}
        ${freshness(build, build.cached ? 'saved' : status(), null)}
        ${build.cached ? '<p class="fine">Сохранённый билд · сверены требования дерева</p>' : ''}
        ${builds.length > 1 ? `<div class="talent-options" role="group" aria-label="Готовый билд">${buildButtons}</div>` : ''}
        ${build.order ? levelCard(build, level, nodes) : ''}` :
        '<p>Пока нет проверенных билдов для этой цели на текущем дереве.</p>'}
      </article>
      <article class="panel talent-tree">
        ${choices(treeTabs, mainTree, 'talent-tree', 'Ветка талантов')}
        ${talentTree(tree, ranks, nodes, build?.order?.[level - 9])}
        <p class="fine talent-hint">Дерево ${escape(classData.version || data.version)}</p>
        ${freshness({verifiedAt:classData.treeChecked || classData.checked}, status(), classData.checked ? null : data.checked, 'Начальная подборка')}
      </article>${popularity(classData, currentStats)}`;
  }

  function action(key, value) {
    switch (key) {
      case 'talent-retry': load(); break;
      case 'talent-class':
        if (!Object.hasOwn(data.classes, value)) return false;
        playerClass = value;
        setLevel(currentLevel());
        buildIndex = 0;
        treeIndex = null;
        selectedNode = null;
        check(false);
        break;
      case 'talent-goal':
        goal = value;
        buildIndex = 0;
        treeIndex = null;
        selectedNode = null;
        break;
      case 'talent-build':
        buildIndex = Number(value);
        treeIndex = null;
        selectedNode = null;
        break;
      case 'talent-level': setLevel(value); break;
      case 'talent-tree': treeIndex = Number(value); selectedNode = null; break;
      case 'talent-node':
        treeIndex = currentCatalog().trees.findIndex(tree => tree.nodes.some(node => node.id === Number(value)));
        selectedNode = Number(value);
        break;
      default: return false;
    }
    return true;
  }

  function rememberFolds(root) {
    showPath = root.querySelector('[data-talent-fold="path"]')?.open ?? showPath;
    showStats = root.querySelector('[data-talent-fold="stats"]')?.open ?? showStats;
    showChanges = root.querySelector('[data-talent-fold="changes"]')?.open ?? showChanges;
  }

  return {load, screen, action, rememberFolds, check, status};
})();
