

const SUPA_URL = 'https://blsnxyhnuckpvbkoixqa.supabase.co';
const SUPA_KEY = 'sb_publishable_4_iqlW98eU6gtYI3uUku6Q_qARTbydT';
const EG_DOMAIN = 'eliteguard.internal';

let _db;
function db() {
  if (!_db) _db = supabase.createClient(SUPA_URL, SUPA_KEY);
  return _db;
}

let profile = null;
let props   = [];
let photoFile = null;

const ROLE_PAGES = {
  admin:      ['dispatch','postcheck','neglect','vehicle','fleet','users','properties','import'],
  manager:    ['dispatch','neglect','fleet'],
  supervisor: ['postcheck','vehicle','dispatch','neglect'],
  dispatch:   ['dispatch'],
  supply:     ['dispatch'],
};
const PAGE_META = {
  dispatch:   { label: 'Live Dispatch Log',  icon: `<svg width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>` },
  postcheck:  { label: 'New Post Check',     icon: `<svg width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2"/><rect x="9" y="3" width="6" height="4" rx="1"/></svg>` },
  neglect:    { label: 'Neglect Dashboard', icon: `<svg width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>` },
  vehicle:    { label: 'Vehicle Inspection', icon: `<svg width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><rect x="1" y="3" width="15" height="13" rx="2"/><path d="M16 8h4l3 3v5h-7V8z"/><circle cx="5.5" cy="18.5" r="2.5"/><circle cx="18.5" cy="18.5" r="2.5"/></svg>` },
  fleet:      { label: 'Fleet & Inspections', icon: `<svg width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>` },
  users:      { label: 'User Management',    icon: `<svg width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 00-3-3.87"/><path d="M16 3.13a4 4 0 010 7.75"/></svg>` },
  properties: { label: 'Properties',         icon: `<svg width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>` },
  import:     { label: 'Deputy Import',      icon: `<svg width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>` },
};

async function doLogin() {
  const u = document.getElementById('l-user').value.trim().toLowerCase();
  const p = document.getElementById('l-pass').value;
  document.getElementById('l-err').textContent = '';
  if (!u || !p) { setErr('Enter your username and password.'); return; }
  const { data, error } = await db().auth.signInWithPassword({ email: `${u}@${EG_DOMAIN}`, password: p });
  if (error) { setErr('Invalid username or password.'); return; }
  await boot(data.user);
}

function setErr(msg) { document.getElementById('l-err').textContent = msg; }

async function doLogout() {
  await db().auth.signOut();
  location.reload();
}

async function boot(user) {
  const { data: prof, error } = await db().from('profiles').select('*').eq('id', user.id).single();
  if (error || !prof) { setErr('Account not found. Contact your administrator.'); return; }
  profile = prof;

  const { data: propData } = await db().from('properties').select('*').order('name');
  props = propData || [];

  document.getElementById('login-screen').style.display = 'none';
  document.getElementById('app').style.display = 'flex';

  const initials = (prof.display_name || 'EG').split(' ').map(w => w[0]).join('').toUpperCase().slice(0,2);
  document.getElementById('sb-av').textContent   = initials;
  document.getElementById('sb-name').textContent = prof.display_name || prof.username;
  document.getElementById('sb-role').textContent = roleLabel(prof.role);

  buildNav();
  if (prof.role === 'admin') wireAdminEvents();
  if (['admin','supervisor','manager'].includes(prof.role)) wireVehicleEvents();
  const pages = ROLE_PAGES[prof.role] || ['dispatch'];
  goTo(pages[0]);
}

function roleLabel(r) {
  return { admin:'Administrator', manager:'Manager', supervisor:'Field Supervisor', dispatch:'Dispatch', supply:'Supply Clerk' }[r] || r;
}

