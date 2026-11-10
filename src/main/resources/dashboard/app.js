/* Vector dashboard. No dependencies. All dynamic text goes through textContent, never innerHTML. */
(() => {
  'use strict';

  const POLL_MS = 2000;
  const MAX_HISTORY = 300;
  const MAX_LOG_LINES = 1000;
  const MAX_ACTIVITY = 500;
  const SVG_NS = 'http://www.w3.org/2000/svg';

  const state = {
    data: null,
    history: [],
    historySeq: 0,
    logs: [],
    logSeq: 0,
    activity: [],
    activitySeq: 0,
    playerSort: { key: 'since', dir: -1 }, // newest connection first by default
    tab: 'overview',
    timer: null,
    authRequired: false,
    lastOk: 0,
  };

  const $ = (id) => document.getElementById(id);

  // ------------------------------------------------------------------ helpers

  function h(tag, props, ...children) {
    const node = document.createElement(tag);
    if (props) {
      for (const [key, value] of Object.entries(props)) {
        if (value === false || value == null) continue;
        if (key === 'class') node.className = value;
        else if (key === 'text') node.textContent = value;
        else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
        else node.setAttribute(key, value === true ? '' : value);
      }
    }
    for (const child of children) {
      if (child == null || child === false) continue;
      node.append(child.nodeType ? child : document.createTextNode(String(child)));
    }
    return node;
  }

  function svg(tag, attrs, text) {
    const node = document.createElementNS(SVG_NS, tag);
    for (const [key, value] of Object.entries(attrs || {})) node.setAttribute(key, value);
    if (text != null) node.textContent = text;
    return node;
  }

  function fmtNumber(n) {
    return Math.round(n).toLocaleString();
  }

  function fmtBytes(n) {
    if (n < 1024) return `${Math.round(n)} B`;
    const units = ['KiB', 'MiB', 'GiB', 'TiB'];
    let v = n / 1024;
    let i = 0;
    while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
    return `${v >= 100 ? v.toFixed(0) : v.toFixed(1)} ${units[i]}`;
  }

  const fmtRate = (n) => `${fmtBytes(n)}/s`;

  function fmtPerSec(n) {
    return n >= 100 ? `${Math.round(n)}/s` : `${n.toFixed(n < 10 ? 1 : 0)}/s`;
  }

  function fmtDuration(ms) {
    let s = Math.max(0, Math.floor(ms / 1000));
    const d = Math.floor(s / 86400); s %= 86400;
    const hr = Math.floor(s / 3600); s %= 3600;
    const m = Math.floor(s / 60); s %= 60;
    if (d) return `${d}d ${hr}h`;
    if (hr) return `${hr}h ${m}m`;
    if (m) return `${m}m ${s}s`;
    return `${s}s`;
  }

  function fmtClock(ms) {
    const d = new Date(ms);
    return d.toTimeString().slice(0, 8);
  }

  function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  function toast(message, bad) {
    const node = h('div', { class: bad ? 'toast bad' : 'toast', text: message });
    $('toasts').append(node);
    setTimeout(() => node.remove(), bad ? 7000 : 4000);
  }

  async function request(path, options) {
    const init = { credentials: 'same-origin', ...options };
    if (init.method === 'POST') {
      init.headers = { 'Content-Type': 'application/json', 'X-Requested-With': 'vector-dashboard' };
    }
    const response = await fetch(path, init);
    let body = null;
    try { body = await response.json(); } catch (e) { /* non-JSON error page */ }
    return { status: response.status, ok: response.ok, body };
  }

  async function act(payload, successFallback) {
    try {
      const res = await request('/api/action', { method: 'POST', body: JSON.stringify(payload) });
      if (res.status === 401) { showLogin(); return false; }
      const message = (res.body && res.body.message) || (res.ok ? successFallback : 'The request failed.');
      toast(message, !res.ok);
      if (res.ok) poll(true);
      return res.ok;
    } catch (e) {
      toast('Could not reach Vector. Check that it is still running.', true);
      return false;
    }
  }

  // ------------------------------------------------------------------ preferences

  /** localStorage can throw (private windows, blocked storage), so every access goes through these. */
  const prefs = {
    get(key, fallback) {
      try { const v = localStorage.getItem(`vector.${key}`); return v === null ? fallback : v; } catch (e) { return fallback; }
    },
    set(key, value) {
      try { localStorage.setItem(`vector.${key}`, String(value)); } catch (e) { /* not persisted, still works */ }
    },
  };

  const THEMES = ['auto', 'light', 'dark'];

  function applyTheme(theme) {
    if (theme === 'auto') document.documentElement.removeAttribute('data-theme');
    else document.documentElement.setAttribute('data-theme', theme);
    $('themeBtn').textContent = `Theme: ${theme}`;
    if (state.data && state.tab === 'overview') renderOverview(state.data); // charts read colours when drawn
  }

  $('themeBtn').addEventListener('click', () => {
    const current = prefs.get('theme', 'auto');
    const next = THEMES[(THEMES.indexOf(current) + 1) % THEMES.length];
    prefs.set('theme', next);
    applyTheme(next);
  });

  // ------------------------------------------------------------------ confirmation

  /** Asks before a destructive action. Resolves true only when the operator presses the confirm button. */
  function confirmAction({ title, message, confirm = 'Confirm' }) {
    const dialog = $('confirmDialog');
    $('confirmTitle').textContent = title;
    $('confirmMessage').textContent = message;
    $('confirmOk').textContent = confirm;
    return new Promise((resolve) => {
      const done = (value) => {
        $('confirmForm').removeEventListener('submit', onSubmit);
        $('confirmCancel').removeEventListener('click', onCancel);
        dialog.removeEventListener('close', onClose);
        if (dialog.open) dialog.close();
        resolve(value);
      };
      const onSubmit = (event) => { event.preventDefault(); done(true); };
      const onCancel = () => done(false);
      const onClose = () => done(false); // Escape key
      $('confirmForm').addEventListener('submit', onSubmit);
      $('confirmCancel').addEventListener('click', onCancel);
      dialog.addEventListener('close', onClose);
      dialog.showModal();
      $('confirmCancel').focus();
    });
  }

  // ------------------------------------------------------------------ session

  function showLogin() {
    clearTimeout(state.timer);
    $('app').hidden = true;
    $('login').hidden = false;
    $('loginPassword').focus();
  }

  function showApp() {
    $('login').hidden = true;
    $('app').hidden = false;
    $('signout').hidden = !state.authRequired;
    poll(true);
  }

  $('loginForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    const error = $('loginError');
    error.hidden = true;
    try {
      const res = await request('/api/login', { method: 'POST', body: JSON.stringify({ password: $('loginPassword').value }) });
      if (res.ok) {
        $('loginPassword').value = '';
        showApp();
      } else {
        error.textContent = (res.body && res.body.message) || 'Sign in failed.';
        error.hidden = false;
      }
    } catch (e) {
      error.textContent = 'Could not reach Vector.';
      error.hidden = false;
    }
  });

  $('signout').addEventListener('click', async () => {
    await request('/api/logout', { method: 'POST', body: '{}' });
    showLogin();
  });

  async function boot() {
    try {
      const res = await request('/api/session');
      state.authRequired = !!(res.body && res.body.authRequired);
      if (state.authRequired && !res.body.authenticated) showLogin();
      else showApp();
    } catch (e) {
      $('app').hidden = false;
      setLive('lost', 'Cannot reach Vector');
    }
  }

  // ------------------------------------------------------------------ polling

  function setLive(kind, text) {
    $('liveState').dataset.state = kind;
    $('liveText').textContent = text;
  }

  async function poll(immediate) {
    clearTimeout(state.timer);
    if (document.hidden && !immediate) {
      state.timer = setTimeout(poll, POLL_MS);
      return;
    }
    try {
      const res = await request(`/api/state?history=${state.historySeq}&log=${state.logSeq}&activity=${state.activitySeq}`);
      if (res.status === 401) { showLogin(); return; }
      if (!res.ok || !res.body) throw new Error('bad response');
      apply(res.body);
      state.lastOk = Date.now();
      setLive('ok', `Live · up ${fmtDuration(res.body.now - res.body.startedAt)}`);
    } catch (e) {
      setLive('lost', 'Connection lost, retrying');
    }
    state.timer = setTimeout(poll, POLL_MS);
  }

  document.addEventListener('visibilitychange', () => { if (!document.hidden) poll(true); });

  function apply(d) {
    state.data = d;
    if (d.history.length) {
      state.history.push(...d.history);
      if (state.history.length > MAX_HISTORY) state.history.splice(0, state.history.length - MAX_HISTORY);
    }
    state.historySeq = d.historySeq;
    if (d.logs.length) {
      state.logs.push(...d.logs);
      if (state.logs.length > MAX_LOG_LINES) state.logs.splice(0, state.logs.length - MAX_LOG_LINES);
      appendLogs(d.logs);
    }
    state.logSeq = d.logSeq;
    if (d.activity.length) {
      state.activity.push(...d.activity);
      if (state.activity.length > MAX_ACTIVITY) state.activity.splice(0, state.activity.length - MAX_ACTIVITY);
      if (state.tab === 'activity') renderActivity();
    }
    state.activitySeq = d.activitySeq;

    $('bindLabel').textContent = d.bind;
    updateTitle(d);
    renderBanners(d);
    renderOverview(d);
    if (state.tab === 'players') renderPlayers(d);
    if (state.tab === 'backends') renderBackends(d);
    if (state.tab === 'access') renderAccess(d);
  }

  /** The tab title carries the essentials so a pinned tab is useful: players online, and a marker when backends are down. */
  function updateTitle(d) {
    const down = d.backends.filter((b) => !b.healthy).length;
    const available = d.backends.filter((b) => b.healthy && !b.draining).length;
    let prefix = `(${d.totals.connectionsActive})`;
    if (d.backends.length && available === 0) prefix = '(offline)';
    else if (down) prefix = `(${d.totals.connectionsActive}, ${down} down)`;
    document.title = `${prefix} Vector dashboard`;
  }

  /** Persistent notices that should be visible on every tab: maintenance mode and backend trouble. */
  function renderBanners(d) {
    $('maintenanceBanner').hidden = !d.maintenance;

    const down = d.backends.filter((b) => !b.healthy);
    const available = d.backends.filter((b) => b.healthy && !b.draining);
    const banner = $('backendBanner');
    if (!d.backends.length || (!down.length && available.length)) {
      banner.hidden = true;
      return;
    }
    banner.hidden = false;
    if (!available.length) {
      $('backendBannerText').textContent = 'No backend can take new players right now. Joining players are refused and the server list shows the offline message.';
    } else {
      const names = down.map((b) => b.name).join(', ');
      $('backendBannerText').textContent = `${down.length} of ${d.backends.length} backends ${down.length === 1 ? 'is' : 'are'} down: ${names}. Players are being sent to the others.`;
    }
  }

  $('maintenanceOff').addEventListener('click', () => {
    act({ action: 'setting', key: 'maintenance-enabled', value: false }, 'Maintenance mode is off.');
  });
  $('backendBannerGo').addEventListener('click', () => selectTab('backends'));

  // ------------------------------------------------------------------ tabs

  function selectTab(name) {
    state.tab = name;
    for (const btn of document.querySelectorAll('.tab-btn')) {
      if (btn.dataset.tab === name) btn.setAttribute('aria-current', 'page');
      else btn.removeAttribute('aria-current');
    }
    for (const section of document.querySelectorAll('.tab')) section.hidden = section.id !== `tab-${name}`;
    history.replaceState(null, '', `#${name}`);
    if (state.data) {
      if (name === 'overview') renderOverview(state.data);
      if (name === 'players') renderPlayers(state.data, true);
      if (name === 'backends') renderBackends(state.data);
      if (name === 'access') renderAccess(state.data, true);
    }
    if (name === 'logs') { renderLogsFull(); }
    if (name === 'activity') renderActivity();
    if (name === 'config') loadConfig();
  }

  $('tabs').addEventListener('click', (event) => {
    const btn = event.target.closest('.tab-btn');
    if (btn) selectTab(btn.dataset.tab);
  });

  // ------------------------------------------------------------------ overview

  function renderOverview(d) {
    const last = state.history[state.history.length - 1];
    const inBps = last ? last.inBps : 0;
    const outBps = last ? last.outBps : 0;

    $('sOnline').textContent = fmtNumber(d.totals.connectionsActive);
    $('sOnlineSub').textContent = `${fmtNumber(d.totals.connectionsTotal)} since start`;
    $('sTraffic').textContent = fmtRate(inBps + outBps);
    $('sTrafficSub').textContent = `${fmtBytes(d.totals.bytesFromClients + d.totals.bytesToClients)} relayed in total`;

    const pings = d.status.cache + d.status.offline + d.status.backend;
    $('sPings').textContent = fmtNumber(pings);
    $('sPingsSub').textContent = pings ? `${Math.round((d.status.cache + d.status.offline) / pings * 100)}% answered by Vector` : 'None yet';

    const blocked = Object.values(d.blocked).reduce((a, b) => a + b, 0);
    $('sBlocked').textContent = fmtNumber(blocked);
    const parts = [];
    if (d.blocked.ban) parts.push(`${d.blocked.ban} banned`);
    if (d.blocked.connectionLimit + d.blocked.connectionRate) parts.push(`${d.blocked.connectionLimit + d.blocked.connectionRate} over limit`);
    if (d.blocked.packetLimit) parts.push(`${d.blocked.packetLimit} flooding`);
    if (d.blocked.whitelist) parts.push(`${d.blocked.whitelist} not whitelisted`);
    $('sBlockedSub').textContent = parts.length ? parts.join(', ') : 'Nothing refused';

    drawRouteMap(d, last);
    if (state.tab === 'overview') drawCharts();
  }

  // ---- route map

  function linkWidth(count) {
    return Math.min(16, 2 + Math.sqrt(Math.max(0, count)) * 2.2);
  }

  function clip(text, max) {
    return text.length > max ? `${text.slice(0, max - 1)}…` : text;
  }

  function drawRouteMap(d, last) {
    const map = $('routeMap');
    const backends = d.backends;
    const rowH = 78;
    const W = 960;
    const H = Math.max(230, backends.length * rowH + 30);
    const cy = H / 2;
    map.setAttribute('viewBox', `0 0 ${W} ${H}`);
    map.replaceChildren();

    const clientX = 30, clientW = 170, nodeH = 64;
    const hubX = 400, hubW = 140, hubH = 84;
    const backX = 720, backW = 210, backH = 58;

    const healthy = backends.filter((b) => b.healthy).length;
    $('mapNote').textContent = `${backends.length} backend${backends.length === 1 ? '' : 's'}, ${healthy} up · ${d.balanceMode}`;
    map.setAttribute('aria-label',
      `${d.totals.connectionsActive} open connections through Vector to ${backends.length} backends: `
      + backends.map((b) => `${b.name} ${b.healthy ? 'up' : 'down'} with ${b.active}`).join(', '));

    const links = svg('g');
    const flows = svg('g');
    const nodes = svg('g');
    map.append(links, flows, nodes);

    // clients -> Vector
    const inPath = `M ${clientX + clientW} ${cy} L ${hubX} ${cy}`;
    const inWidth = linkWidth(d.totals.connectionsActive);
    links.append(svg('path', { d: inPath, class: `rm-link ${d.totals.connectionsActive ? 'rm-link-up' : 'rm-link-idle'}`, 'stroke-width': inWidth }));
    if (d.totals.connectionsActive) {
      flows.append(svg('path', { d: inPath, class: 'rm-flow', 'stroke-width': Math.max(1.6, inWidth * 0.34) }));
    }

    // refused traffic stops at a wall before the hub
    const refusedPerSec = last ? last.blocked : 0;
    if (refusedPerSec > 0) {
      const y = cy + 44;
      links.append(svg('line', { x1: clientX + clientW, y1: y, x2: clientX + clientW + 120, y2: y, class: 'rm-blocked' }));
      links.append(svg('line', { x1: clientX + clientW + 120, y1: y - 9, x2: clientX + clientW + 120, y2: y + 9, class: 'rm-blocked' }));
      nodes.append(svg('text', { x: clientX + clientW + 6, y: y + 24, class: 'rm-blocked-text' }, `${fmtPerSec(refusedPerSec)} refused`));
    }

    // Vector -> backends
    backends.forEach((b, i) => {
      const y = backends.length === 1 ? cy : (H / 2) - ((backends.length - 1) * rowH) / 2 + i * rowH;
      const x1 = hubX + hubW, x2 = backX;
      const mid = (x1 + x2) / 2;
      const path = `M ${x1} ${cy} C ${mid} ${cy}, ${mid} ${y}, ${x2} ${y}`;
      const width = linkWidth(b.active);
      let cls = 'rm-link-idle';
      if (!b.healthy) cls = 'rm-link-down';
      else if (b.active > 0) cls = 'rm-link-up';
      links.append(svg('path', { d: path, class: `rm-link ${cls}`, 'stroke-width': b.healthy ? width : 3 }));
      if (b.healthy && b.active > 0) {
        flows.append(svg('path', { d: path, class: 'rm-flow', 'stroke-width': Math.max(1.6, width * 0.34) }));
      }

      const top = y - backH / 2;
      nodes.append(svg('rect', { x: backX, y: top, width: backW, height: backH, rx: 10, class: 'rm-node' }));
      nodes.append(svg('circle', { cx: backX + 18, cy: top + 20, r: 5, class: b.healthy ? 'rm-dot-up' : 'rm-dot-down' }));
      nodes.append(svg('text', { x: backX + 32, y: top + 24, class: 'rm-text' }, clip(b.name, 20)));
      const detail = b.healthy
        ? `${b.active} open${b.latencyMs >= 0 ? ` · ${b.latencyMs} ms` : ''}`
        : 'down';
      nodes.append(svg('text', { x: backX + 18, y: top + 44, class: 'rm-sub' }, clip(`${b.address}`, 26)));
      nodes.append(svg('text', { x: backX + backW - 12, y: top + 24, class: 'rm-sub', 'text-anchor': 'end' }, detail));
    });

    // nodes drawn last so links tuck underneath
    nodes.append(svg('rect', { x: clientX, y: cy - nodeH / 2, width: clientW, height: nodeH, rx: 10, class: 'rm-node' }));
    nodes.append(svg('text', { x: clientX + 16, y: cy - 6, class: 'rm-text' }, 'Players'));
    nodes.append(svg('text', { x: clientX + 16, y: cy + 14, class: 'rm-sub' },
      `${d.totals.connectionsActive} open${last ? ` · ${fmtPerSec(last.newConns)} joining` : ''}`));

    nodes.append(svg('rect', { x: hubX, y: cy - hubH / 2, width: hubW, height: hubH, rx: 14, class: 'rm-hub' }));
    nodes.append(svg('text', { x: hubX + hubW / 2, y: cy - 4, class: 'rm-hub-text', 'text-anchor': 'middle' }, 'Vector'));
    nodes.append(svg('text', { x: hubX + hubW / 2, y: cy + 16, class: 'rm-hub-text', 'text-anchor': 'middle', 'font-size': 11, opacity: 0.85 }, d.bind));
  }

  // ---- charts

  const chartDefs = {
    chConns: {
      legend: 'lgConns',
      format: (v) => fmtNumber(v),
      minMax: 4,
      series: [{ label: 'Open', color: '--signal', key: 'active', fill: true }],
    },
    chTraffic: {
      legend: 'lgTraffic',
      format: (v) => fmtRate(v),
      minMax: 1024,
      series: [
        { label: 'From players', color: '--signal', key: 'inBps', fill: true },
        { label: 'To players', color: '--up', key: 'outBps' },
      ],
    },
    chEvents: {
      legend: 'lgEvents',
      format: (v) => fmtPerSec(v),
      minMax: 2,
      series: [
        { label: 'Joins', color: '--signal', key: 'newConns' },
        { label: 'Pings', color: '--warn', key: 'status' },
        { label: 'Refused', color: '--down', key: 'blocked' },
      ],
    },
  };

  const hover = {};
  const chartPad = {};

  function niceMax(value, minimum) {
    const v = Math.max(value, minimum);
    const pow = Math.pow(10, Math.floor(Math.log10(v)));
    const n = v / pow;
    const step = n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10;
    return step * pow;
  }

  function drawCharts() {
    for (const [id, def] of Object.entries(chartDefs)) drawChart(id, def);
  }

  function drawChart(id, def) {
    const canvas = $(id);
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth;
    const hgt = canvas.clientHeight;
    if (!w || !hgt) return;
    if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(hgt * dpr)) {
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(hgt * dpr);
    }
    const ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, hgt);

    const pts = state.history;
    const padR = 8, padT = 8, padB = 20;
    const plotH = hgt - padT - padB;

    let max = 0;
    for (const s of def.series) for (const p of pts) max = Math.max(max, p[s.key] || 0);
    const yMax = niceMax(max, def.minMax);

    const line = cssVar('--line');
    const muted = cssVar('--ink-2');
    ctx.font = '11px ' + cssVar('--mono');
    ctx.textBaseline = 'middle';

    // size the left gutter to the widest axis label so nothing is clipped
    let labelWidth = 0;
    for (let i = 0; i <= 4; i++) labelWidth = Math.max(labelWidth, ctx.measureText(def.format((yMax * i) / 4)).width);
    const padL = Math.ceil(labelWidth) + 14;
    chartPad[id] = padL;
    const plotW = w - padL - padR;

    // grid + y labels
    ctx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
      const y = padT + plotH - (plotH * i) / 4;
      ctx.strokeStyle = line;
      ctx.beginPath(); ctx.moveTo(padL, y + 0.5); ctx.lineTo(w - padR, y + 0.5); ctx.stroke();
      ctx.fillStyle = muted;
      ctx.textAlign = 'right';
      ctx.fillText(def.format((yMax * i) / 4), padL - 8, y);
    }

    const span = MAX_HISTORY - 1;
    const xAt = (index) => padL + plotW - ((pts.length - 1 - index) / span) * plotW;
    const yAt = (value) => padT + plotH - (Math.min(value, yMax) / yMax) * plotH;

    // x labels
    ctx.textBaseline = 'alphabetic';
    ctx.fillStyle = muted;
    ctx.textAlign = 'right';
    ctx.fillText('now', w - padR, hgt - 5);
    ctx.textAlign = 'left';
    ctx.fillText('10 min ago', padL, hgt - 5);

    if (pts.length < 2) {
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('Collecting data…', padL + plotW / 2, padT + plotH / 2);
    } else {
      for (const s of def.series) {
        const color = cssVar(s.color);
        ctx.beginPath();
        pts.forEach((p, i) => {
          const x = xAt(i), y = yAt(p[s.key] || 0);
          if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
        });
        if (s.fill) {
          ctx.save();
          ctx.lineTo(xAt(pts.length - 1), padT + plotH);
          ctx.lineTo(xAt(0), padT + plotH);
          ctx.closePath();
          ctx.globalAlpha = 0.1;
          ctx.fillStyle = color;
          ctx.fill();
          ctx.restore();
          ctx.beginPath();
          pts.forEach((p, i) => {
            const x = xAt(i), y = yAt(p[s.key] || 0);
            if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
          });
        }
        ctx.lineWidth = 1.8;
        ctx.lineJoin = 'round';
        ctx.strokeStyle = color;
        ctx.stroke();
      }

      // hover crosshair
      const hv = hover[id];
      if (hv != null && hv >= 0 && hv < pts.length) {
        const x = xAt(hv);
        ctx.strokeStyle = muted;
        ctx.lineWidth = 1;
        ctx.setLineDash([3, 3]);
        ctx.beginPath(); ctx.moveTo(x + 0.5, padT); ctx.lineTo(x + 0.5, padT + plotH); ctx.stroke();
        ctx.setLineDash([]);
        for (const s of def.series) {
          ctx.fillStyle = cssVar(s.color);
          ctx.beginPath(); ctx.arc(x, yAt(pts[hv][s.key] || 0), 3.5, 0, Math.PI * 2); ctx.fill();
        }
      }
    }

    // legend with the value under the cursor, or the latest value
    const legend = $(def.legend);
    const idx = hover[id] != null && hover[id] < pts.length ? hover[id] : pts.length - 1;
    const sample = idx >= 0 ? pts[idx] : null;
    legend.replaceChildren(...def.series.map((s) => {
      const swatch = h('span', { class: 'swatch' });
      swatch.style.background = cssVar(s.color);
      return h('span', { class: 'legend-item' }, swatch, `${s.label} `, h('span', { class: 'mono', text: sample ? def.format(sample[s.key] || 0) : '–' }));
    }));
    if (hover[id] != null && sample) legend.append(h('span', { class: 'legend-item mono', text: fmtClock(sample.t) }));
  }

  for (const id of Object.keys(chartDefs)) {
    const canvas = $(id);
    canvas.addEventListener('mousemove', (event) => {
      const rect = canvas.getBoundingClientRect();
      const padL = chartPad[id] || 54, padR = 8;
      const plotW = rect.width - padL - padR;
      const rel = (event.clientX - rect.left - padL) / plotW;
      const idx = state.history.length - 1 - Math.round((1 - rel) * (MAX_HISTORY - 1));
      hover[id] = idx >= 0 && idx < state.history.length ? idx : null;
      drawChart(id, chartDefs[id]);
    });
    canvas.addEventListener('mouseleave', () => { hover[id] = null; drawChart(id, chartDefs[id]); });
  }

  let resizeTimer = null;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => { if (state.tab === 'overview') drawCharts(); }, 120);
  });
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (state.data) { renderOverview(state.data); }
  });

  // ------------------------------------------------------------------ players

  function interacting(table) {
    return table.contains(document.activeElement) && document.activeElement !== document.body;
  }

  function renderPlayers(d, force) {
    const table = $('playersTable');
    if (!force && interacting(table)) return;
    const filter = $('playerFilter').value.trim().toLowerCase();
    const rows = d.connections.filter((c) => !filter
      || `${c.player || ''} ${c.ip} ${c.backend || ''}`.toLowerCase().includes(filter));
    sortPlayers(rows);
    for (const th of table.querySelectorAll('th[aria-sort]')) {
      const key = th.querySelector('button.sort').dataset.sort;
      th.setAttribute('aria-sort', key === state.playerSort.key ? (state.playerSort.dir > 0 ? 'ascending' : 'descending') : 'none');
    }

    $('playerCount').textContent = `${rows.length} of ${d.totals.connectionsActive} connection${d.totals.connectionsActive === 1 ? '' : 's'}`;
    const body = table.querySelector('tbody');
    body.replaceChildren();
    $('playersEmpty').hidden = rows.length > 0;
    table.parentElement.hidden = rows.length === 0;
    if (!rows.length && d.totals.connectionsActive > 0) {
      $('playersEmpty').textContent = 'No connection matches this filter.';
    } else if (!rows.length) {
      $('playersEmpty').textContent = 'Nobody is connected. Connections appear here as soon as a client joins or pings the server.';
    }

    for (const c of rows) {
      const send = h('select', { 'aria-label': `Send ${c.player || c.ip} to another backend` },
        h('option', { value: '', text: 'Send to…' }),
        ...d.backends.filter((b) => b.healthy && b.name !== c.backend).map((b) => h('option', { value: b.name, text: b.name })));
      send.addEventListener('change', () => {
        if (send.value) act({ action: 'send', ip: c.ip, backend: send.value }, 'Sent.');
      });

      body.append(h('tr', null,
        h('td', null, c.player ? h('strong', { text: c.player }) : h('span', { class: 'muted', text: 'connecting or pinging' })),
        h('td', { class: 'mono', text: `${c.ip}:${c.port}` }),
        h('td', { text: c.backend || '–' }),
        h('td', { class: 'mono', text: fmtDuration(d.now - c.since) }),
        h('td', { class: 'mono', text: `${fmtBytes(c.bytesIn)} / ${fmtBytes(c.bytesOut)}` }),
        h('td', { class: 'mono', text: c.protocol != null ? String(c.protocol) : '–' }),
        h('td', null, h('div', { class: 'row-actions' },
          d.backends.length > 1 ? send : null,
          h('button', { class: 'ghost small-btn', text: 'Kick', onclick: () => act({ action: 'kick', ip: c.ip }, 'Disconnected.') }),
          h('button', { class: 'ghost small-btn', text: 'Ban…', onclick: () => openBanDialog(c) })))));
    }
  }

  /** Sorts connection rows in place by the column chosen in the header (names and backends case-insensitively). */
  function sortPlayers(rows) {
    const { key, dir } = state.playerSort;
    const value = (c) => {
      switch (key) {
        case 'player': return (c.player || '').toLowerCase();
        case 'ip': return c.ip;
        case 'backend': return (c.backend || '').toLowerCase();
        case 'traffic': return c.bytesIn + c.bytesOut;
        default: return -c.since; // 'since': smaller timestamp = older = longer connected
      }
    };
    rows.sort((a, b) => {
      const x = value(a), y = value(b);
      return (x < y ? -1 : x > y ? 1 : 0) * dir;
    });
  }

  $('playersTable').querySelector('thead').addEventListener('click', (event) => {
    const button = event.target.closest('button.sort');
    if (!button) return;
    const key = button.dataset.sort;
    state.playerSort = { key, dir: state.playerSort.key === key ? -state.playerSort.dir : 1 };
    if (state.data) renderPlayers(state.data, true);
  });

  $('playerFilter').addEventListener('input', () => {
    prefs.set('playerFilter', $('playerFilter').value);
    if (state.data) renderPlayers(state.data, true);
  });

  // ---- bulk actions

  $('kickAllBtn').addEventListener('click', async () => {
    const online = state.data ? state.data.totals.connectionsActive : 0;
    if (await confirmAction({
      title: `Disconnect all ${online} connection${online === 1 ? '' : 's'}?`,
      message: 'Everyone is kicked. They can rejoin immediately unless maintenance mode or a ban stops them.',
      confirm: 'Disconnect everyone',
    })) {
      act({ action: 'kickall' }, 'Disconnected.');
    }
  });

  $('evacBtn').addEventListener('click', () => {
    const backends = state.data ? state.data.backends : [];
    if (backends.length < 2) {
      toast('You need at least two backends to move players between them.', true);
      return;
    }
    for (const id of ['evacFrom', 'evacTo']) {
      $(id).replaceChildren(...backends.map((b) => h('option', { value: b.name, text: `${b.name} (${b.active} open)` })));
    }
    $('evacTo').selectedIndex = 1;
    $('evacDialog').showModal();
  });

  $('evacCancel').addEventListener('click', () => $('evacDialog').close());
  $('evacForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    const ok = await act({ action: 'evacuate', from: $('evacFrom').value, to: $('evacTo').value }, 'Moved.');
    if (ok) $('evacDialog').close();
  });

  // ---- ban dialog

  function openBanDialog(c) {
    $('banDialogName').textContent = c.player || c.ip;
    $('bdTarget').value = c.ip;
    $('bdDuration').value = '';
    $('bdReason').value = '';
    $('banDialog').showModal();
    $('bdDuration').focus();
  }

  $('bdCancel').addEventListener('click', () => $('banDialog').close());
  $('banDialogForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    const ok = await act({
      action: 'ban', target: $('bdTarget').value, duration: $('bdDuration').value, reason: $('bdReason').value,
    }, 'Banned.');
    if (ok) $('banDialog').close();
  });

  // ------------------------------------------------------------------ backends

  function renderBackends(d) {
    $('backendMode').textContent = `Balancing: ${d.balanceMode}`;
    const body = $('backendsTable').querySelector('tbody');
    body.replaceChildren();
    for (const b of d.backends) {
      const bar = h('span', { class: 'latency-fill' });
      bar.style.width = `${b.latencyMs < 0 ? 0 : Math.min(100, (b.latencyMs / 200) * 100)}%`;
      body.append(h('tr', null,
        h('td', null, h('strong', { text: b.name })),
        h('td', null, !b.healthy
          ? h('span', { class: 'pill down', text: 'Down' })
          : b.draining ? h('span', { class: 'pill warn', text: 'Draining' }) : h('span', { class: 'pill up', text: 'Up' })),
        h('td', { class: 'mono', text: b.address }),
        h('td', { class: 'num', text: String(b.weight) }),
        h('td', { class: 'num', text: fmtNumber(b.active) }),
        h('td', { class: 'num', text: b.maxConnections > 0 ? fmtNumber(b.maxConnections) : '–' }),
        h('td', { class: 'num', text: fmtNumber(b.total) }),
        h('td', { class: 'num', text: fmtNumber(b.failures) }),
        h('td', { class: 'num' }, b.latencyMs >= 0
          ? h('span', { class: 'latency' }, h('span', { class: 'latency-bar' }, bar), `${b.latencyMs} ms`)
          : '–'),
        h('td', null, h('div', { class: 'row-actions' },
          h('button', {
            class: 'ghost small-btn',
            text: b.draining ? 'Resume' : 'Drain',
            title: b.draining ? 'Accept new players again' : 'Stop sending new players here',
            onclick: () => act({ action: b.draining ? 'undrain' : 'drain', backend: b.name }, 'Done.'),
          })))));
    }
  }

  // ------------------------------------------------------------------ access

  function renderAccess(d, force) {
    if (!force && (interacting($('bansTable')) || interacting($('wlTable')))) return;

    $('banCount').textContent = d.bans.length ? `${d.bans.length} active` : '';
    const banBody = $('bansTable').querySelector('tbody');
    banBody.replaceChildren();
    $('bansEmpty').hidden = d.bans.length > 0;
    $('bansTable').parentElement.hidden = d.bans.length === 0;
    for (const b of d.bans) {
      const expires = b.expiresAt === 0
        ? 'Never'
        : `${new Date(b.expiresAt).toLocaleString()} (in ${fmtDuration(b.expiresAt - d.now)})`;
      banBody.append(h('tr', null,
        h('td', { class: 'mono', text: b.target }),
        h('td', { text: expires }),
        h('td', { text: b.reason || '–' }),
        h('td', null, h('div', { class: 'row-actions' },
          h('button', {
            class: 'ghost small-btn',
            text: 'Unban',
            onclick: async () => {
              if (await confirmAction({ title: `Unban ${b.target}?`, message: 'Connections from this address or range will be accepted again.', confirm: 'Unban' })) {
                act({ action: 'unban', target: b.target }, 'Unbanned.');
              }
            },
          })))));
    }

    const wl = d.whitelist;
    const pill = $('wlState');
    pill.textContent = wl.enabled ? 'On' : 'Off';
    pill.className = wl.enabled ? 'pill up' : 'pill';
    const wlBody = $('wlTable').querySelector('tbody');
    wlBody.replaceChildren();
    $('wlEmpty').hidden = wl.entries.length > 0;
    $('wlTable').parentElement.hidden = wl.entries.length === 0;
    for (const entry of wl.entries) {
      wlBody.append(h('tr', null,
        h('td', { class: 'mono', text: entry }),
        h('td', null, h('div', { class: 'row-actions' },
          h('button', {
            class: 'ghost small-btn',
            text: 'Remove',
            onclick: async () => {
              if (await confirmAction({ title: `Remove ${entry} from the whitelist?`, message: 'They will no longer be able to join while the whitelist or maintenance mode is on.', confirm: 'Remove' })) {
                act({ action: 'whitelist-remove', entry }, 'Removed.');
              }
            },
          })))));
    }
  }

  $('banForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    const ok = await act({
      action: 'ban', target: $('banTarget').value, duration: $('banDuration').value, reason: $('banReason').value,
    }, 'Banned.');
    if (ok) { $('banTarget').value = ''; $('banDuration').value = ''; $('banReason').value = ''; }
  });

  $('wlForm').addEventListener('submit', async (event) => {
    event.preventDefault();
    const ok = await act({ action: 'whitelist-add', entry: $('wlEntry').value }, 'Added.');
    if (ok) $('wlEntry').value = '';
  });

  // ------------------------------------------------------------------ logs

  function logVisible(entry) {
    const level = $('logLevel').value;
    if (level === 'WARN' && !['WARN', 'ERROR', 'FATAL'].includes(entry.level)) return false;
    if (level === 'ERROR' && !['ERROR', 'FATAL'].includes(entry.level)) return false;
    const q = $('logSearch').value.trim().toLowerCase();
    return !q || entry.message.toLowerCase().includes(q);
  }

  function logLine(entry) {
    return h('div', { class: `log-line log-${entry.level}` },
      h('span', { class: 'log-time', text: fmtClock(entry.t) }),
      h('span', { class: 'log-level', text: entry.level }),
      h('span', { class: 'log-msg', text: entry.message }));
  }

  function followLogs() {
    if ($('logFollow').checked) {
      const box = $('logBox');
      box.scrollTop = box.scrollHeight;
    }
  }

  function appendLogs(entries) {
    if (state.tab !== 'logs') return;
    const box = $('logBox');
    for (const entry of entries) if (logVisible(entry)) box.append(logLine(entry));
    while (box.childElementCount > MAX_LOG_LINES) box.firstElementChild.remove();
    followLogs();
  }

  function renderLogsFull() {
    const box = $('logBox');
    box.replaceChildren(...state.logs.filter(logVisible).map(logLine));
    followLogs();
  }

  $('logLevel').addEventListener('change', () => { prefs.set('logLevel', $('logLevel').value); renderLogsFull(); });
  $('logSearch').addEventListener('input', () => { prefs.set('logSearch', $('logSearch').value); renderLogsFull(); });
  $('logBox').addEventListener('scroll', () => {
    const box = $('logBox');
    $('logFollow').checked = box.scrollHeight - box.scrollTop - box.clientHeight < 24;
  });
  $('logFollow').addEventListener('change', followLogs);

  // ------------------------------------------------------------------ activity

  const ACTIVITY_TYPES = {
    JOIN: { label: 'Joined', cls: 'tag-join', group: 'PLAYERS' },
    LEAVE: { label: 'Left', cls: 'tag-leave', group: 'PLAYERS' },
    REFUSED: { label: 'Refused', cls: 'tag-refused', group: 'SECURITY' },
    AUTO_BAN: { label: 'Auto-ban', cls: 'tag-ban', group: 'SECURITY' },
    BACKEND_UP: { label: 'Backend up', cls: 'tag-up', group: 'BACKENDS' },
    BACKEND_DOWN: { label: 'Backend down', cls: 'tag-down', group: 'BACKENDS' },
    ADMIN: { label: 'Admin', cls: 'tag-admin', group: 'ADMIN' },
  };

  function renderActivity() {
    const filter = $('activityFilter').value;
    const rows = state.activity
      .filter((e) => filter === 'ALL' || (ACTIVITY_TYPES[e.type] || {}).group === filter)
      .slice(-300)
      .reverse(); // newest first
    const list = $('activityList');
    list.replaceChildren(...rows.map((e) => {
      const meta = ACTIVITY_TYPES[e.type] || { label: e.type, cls: '' };
      return h('li', { class: 'feed-item' },
        h('span', { class: 'feed-time', text: fmtClock(e.t) }),
        h('span', null, h('span', { class: `tag ${meta.cls}`, text: meta.label })),
        h('span', { class: 'feed-text' }, h('strong', { text: e.subject }), e.detail ? h('span', { class: 'feed-detail', text: `  ${e.detail}` }) : null));
    }));
    $('activityEmpty').hidden = rows.length > 0;
    $('activityCount').textContent = rows.length ? `${rows.length} most recent` : '';
  }

  $('activityFilter').addEventListener('change', renderActivity);

  // ------------------------------------------------------------------ config

  async function loadConfig() {
    try {
      const res = await request('/api/config');
      if (res.status === 401) { showLogin(); return; }
      if (!res.ok) throw new Error('bad response');
      const list = $('configList');
      list.replaceChildren();
      for (const [key, value] of Object.entries(res.body.settings)) {
        let text;
        if (key === 'backends') {
          text = value.map((b) => `${b.name}  ${b.host}:${b.port}  weight ${b.weight}`).join('\n');
        } else {
          text = String(value);
        }
        const dd = h('dd', { text });
        if (key === 'backends') dd.style.whiteSpace = 'pre-wrap';
        list.append(h('dt', { text: key }), dd);
      }
    } catch (e) {
      toast('Could not load the configuration.', true);
    }
  }

  $('reloadBtn').addEventListener('click', async () => {
    const ok = await act({ action: 'reload' }, 'Configuration reloaded.');
    if (ok) loadConfig();
  });

  // ------------------------------------------------------------------ start

  applyTheme(THEMES.includes(prefs.get('theme', 'auto')) ? prefs.get('theme', 'auto') : 'auto');
  // Restore the filters from the last visit.
  $('playerFilter').value = prefs.get('playerFilter', '');
  $('logSearch').value = prefs.get('logSearch', '');
  if (['ALL', 'WARN', 'ERROR'].includes(prefs.get('logLevel', 'ALL'))) $('logLevel').value = prefs.get('logLevel', 'ALL');
  const initial = (location.hash || '#overview').slice(1);
  if (['overview', 'players', 'backends', 'activity', 'access', 'logs', 'config'].includes(initial)) {
    selectTab(initial);
  }
  boot();
})();