function buildNav() {
  const pages = ROLE_PAGES[profile.role] || ['dispatch'];
  const nav = document.getElementById('sb-nav');

  const sections = {
    'Field':  ['postcheck','vehicle'],
    'Monitor':['dispatch','neglect'],
    'Admin':  ['fleet','users','properties','import'],
  };

  nav.innerHTML = '';

  function appendNavItem(pg) {
    const m = PAGE_META[pg];
    if (!m) return;
    const el = document.createElement('div');
    el.className = 'nav-item';
    el.id = 'nav-' + pg;
    el.innerHTML = m.icon + '<span>' + m.label + '</span>';
    el.addEventListener('click', function() { goTo(pg); });
    nav.appendChild(el);
    usedPages.push(pg);
  }

  function appendSection(label) {
    const sec = document.createElement('div');
    sec.className = 'sb-section';
    sec.textContent = label;
    nav.appendChild(sec);
  }

  let usedPages = [];
  Object.entries(sections).forEach(function(entry) {
    const sectionLabel = entry[0];
    const sectionPages = entry[1];
    const visible = sectionPages.filter(function(pg) { return pages.includes(pg); });
    if (!visible.length) return;
    appendSection(sectionLabel);
    visible.forEach(function(pg) { appendNavItem(pg); });
  });

  pages.filter(function(pg) { return !usedPages.includes(pg); }).forEach(function(pg) { appendNavItem(pg); });

function buildNav_OLD_UNUSED() {
  const pages = ROLE_PAGES[profile.role] || ['dispatch'];
  const nav = document.getElementById('sb-nav');
  nav.innerHTML = `<div class="sb-section">Navigation</div>`;
  pages.forEach(pg => {
    const m = PAGE_META[pg];
    const el = document.createElement('div');
    el.className = 'nav-item';
    el.id = `nav-${pg}`;
    el.innerHTML = `${m.icon}<span>${m.label}</span>`;
    el.addEventListener('click', () => goTo(pg));
    nav.appendChild(el);
  });
}

function goTo(pg) {
  document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
  const pageEl = document.getElementById(`pg-${pg}`);
  if (pageEl) pageEl.classList.add('active');
  const navEl = document.getElementById(`nav-${pg}`);
  if (navEl) navEl.classList.add('active');
  const m = PAGE_META[pg];
  document.getElementById('pg-title').textContent = m?.label?.toUpperCase() || pg.toUpperCase();
  document.getElementById('pg-meta').textContent = new Date().toLocaleDateString('en-US',{ weekday:'long', month:'long', day:'numeric' });
  if (pg === 'dispatch')   loadDispatch();
  if (pg === 'postcheck')  initPostCheck();
  if (pg === 'neglect')    loadNeglect();
  if (pg === 'vehicle')    initVehicle();
  if (pg === 'fleet')      loadFleet();
  if (pg === 'users')      loadUsers();
  if (pg === 'properties') loadProperties();
  if (pg === 'import')     loadSchedules();
}

async function loadDispatch() {
  const { data } = await db().from('post_checks').select('*').order('checked_at',{ ascending: false }).limit(60);
  const today = (data||[]).filter(r => isToday(r.checked_at));

  document.getElementById('dispatch-stats').innerHTML = `
    <div class="stat-card c-gold"><div class="stat-label">Checks Today</div><div class="stat-value">${today.length}</div></div>
    <div class="stat-card c-warn"><div class="stat-label">With Anomalies</div><div class="stat-value">${today.filter(r=>r.anomalies).length}</div></div>
    <div class="stat-card c-gold"><div class="stat-label">Supply Requests</div><div class="stat-value">${today.filter(r=>r.supply_request).length}</div></div>
    <div class="stat-card c-gold"><div class="stat-label">Uniform Requests</div><div class="stat-value">${today.filter(r=>r.uniform_request).length}</div></div>
  `;

  const feed = document.getElementById('dispatch-feed');
  if (!data?.length) {
    feed.innerHTML = `<div class="empty"><svg width="38" height="38" fill="none" stroke="currentColor" stroke-width="1.5" viewBox="0 0 24 24"><path d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2"/><rect x="9" y="3" width="6" height="4" rx="1"/></svg><p>No post checks logged yet.</p></div>`;
    return;
  }

  feed.innerHTML = data.map(r => `
    <div class="log-entry">
      <div class="log-thumb">${r.photo_url ? `<img src="${esc(r.photo_url)}" alt="photo"/>` : '📷'}</div>
      <div class="log-body">
        <div class="log-site">${esc(r.site_name)}</div>
        <div class="log-detail">
          Officer: <strong>${esc(r.employee_on_duty)}</strong>
          &nbsp;·&nbsp; Sup: ${esc(r.supervisor_name||'—')}
          ${r.supply_request ? `&nbsp;·&nbsp; <span style="color:var(--gold);">📦 Supply</span>` : ''}
          ${r.uniform_request ? `&nbsp;·&nbsp; <span style="color:#a78bfa;">👔 Uniform</span>` : ''}
        </div>
        ${r.anomalies ? `<div class="log-anomaly"><svg width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/></svg>${esc(r.anomalies)}</div>` : ''}
      </div>
      <div class="log-time-col">
        <div class="log-time">${fmtTime(r.checked_at)}</div>
        <div class="log-date">${fmtDate(r.checked_at)}</div>
      </div>
    </div>
  `).join('');
}

function initPostCheck() {
  document.getElementById('pc-sup').value = profile?.display_name || '';
  document.getElementById('pc-site').value = '';
  document.getElementById('site-info-card').style.display = 'none';
}

document.addEventListener('DOMContentLoaded', () => {});

function acSearch(val) {
  const list = document.getElementById('ac-list');
  if (!val || val.length < 2) { list.classList.remove('open'); return; }
  const q = val.toLowerCase();
  const matches = props.filter(p => p.name.toLowerCase().includes(q)).slice(0, 8);
  if (!matches.length) { list.classList.remove('open'); return; }
  list.innerHTML = matches.map(p => `
    <div class="ac-item" data-name="${esc(p.name)}">
      <span>${esc(p.name)}</span>
      <span class="ac-sub">${esc(p.subzone||p.zone||'')}</span>
    </div>
  `).join('');
  list.querySelectorAll('.ac-item').forEach(el => {
    el.addEventListener('click', () => {
      document.getElementById('pc-site').value = el.dataset.name;
      list.classList.remove('open');
      showSiteInfo(el.dataset.name);
    });
  });
  list.classList.add('open');
}

function showSiteInfo(name) {
  const p = props.find(x => x.name === name);
  const card = document.getElementById('site-info-card');
  if (!p) { card.style.display = 'none'; return; }
  document.getElementById('site-info-body').innerHTML = `
    <div class="site-info-item"><div class="si-label">Address</div><div class="si-val">${esc(p.address||'—')}</div></div>
    <div class="site-info-item"><div class="si-label">Zone</div><div class="si-val">${esc(p.zone||'—')}</div></div>
    <div class="site-info-item"><div class="si-label">SubZone</div><div class="si-val">${esc(p.subzone||'—')}</div></div>
    <div class="site-info-item"><div class="si-label">Schedule</div><div class="si-val">${esc(p.schedule||'—')}</div></div>
  `;
  card.style.display = 'block';
}

async function submitPostCheck() {
  const site = document.getElementById('pc-site').value.trim();
  const emp  = document.getElementById('pc-emp').value.trim();
  if (!site || !emp) { toast('Site name and officer name are required.','error'); return; }
  const btn = document.getElementById('pc-submit-btn');
  btn.disabled = true; btn.textContent = 'Submitting…';

  let photoUrl = null;
  if (photoFile) {
    const ext  = photoFile.name.split('.').pop();
    const path = `postchecks/${Date.now()}.${ext}`;
    const { error: upErr } = await db().storage.from('photos').upload(path, photoFile);
    if (!upErr) photoUrl = db().storage.from('photos').getPublicUrl(path).data?.publicUrl;
  }

  const { error } = await db().from('post_checks').insert({
    site_name:        site,
    employee_on_duty: emp,
    supervisor_id:    profile?.id,
    supervisor_name:  profile?.display_name || '',
    anomalies:        document.getElementById('pc-anomaly').value.trim() || null,
    supply_request:   document.getElementById('pc-supply').value.trim() || null,
    uniform_request:  document.getElementById('pc-uniform').value.trim() || null,
    photo_url:        photoUrl,
    checked_at:       new Date().toISOString(),
  });

  btn.disabled = false;
  btn.innerHTML = `<svg width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.5" viewBox="0 0 24 24"><polyline points="20 6 9 17 4 12"/></svg> Submit Post Check`;

  if (error) { toast('Error: '+error.message,'error'); return; }
  toast('Post check submitted!','success');
  clearPostCheck();
}

function clearPostCheck() {
  ['pc-site','pc-emp','pc-anomaly','pc-supply','pc-uniform'].forEach(id => { const el = document.getElementById(id); if(el) el.value=''; });
  document.getElementById('pc-sup').value = profile?.display_name || '';
  document.getElementById('photo-prev').style.display = 'none';
  document.getElementById('site-info-card').style.display = 'none';
  document.getElementById('ac-list').classList.remove('open');
  photoFile = null;
}

function esc(s) { return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
function fmtDateTime(iso) { return iso ? new Date(iso).toLocaleDateString('en-US',{month:'short',day:'numeric'}) + ' ' + fmtTime(iso) : '—'; }
function fmtTime(iso) { return iso ? new Date(iso).toLocaleTimeString('en-US',{hour:'2-digit',minute:'2-digit'}) : '—'; }
function fmtDate(iso) { return iso ? new Date(iso).toLocaleDateString('en-US',{month:'short',day:'numeric'}) : ''; }
function isToday(iso) { if(!iso) return false; const d=new Date(iso),n=new Date(); return d.getDate()===n.getDate()&&d.getMonth()===n.getMonth()&&d.getFullYear()===n.getFullYear(); }

function toast(msg, type='info') {
  const d = document.createElement('div');
  d.className = `toast-item t-${type}`;
  const icon = type==='success'?'✓':type==='error'?'✕':'ℹ';
  d.innerHTML = `<span style="color:${type==='success'?'var(--success)':type==='error'?'var(--danger)':'var(--gold)'};">${icon}</span> ${esc(msg)}`;
  document.getElementById('toast').appendChild(d);
  setTimeout(() => d.remove(), 4000);
}

const NEGLECT_HRS = 48;
let neglectData = [];
let neglectFilter = 'all';
let neglectZone = '';

async function loadNeglect() {

  const { data: checks } = await db().from('post_checks')
    .select('site_name, checked_at')
    .order('checked_at', { ascending: false });

  const lastCheck = {};
  (checks || []).forEach(c => {
    if (!lastCheck[c.site_name]) lastCheck[c.site_name] = c.checked_at;
  });

  const now = Date.now();
  neglectData = props.map(p => {
    const lc = lastCheck[p.name];
    const hoursAgo = lc ? (now - new Date(lc).getTime()) / 3600000 : null;
    return { ...p, lastCheck: lc, hoursAgo };
  }).sort((a, b) => {

    if (a.hoursAgo === null && b.hoursAgo === null) return a.name.localeCompare(b.name);
    if (a.hoursAgo === null) return -1;
    if (b.hoursAgo === null) return 1;
    return b.hoursAgo - a.hoursAgo;
  });

  const flagged  = neglectData.filter(r => r.hoursAgo !== null && r.hoursAgo >= NEGLECT_HRS);
  const watch    = neglectData.filter(r => r.hoursAgo !== null && r.hoursAgo >= 24 && r.hoursAgo < NEGLECT_HRS);
  const ok       = neglectData.filter(r => r.hoursAgo !== null && r.hoursAgo < 24);
  const never    = neglectData.filter(r => r.hoursAgo === null);

  document.getElementById('neglect-stats').innerHTML = `
    <div class="stat-card c-danger">
      <div class="stat-label">Flagged 48h+</div>
      <div class="stat-value" style="color:var(--danger);">${flagged.length}</div>
      <div class="stat-sub">Overdue sites</div>
    </div>
    <div class="stat-card c-warn">
      <div class="stat-label">Watch 24–48h</div>
      <div class="stat-value" style="color:var(--warn);">${watch.length}</div>
      <div class="stat-sub">Approaching threshold</div>
    </div>
    <div class="stat-card c-success">
      <div class="stat-label">Checked &lt;24h</div>
      <div class="stat-value" style="color:var(--success);">${ok.length}</div>
      <div class="stat-sub">Recently checked</div>
    </div>
    <div class="stat-card">
      <div class="stat-label">Never Checked</div>
      <div class="stat-value" style="color:var(--danger);">${never.length}</div>
      <div class="stat-sub">No record found</div>
    </div>
  `;

  const zones = [...new Set(props.map(p => p.zone).filter(Boolean))].sort();
  const zSel = document.getElementById('neglect-zone-filter');
  zSel.innerHTML = '<option value="">All Zones</option>' + zones.map(z => `<option value="${esc(z)}">${esc(z)}</option>`).join('');
  zSel.value = neglectZone;

  renderNeglectTable();
  wireNeglectFilters();
}

function renderNeglectTable() {
  let filtered = neglectData;

  if (neglectZone) filtered = filtered.filter(r => r.zone === neglectZone);

  if (neglectFilter === 'flagged') filtered = filtered.filter(r => r.hoursAgo !== null && r.hoursAgo >= NEGLECT_HRS);
  else if (neglectFilter === 'watch')   filtered = filtered.filter(r => r.hoursAgo !== null && r.hoursAgo >= 24 && r.hoursAgo < NEGLECT_HRS);
  else if (neglectFilter === 'ok')      filtered = filtered.filter(r => r.hoursAgo !== null && r.hoursAgo < 24);
  else if (neglectFilter === 'never')   filtered = filtered.filter(r => r.hoursAgo === null);

  const tbody = document.getElementById('neglect-tbody');
  if (!filtered.length) {
    tbody.innerHTML = `<tr><td colspan="7" style="text-align:center;color:var(--muted);padding:30px;">No sites match this filter.</td></tr>`;
    return;
  }

  tbody.innerHTML = filtered.map((r, i) => {
    const never   = r.hoursAgo === null;
    const flagged  = !never && r.hoursAgo >= NEGLECT_HRS;
    const watch    = !never && r.hoursAgo >= 24 && r.hoursAgo < NEGLECT_HRS;
    const ok       = !never && r.hoursAgo < 24;

    const color    = never || flagged ? 'var(--danger)' : watch ? 'var(--warn)' : 'var(--success)';
    const pct      = never ? 100 : Math.min(100, (r.hoursAgo / NEGLECT_HRS) * 100);
    const hrsLabel = never ? '—' : r.hoursAgo < 1
      ? `${Math.round(r.hoursAgo * 60)}m`
      : `${r.hoursAgo.toFixed(1)}h`;

    const badge = never
      ? `<span class="badge badge-danger">Never</span>`
      : flagged
        ? `<span class="badge badge-danger">Flagged</span>`
        : watch
          ? `<span class="badge" style="background:rgba(245,158,11,.12);color:var(--warn);border:1px solid rgba(245,158,11,.2);">Watch</span>`
          : `<span class="badge badge-success">OK</span>`;

    return `<tr style="${flagged || never ? 'background:rgba(239,68,68,.03);' : ''}">
      <td class="neglect-rank">${i + 1}</td>
      <td><strong>${esc(r.name)}</strong></td>
      <td style="color:var(--text2);font-size:12px;">${esc(r.zone||'—')}</td>
      <td style="color:var(--text2);font-size:12px;">${esc(r.subzone||'—')}</td>
      <td style="font-size:12px;color:var(--muted);">
        ${never ? '<span class="never-checked">No check on record</span>' : fmtDateTime(r.lastCheck)}
      </td>
      <td>
        <div class="neglect-bar-wrap">
          <div class="neglect-bar">
            <div class="neglect-fill" style="width:${pct}%;background:${color};"></div>
          </div>
          <div class="neglect-hrs" style="color:${color};">${hrsLabel}</div>
        </div>
      </td>
      <td>${badge}</td>
    </tr>`;
  }).join('');
}

function wireNeglectFilters() {
  document.querySelectorAll('.filter-btn').forEach(btn => {
    btn.onclick = null;
    btn.addEventListener('click', () => {
      neglectFilter = btn.dataset.filter;
      document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      renderNeglectTable();
    });
  });
  document.getElementById('neglect-zone-filter').addEventListener('change', e => {
    neglectZone = e.target.value;
    renderNeglectTable();
  });
}

let activeShift = null;     // currently-open inspection
let viPhotoStart = null;
let viPhotoEnd   = null;
let viFuelStart  = '';
let viFuelEnd    = '';
let viCondExt    = '';
let viCondInt    = '';
let fleetCache   = [];

async function initVehicle() {
  resetVehicleForm();

  const { data: fleet } = await db().from('vehicles').select('*').order('plate');
  fleetCache = fleet || [];

  const { data: open } = await db().from('vehicle_inspections')
    .select('*')
    .eq('supervisor_id', profile.id)
    .is('end_at', null)
    .order('start_at', { ascending: false })
    .limit(1);

  if (open && open.length) {
    activeShift = open[0];
    showEndCard();
  } else {
    activeShift = null;
    showStartCard();
  }
  renderShiftStatus();
  loadMyInspections();
  wireVehicleEvents();
}

function renderShiftStatus() {
  const wrap = document.getElementById('shift-status-wrap');
  if (activeShift) {
    const startTime = new Date(activeShift.start_at);
    const hoursIn = ((Date.now() - startTime.getTime()) / 3600000).toFixed(1);
    wrap.innerHTML = `
      <div class="shift-status-card">
        <div class="shift-status-info">
          <div class="shift-pulse"></div>
          <div>
            <div class="shift-status-text"><strong>Shift Active</strong> — ${esc(activeShift.vehicle_desc)} (${esc(activeShift.plate)})</div>
            <div class="shift-status-meta">Started ${fmtDateTime(activeShift.start_at)} · ${hoursIn} hours in</div>
          </div>
        </div>
        <button class="btn btn-outline btn-sm" id="cancel-shift-btn">Cancel Shift</button>
      </div>`;
    document.getElementById('cancel-shift-btn').addEventListener('click', cancelShift);
  } else {
    wrap.innerHTML = `
      <div class="shift-status-card" style="border-color:var(--border2);background:var(--surface);">
        <div class="shift-status-info">
          <div class="shift-pulse idle"></div>
          <div>
            <div class="shift-status-text"><strong>No Active Shift</strong></div>
            <div class="shift-status-meta">Complete the start-of-shift inspection below to begin.</div>
          </div>
        </div>
      </div>`;
  }
}

function showStartCard() {
  document.getElementById('vi-start-card').style.display = 'block';
  document.getElementById('vi-end-card').style.display = 'none';
}

function showEndCard() {
  document.getElementById('vi-start-card').style.display = 'none';
  document.getElementById('vi-end-card').style.display = 'block';

  const a = activeShift;
  document.getElementById('vi-end-summary').innerHTML = `
    <div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:12px;">
      <div><div style="font-size:10px;color:var(--muted);text-transform:uppercase;letter-spacing:1px;">Vehicle</div><div><strong>${esc(a.vehicle_desc)}</strong></div></div>
      <div><div style="font-size:10px;color:var(--muted);text-transform:uppercase;letter-spacing:1px;">Plate</div><div style="font-family:'Bebas Neue',monospace;letter-spacing:2px;color:var(--gold);">${esc(a.plate)}</div></div>
      <div><div style="font-size:10px;color:var(--muted);text-transform:uppercase;letter-spacing:1px;">Mileage In</div><div><strong>${a.mileage_start?.toLocaleString() || '—'}</strong></div></div>
    </div>`;
}

async function lookupPlate() {
  const plate = document.getElementById('vi-plate').value.trim().toUpperCase();
  if (plate.length < 3) {
    document.getElementById('fleet-mini').classList.remove('show');
    return;
  }
  const known = fleetCache.find(v => v.plate === plate);
  const mini = document.getElementById('fleet-mini');
  if (known) {
    document.getElementById('vi-vehicle').value = known.description || '';
    mini.innerHTML = `<strong>✓ Known vehicle:</strong> ${esc(known.description)} · Last seen ${fmtDate(known.last_seen) || 'today'}`;
    mini.classList.add('show');
  } else {
    mini.innerHTML = `<strong>+ New vehicle</strong> — will be added to fleet after first inspection.`;
    mini.classList.add('show');
  }
}

async function startShift() {
  const plate    = document.getElementById('vi-plate').value.trim().toUpperCase();
  const vehicle  = document.getElementById('vi-vehicle').value.trim();
  const miStart  = parseInt(document.getElementById('vi-mi-start').value);

  if (!plate || !vehicle) { toast('License plate and vehicle description required.', 'error'); return; }
  if (isNaN(miStart) || miStart < 0) { toast('Valid starting mileage required.', 'error'); return; }
  if (!viFuelStart) { toast('Select fuel level.', 'error'); return; }
  if (!viCondExt || !viCondInt) { toast('Rate both exterior and interior condition.', 'error'); return; }
  if (!viPhotoStart) { toast('Photo of vehicle required.', 'error'); return; }

  const btn = document.getElementById('vi-start-btn');
  btn.disabled = true; btn.textContent = 'Starting…';

  const ext = viPhotoStart.name.split('.').pop();
  const path = `inspections/${Date.now()}_start.${ext}`;
  const { error: upErr } = await db().storage.from('photos').upload(path, viPhotoStart);
  let photoUrl = null;
  if (!upErr) photoUrl = db().storage.from('photos').getPublicUrl(path).data?.publicUrl;

  await db().from('vehicles').upsert({
    plate, description: vehicle, last_seen: new Date().toISOString(),
  }, { onConflict: 'plate' });

  const { data, error } = await db().from('vehicle_inspections').insert({
    supervisor_id:   profile.id,
    supervisor_name: profile.display_name,
    plate, vehicle_desc: vehicle,
    mileage_start:   miStart,
    fuel_start:      viFuelStart,
    exterior_start:  viCondExt,
    interior_start:  viCondInt,
    photo_start:     photoUrl,
    notes_start:     document.getElementById('vi-notes-start').value.trim() || null,
    start_at:        new Date().toISOString(),
  }).select().single();

  btn.disabled = false;
  btn.innerHTML = `<svg width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.5" viewBox="0 0 24 24"><polyline points="20 6 9 17 4 12"/></svg> Start Shift Inspection`;

  if (error) { toast('Error: ' + error.message, 'error'); return; }
  toast('Shift started!', 'success');
  activeShift = data;
  showEndCard();
  renderShiftStatus();
  loadMyInspections();
}

async function endShift() {
  if (!activeShift) { toast('No active shift.', 'error'); return; }
  const miEnd = parseInt(document.getElementById('vi-mi-end').value);
  if (isNaN(miEnd) || miEnd < activeShift.mileage_start) { toast('End mileage must be ≥ starting mileage.', 'error'); return; }
  if (!viFuelEnd)   { toast('Select end fuel level.', 'error'); return; }
  if (!viPhotoEnd)  { toast('End-of-shift photo required.', 'error'); return; }

  const btn = document.getElementById('vi-end-btn');
  btn.disabled = true; btn.textContent = 'Ending…';

  const ext = viPhotoEnd.name.split('.').pop();
  const path = `inspections/${Date.now()}_end.${ext}`;
  const { error: upErr } = await db().storage.from('photos').upload(path, viPhotoEnd);
  let photoUrl = null;
  if (!upErr) photoUrl = db().storage.from('photos').getPublicUrl(path).data?.publicUrl;

  const { error } = await db().from('vehicle_inspections').update({
    mileage_end:  miEnd,
    fuel_end:     viFuelEnd,
    photo_end:    photoUrl,
    notes_end:    document.getElementById('vi-notes-end').value.trim() || null,
    end_at:       new Date().toISOString(),
  }).eq('id', activeShift.id);

  btn.disabled = false;
  btn.innerHTML = `<svg width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.5" viewBox="0 0 24 24"><polyline points="20 6 9 17 4 12"/></svg> End Shift Inspection`;

  if (error) { toast('Error: ' + error.message, 'error'); return; }
  toast('Shift ended successfully!', 'success');
  activeShift = null;
  resetVehicleForm();
  showStartCard();
  renderShiftStatus();
  loadMyInspections();
}

async function cancelShift() {
  if (!confirm('Cancel this shift? The inspection will be deleted.')) return;
  await db().from('vehicle_inspections').delete().eq('id', activeShift.id);
  toast('Shift cancelled.', 'info');
  activeShift = null;
  resetVehicleForm();
  showStartCard();
  renderShiftStatus();
}

function resetVehicleForm() {
  ['vi-plate','vi-vehicle','vi-mi-start','vi-mi-end','vi-notes-start','vi-notes-end'].forEach(id => {
    const el = document.getElementById(id); if (el) el.value = '';
  });
  document.getElementById('vi-photo-prev').style.display = 'none';
  document.getElementById('vi-end-photo-prev').style.display = 'none';
  document.getElementById('fleet-mini').classList.remove('show');
  viPhotoStart = null; viPhotoEnd = null;
  viFuelStart = ''; viFuelEnd = '';
  viCondExt = ''; viCondInt = '';
  document.querySelectorAll('#vi-fuel-start .pill, #vi-fuel-end .pill').forEach(p => p.classList.remove('selected'));
  document.querySelectorAll('.cond-pill').forEach(p => {
    p.classList.remove('selected-good','selected-fair','selected-poor');
  });
}

async function loadMyInspections() {
  const { data } = await db().from('vehicle_inspections')
    .select('*')
    .eq('supervisor_id', profile.id)
    .order('start_at', { ascending: false })
    .limit(10);
  const tbody = document.getElementById('vi-history-tbody');
  if (!data?.length) {
    tbody.innerHTML = `<tr><td colspan="6" style="text-align:center;color:var(--muted);padding:30px;">No inspections yet.</td></tr>`;
    return;
  }
  tbody.innerHTML = data.map(r => {
    const miles = (r.mileage_end && r.mileage_start) ? `${(r.mileage_end - r.mileage_start).toLocaleString()} mi` : '—';
    const status = r.end_at
      ? `<span class="badge badge-success">Completed</span>`
      : `<span class="badge badge-gold">Active</span>`;
    return `<tr>
      <td style="font-size:12px;color:var(--text2);">${fmtDateTime(r.start_at)}</td>
      <td>${esc(r.vehicle_desc)}</td>
      <td style="font-family:monospace;color:var(--gold);font-size:12px;">${esc(r.plate)}</td>
      <td>${miles}</td>
      <td style="font-size:12px;">${esc(r.fuel_start)} → ${esc(r.fuel_end||'—')}</td>
      <td>${status}</td>
    </tr>`;
  }).join('');
}

let _viWired = false;
function wireVehicleEvents() {
  if (_viWired) return;
  _viWired = true;

  document.getElementById('vi-plate').addEventListener('input', e => {
    e.target.value = e.target.value.toUpperCase();
    lookupPlate();
  });

  document.querySelectorAll('#vi-fuel-start .pill').forEach(p => {
    p.addEventListener('click', () => {
      document.querySelectorAll('#vi-fuel-start .pill').forEach(x => x.classList.remove('selected'));
      p.classList.add('selected');
      viFuelStart = p.dataset.val;
    });
  });
  document.querySelectorAll('#vi-fuel-end .pill').forEach(p => {
    p.addEventListener('click', () => {
      document.querySelectorAll('#vi-fuel-end .pill').forEach(x => x.classList.remove('selected'));
      p.classList.add('selected');
      viFuelEnd = p.dataset.val;
    });
  });

  document.querySelectorAll('.condition-pills').forEach(grp => {
    const cond = grp.dataset.cond;
    grp.querySelectorAll('.cond-pill').forEach(p => {
      p.addEventListener('click', () => {
        grp.querySelectorAll('.cond-pill').forEach(x => x.classList.remove('selected-good','selected-fair','selected-poor'));
        const val = p.dataset.val;
        p.classList.add(`selected-${val.toLowerCase()}`);
        if (cond === 'exterior') viCondExt = val;
        else if (cond === 'interior') viCondInt = val;
      });
    });
  });

  document.getElementById('vi-mi-end').addEventListener('input', e => {
    if (activeShift && activeShift.mileage_start && e.target.value) {
      const diff = parseInt(e.target.value) - activeShift.mileage_start;
      document.getElementById('vi-miles-driven').textContent = diff >= 0 ? `${diff.toLocaleString()} miles driven this shift` : '⚠ End mileage less than start';
    } else {
      document.getElementById('vi-miles-driven').textContent = '';
    }
  });

  document.getElementById('vi-photo-drop').addEventListener('click', () => document.getElementById('vi-photo-input').click());
  document.getElementById('vi-photo-input').addEventListener('change', e => {
    const f = e.target.files[0]; if (!f) return;
    viPhotoStart = f;
    const prev = document.getElementById('vi-photo-prev');
    prev.src = URL.createObjectURL(f); prev.style.display = 'block';
  });
  document.getElementById('vi-end-photo-drop').addEventListener('click', () => document.getElementById('vi-end-photo-input').click());
  document.getElementById('vi-end-photo-input').addEventListener('change', e => {
    const f = e.target.files[0]; if (!f) return;
    viPhotoEnd = f;
    const prev = document.getElementById('vi-end-photo-prev');
    prev.src = URL.createObjectURL(f); prev.style.display = 'block';
  });

  document.getElementById('vi-start-btn').addEventListener('click', startShift);
  document.getElementById('vi-end-btn').addEventListener('click', endShift);
}

async function loadFleet() {
  const { data, error } = await db().from('vehicle_inspections')
    .select('*')
    .order('start_at', { ascending: false })
    .limit(100);
  if (error) { console.error(error); return; }

  const todayInsp = (data||[]).filter(r => isToday(r.start_at));
  const active    = (data||[]).filter(r => !r.end_at);
  const flagged   = (data||[]).filter(r => (r.notes_start && r.notes_start.length > 5) || (r.notes_end && r.notes_end.length > 5) || r.exterior_start === 'Poor' || r.interior_start === 'Poor');

  document.getElementById('fleet-stats').innerHTML = `
    <div class="stat-card c-gold"><div class="stat-label">Inspections Today</div><div class="stat-value">${todayInsp.length}</div></div>
    <div class="stat-card c-success"><div class="stat-label">Active Shifts</div><div class="stat-value">${active.length}</div></div>
    <div class="stat-card c-warn"><div class="stat-label">Flagged Issues</div><div class="stat-value" style="color:var(--warn);">${flagged.length}</div></div>
    <div class="stat-card"><div class="stat-label">Total Records</div><div class="stat-value">${data?.length || 0}</div></div>
  `;

  const tbody = document.getElementById('fleet-tbody');
  if (!data?.length) {
    tbody.innerHTML = `<tr><td colspan="8" style="text-align:center;color:var(--muted);padding:30px;">No inspections yet.</td></tr>`;
    return;
  }
  tbody.innerHTML = data.map(r => {
    const miles = (r.mileage_end && r.mileage_start) ? (r.mileage_end - r.mileage_start).toLocaleString() : '—';
    const condBadge = (c) => {
      if (!c) return '—';
      if (c === 'Good') return `<span class="badge badge-success">${c}</span>`;
      if (c === 'Fair') return `<span class="badge" style="background:rgba(245,158,11,.12);color:var(--warn);">${c}</span>`;
      return `<span class="badge badge-danger">${c}</span>`;
    };
    const cond = `${condBadge(r.exterior_start)} ${condBadge(r.interior_start)}`;
    const status = r.end_at
      ? `<span class="badge badge-success">Completed</span>`
      : `<span class="badge badge-gold">Active</span>`;
    const photos = [r.photo_start, r.photo_end].filter(Boolean).map(p => `<a href="${esc(p)}" target="_blank" style="display:inline-block;width:32px;height:32px;border-radius:4px;overflow:hidden;border:1px solid var(--border2);margin-right:3px;"><img src="${esc(p)}" style="width:100%;height:100%;object-fit:cover;"/></a>`).join('');
    return `<tr>
      <td style="font-size:12px;color:var(--text2);">${fmtDateTime(r.start_at)}</td>
      <td>${esc(r.vehicle_desc)}</td>
      <td style="font-family:monospace;color:var(--gold);font-size:12px;">${esc(r.plate)}</td>
      <td>${esc(r.supervisor_name||'—')}</td>
      <td>${miles}</td>
      <td>${cond}</td>
      <td>${status}</td>
      <td>${photos || '—'}</td>
    </tr>`;
  }).join('');
}

async function loadUsers() {
  const { data, error } = await db().from('profiles').select('*').order('display_name');
  const tbody = document.getElementById('users-tbody');
  if (error || !data?.length) {
    tbody.innerHTML = `<tr><td colspan="5" style="text-align:center;color:var(--muted);padding:30px;">${error ? 'Error loading users.' : 'No users yet.'}</td></tr>`;
    return;
  }
  tbody.innerHTML = data.map(u => `
    <tr>
      <td style="font-family:monospace;font-size:12px;color:var(--text2);">${esc(u.username||'—')}</td>
      <td><strong>${esc(u.display_name||'—')}</strong></td>
      <td><span class="role-chip role-${u.role}">${roleLabel(u.role)}</span></td>
      <td style="color:var(--text2);">${esc(u.zone||'—')}</td>
      <td>
        <button class="btn btn-outline btn-sm" onclick_data="${esc(u.id)}" data-name="${esc(u.display_name)}" data-role="${esc(u.role)}" data-zone="${esc(u.zone||'')}" data-action="edit-user">Edit</button>
      </td>
    </tr>
  `).join('');

  tbody.querySelectorAll('[data-action="edit-user"]').forEach(btn => {
    btn.addEventListener('click', () => {
      document.getElementById('eu-id').value      = btn.getAttribute('onclick_data');
      document.getElementById('eu-display').value = btn.dataset.name;
      document.getElementById('eu-role').value    = btn.dataset.role;
      document.getElementById('eu-zone').value    = btn.dataset.zone;
      document.getElementById('modal-edituser').classList.remove('hidden');
    });
  });
}

async function createUser() {
  const username = document.getElementById('nu-username').value.trim().toLowerCase().replace(/\s+/g,'');
  const display  = document.getElementById('nu-display').value.trim();
  const role     = document.getElementById('nu-role').value;
  const zone     = document.getElementById('nu-zone').value.trim();
  const pass     = document.getElementById('nu-pass').value;
  if (!username || !display || !pass) { toast('Username, display name, and password are required.','error'); return; }
  const email = `${username}@${EG_DOMAIN}`;

  const { data: { session: curSession } } = await db().auth.getSession();

  const { data, error } = await db().auth.signUp({
    email,
    password: pass,
    options: { data: { username, display_name: display } }
  });
  if (error) { toast('Error: ' + error.message, 'error'); return; }
  if (!data.user) { toast('Could not create user.', 'error'); return; }

  await db().from('profiles').upsert({ id: data.user.id, username, display_name: display, role, zone: zone||null, features: [] });

  if (curSession) {
    await db().auth.setSession({ access_token: curSession.access_token, refresh_token: curSession.refresh_token });
  }
  toast('User created!', 'success');
  document.getElementById('modal-adduser').classList.add('hidden');
  ['nu-username','nu-display','nu-zone','nu-pass'].forEach(id => document.getElementById(id).value = '');
  loadUsers();
}

async function saveEditUser() {
  const id      = document.getElementById('eu-id').value;
  const display = document.getElementById('eu-display').value.trim();
  const role    = document.getElementById('eu-role').value;
  const zone    = document.getElementById('eu-zone').value.trim();
  const { error } = await db().from('profiles').update({ display_name: display, role, zone: zone||null }).eq('id', id);
  if (error) { toast('Error: ' + error.message, 'error'); return; }
  toast('User updated.', 'success');
  document.getElementById('modal-edituser').classList.add('hidden');
  loadUsers();
}

async function loadProperties() {
  const { data, error } = await db().from('properties').select('*').order('name');
  props = data || [];
  const tbody = document.getElementById('props-tbody');
  if (error || !data?.length) {
    tbody.innerHTML = `<tr><td colspan="6" style="text-align:center;color:var(--muted);padding:30px;">${error ? 'Error loading properties.' : 'No properties yet. Add one or import from Deputy.'}</td></tr>`;
    return;
  }
  tbody.innerHTML = data.map(p => `
    <tr>
      <td><strong>${esc(p.name)}</strong></td>
      <td style="font-size:12px;color:var(--text2);">${esc(p.address||'—')}</td>
      <td>${esc(p.zone||'—')}</td>
      <td>${esc(p.subzone||'—')}</td>
      <td style="font-size:12px;">${esc(p.schedule||'—')}</td>
      <td>
        <button class="btn btn-danger-soft btn-sm" data-id="${esc(p.id)}" data-action="del-prop">Delete</button>
      </td>
    </tr>
  `).join('');
  tbody.querySelectorAll('[data-action="del-prop"]').forEach(btn => {
    btn.addEventListener('click', async () => {
      if (!confirm('Delete this property?')) return;
      await db().from('properties').delete().eq('id', btn.dataset.id);
      toast('Property deleted.', 'info');
      loadProperties();
    });
  });
}

async function createProperty() {
  const name = document.getElementById('np-name').value.trim();
  if (!name) { toast('Site name is required.', 'error'); return; }
  const { error } = await db().from('properties').insert({
    name,
    address:  document.getElementById('np-addr').value.trim() || null,
    zone:     document.getElementById('np-zone').value.trim() || null,
    subzone:  document.getElementById('np-subzone').value.trim() || null,
    schedule: document.getElementById('np-schedule').value.trim() || null,
  });
  if (error) { toast('Error: ' + error.message, 'error'); return; }
  toast('Property added!', 'success');
  document.getElementById('modal-addprop').classList.add('hidden');
  ['np-name','np-addr','np-zone','np-subzone','np-schedule'].forEach(id => document.getElementById(id).value = '');
  loadProperties();
}

async function loadSchedules() {
  const { data } = await db().from('schedules').select('*').order('created_at', { ascending: false }).limit(200);
  const tbody = document.getElementById('sched-tbody');
  if (!data?.length) {
    tbody.innerHTML = `<tr><td colspan="6" style="text-align:center;color:var(--muted);padding:30px;">No schedules imported yet.</td></tr>`;
    return;
  }
  tbody.innerHTML = data.map(s => `
    <tr>
      <td><strong>${esc(s.site_name)}</strong></td>
      <td style="font-size:12px;color:var(--text2);">${esc(s.days||'—')}</td>
      <td>${esc(s.shift_start||'—')}</td>
      <td>${esc(s.shift_end||'—')}</td>
      <td><span class="badge ${s.active ? 'badge-success' : 'badge-muted'}">${s.active ? 'Active' : 'Inactive'}</span></td>
      <td>
        <button class="btn btn-outline btn-sm" data-id="${esc(s.id)}" data-active="${s.active}" data-action="toggle-sched">
          ${s.active ? 'Deactivate' : 'Activate'}
        </button>
      </td>
    </tr>
  `).join('');
  tbody.querySelectorAll('[data-action="toggle-sched"]').forEach(btn => {
    btn.addEventListener('click', async () => {
      const cur = btn.dataset.active === 'true';
      await db().from('schedules').update({ active: !cur }).eq('id', btn.dataset.id);
      loadSchedules();
    });
  });
}

function processCSV(file) {
  Papa.parse(file, {
    header: true, skipEmptyLines: true,
    complete: async (results) => {
      const rows = results.data;
      if (!rows.length) { toast('No data found in file.', 'error'); return; }
      const keys = Object.keys(rows[0]);
      const siteCol  = keys.find(k => /(area|location|site|post|property)/i.test(k));
      const startCol = keys.find(k => /(start|from|begin)/i.test(k));
      const endCol   = keys.find(k => /(end|to|finish)/i.test(k));
      const daysCol  = keys.find(k => /(day|date)/i.test(k));
      if (!siteCol) { toast('Could not detect site/location column in CSV.', 'error'); return; }
      const unique = [...new Set(rows.map(r => r[siteCol]).filter(Boolean))];
      const preview = document.getElementById('csv-preview');
      preview.style.display = 'block';
      preview.innerHTML = `
        <div class="card">
          <div class="card-header"><div class="card-title">${unique.length} unique sites found</div></div>
          <div class="card-body">
            <div style="display:flex;flex-wrap:wrap;gap:8px;margin-bottom:16px;">
              ${unique.map(s => `<span class="badge badge-gold">${esc(s)}</span>`).join('')}
            </div>
            <button class="btn btn-gold btn-sm" id="confirm-import-btn">Import ${rows.length} Schedule Rows</button>
          </div>
        </div>`;
      document.getElementById('confirm-import-btn').addEventListener('click', async () => {
        const inserts = rows.map(r => ({
          site_name:   r[siteCol],
          shift_start: startCol ? r[startCol] : null,
          shift_end:   endCol   ? r[endCol]   : null,
          days:        daysCol  ? r[daysCol]  : null,
          active:      true,
        })).filter(r => r.site_name);
        const { error } = await db().from('schedules').insert(inserts);
        if (error) { toast('Import error: ' + error.message, 'error'); return; }

        const existing = props.map(p => p.name.toLowerCase());
        const newSites = unique.filter(s => !existing.includes(s.toLowerCase()));
        if (newSites.length) {
          await db().from('properties').insert(newSites.map(name => ({ name })));
        }
        toast(`Imported ${inserts.length} schedules. ${newSites.length} new properties added.`, 'success');
        preview.style.display = 'none';

        const { data: propData } = await db().from('properties').select('*').order('name');
        props = propData || [];
        loadSchedules();
      });
    }
  });
}

function wireAdminEvents() {

  document.getElementById('add-user-btn')?.addEventListener('click', () => document.getElementById('modal-adduser').classList.remove('hidden'));
  document.getElementById('adduser-cancel-btn')?.addEventListener('click', () => document.getElementById('modal-adduser').classList.add('hidden'));
  document.getElementById('adduser-save-btn')?.addEventListener('click', createUser);
  document.getElementById('edituser-cancel-btn')?.addEventListener('click', () => document.getElementById('modal-edituser').classList.add('hidden'));
  document.getElementById('edituser-save-btn')?.addEventListener('click', saveEditUser);

  document.getElementById('add-prop-btn')?.addEventListener('click', () => document.getElementById('modal-addprop').classList.remove('hidden'));
  document.getElementById('addprop-cancel-btn')?.addEventListener('click', () => document.getElementById('modal-addprop').classList.add('hidden'));
  document.getElementById('addprop-save-btn')?.addEventListener('click', createProperty);

  document.getElementById('csv-drop')?.addEventListener('click', () => document.getElementById('csv-input').click());
  document.getElementById('csv-drop')?.addEventListener('dragover', e => { e.preventDefault(); e.currentTarget.classList.add('drag'); });
  document.getElementById('csv-drop')?.addEventListener('dragleave', e => e.currentTarget.classList.remove('drag'));
  document.getElementById('csv-drop')?.addEventListener('drop', e => {
    e.preventDefault(); e.currentTarget.classList.remove('drag');
    const f = e.dataTransfer.files[0]; if(f) processCSV(f);
  });
  document.getElementById('csv-input')?.addEventListener('change', e => { if(e.target.files[0]) processCSV(e.target.files[0]); });
  document.getElementById('clear-sched-btn')?.addEventListener('click', async () => {
    if (!confirm('Clear all imported schedules?')) return;
    await db().from('schedules').delete().neq('id', '00000000-0000-0000-0000-000000000000');
    toast('Schedules cleared.', 'info');
    loadSchedules();
  });

  document.querySelectorAll('.modal-bg').forEach(m => m.addEventListener('click', e => { if(e.target === m) m.classList.add('hidden'); }));
}

window.addEventListener('load', async () => {

  document.getElementById('l-btn').addEventListener('click', doLogin);
  document.getElementById('l-pass').addEventListener('keydown', e => { if(e.key==='Enter') doLogin(); });
  document.getElementById('l-user').addEventListener('keydown', e => { if(e.key==='Enter') doLogin(); });

  document.getElementById('logout-btn').addEventListener('click', doLogout);

  document.getElementById('pc-site').addEventListener('input', e => acSearch(e.target.value));
  document.getElementById('pc-submit-btn').addEventListener('click', submitPostCheck);
  document.getElementById('pc-clear-btn').addEventListener('click', clearPostCheck);

  document.getElementById('photo-drop').addEventListener('click', () => document.getElementById('photo-input').click());
  document.getElementById('photo-drop').addEventListener('dragover', e => { e.preventDefault(); e.currentTarget.classList.add('drag'); });
  document.getElementById('photo-drop').addEventListener('dragleave', e => e.currentTarget.classList.remove('drag'));
  document.getElementById('photo-drop').addEventListener('drop', e => {
    e.preventDefault(); e.currentTarget.classList.remove('drag');
    const f = e.dataTransfer.files[0]; if(f && f.type.startsWith('image/')) setPhoto(f);
  });
  document.getElementById('photo-input').addEventListener('change', e => { if(e.target.files[0]) setPhoto(e.target.files[0]); });

  document.addEventListener('click', e => {
    if (!e.target.closest('.ac-wrap')) document.getElementById('ac-list').classList.remove('open');
  });

  if (SUPA_URL === 'YOUR_SUPABASE_URL') return;
  const { data: { session } } = await db().auth.getSession();
  if (session?.user) await boot(session.user);
});

function setPhoto(f) {
  photoFile = f;
  const prev = document.getElementById('photo-prev');
  prev.src = URL.createObjectURL(f);
  prev.style.display = 'block';
}

// EOF
// EOF
// EOF
// EOF
// EOF
