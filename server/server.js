// 小染注入后端 —— 管理控制台 + 客户端 API（Node，无第三方依赖）
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = __dirname, DATA = path.join(ROOT, 'data');
const FILES = path.join(DATA, 'files'), MUSIC = path.join(DATA, 'music'), VID = path.join(DATA, 'videos');
const STATE = path.join(DATA, 'state.json');
const ADMIN = process.env.ADMIN_KEY || '小染nb2026';
for (const d of [DATA, FILES, MUSIC, VID]) fs.mkdirSync(d, { recursive: true });

// ---- 状态 ----
let state = {
  cards: {},           // key -> {type, createdAt, expiresAt, banned, banReason, boundDevice, unbindCount}
  announcement: { title: '', content: '', icon: '' },
  update: { enabled: false, minVersion: '0.0.0', url: '', force: false, content: '', image: '' },
  serviceDisabled: false,
  importDir: { default: '', pak: '' },
  videoBg: '',
  cs: { qa: [], inbox: [] },
  devices: {},        // deviceId -> lastSeenMs
  totalUsers: 0,
};
function load() { try { state = Object.assign(state, JSON.parse(fs.readFileSync(STATE, 'utf8'))); } catch (e) {} }
function save() { fs.writeFileSync(STATE, JSON.stringify(state, null, 2)); }
load();

// ---- helpers ----
function json(res, code, o) { const b = JSON.stringify(o); res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' }); res.end(b); }
function body(req) { return new Promise((res) => { let s = ''; req.on('data', c => { s += c; if (s.length > 40 * 1024 * 1024) req.destroy(); }); req.on('end', () => { try { res(JSON.parse(s || '{}')); } catch (e) { res({}); } }); }); }
function admin(req) {
  const h = req.headers['x-admin'] || '';
  if (h === ADMIN) return true;
  // 非 ASCII（如中文）在 HTTP 头里会被按 latin1 解码，还原为 UTF-8 再比一次
  try { if (Buffer.from(h, 'latin1').toString('utf8') === ADMIN) return true; } catch (e) {}
  return false;
}

// ---- 卡密生成：格式 alnum/digits/letters/custom(+前缀)，大小写；期限 永久/月/周/天 ----
function randChars(pool, n) { const a = []; for (let i = 0; i < n; i++) a.push(pool[crypto.randomInt(pool.length)]); return a.join(''); }
function genCardKey(fmt, prefix, len, upper) {
  len = (len | 0) || 6;
  let pool;
  if (fmt === 'digits') pool = '0123456789';
  else if (fmt === 'letters') pool = upper ? 'ABCDEFGHIJKLMNOPQRSTUVWXYZ' : 'abcdefghijklmnopqrstuvwxyz';
  else if (fmt === 'custom') pool = 'abcdefghijklmnopqrstuvwxyz0123456789';
  else pool = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'; // alnum
  let key = '';
  if (fmt === 'custom' && prefix) key += prefix + '-';
  key += randChars(pool, len);
  return key;
}
const DUR = { permanent: 0, month: 30, week: 7, day: 1 };
function genCard(o) {
  const key = genCardKey(o.format, o.prefix, o.len, o.upper);
  const days = DUR[o.dur] != null ? DUR[o.dur] : 0;
  state.cards[key] = {
    type: o.dur || 'permanent', createdAt: Date.now(),
    expiresAt: days ? Date.now() + days * 86400000 : 0,
    banned: false, banReason: '', boundDevice: null, unbindCount: 0, label: o.label || '',
  };
  save();
  return state.cards[key];
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x');
  const p = u.pathname, m = req.method;

  if (m === 'OPTIONS') {
    res.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS', 'Access-Control-Allow-Headers': '*' });
    return res.end();
  }

  // 控制面板
  if (m === 'GET' && (p === '/' || p === '/admin' || p === '/index.html')) {
    try { res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Access-Control-Allow-Origin': '*' }); return res.end(fs.readFileSync(path.join(ROOT, 'admin.html'))); }
    catch (e) { res.writeHead(404); return res.end('no admin.html'); }
  }
  // 上传的视频背景
  if (m === 'GET' && p === '/videoBg') {
    try { const f = path.join(VID, 'bg.mp4'); res.writeHead(200, { 'Content-Type': 'video/mp4', 'Content-Length': fs.statSync(f).size, 'Access-Control-Allow-Origin': '*' }); fs.createReadStream(f).pipe(res); }
    catch (e) { res.writeHead(404); res.end('no video'); }
    return;
  }

  // ============ 卡密：管理 ============
  if (m === 'POST' && p === '/api/admin/generateCard') {
    if (!admin(req)) return json(res, 403, { ok: false, msg: '未授权' });
    const o = await body(req);
    const c = genCard(o);
    return json(res, 200, { ok: true, key: Object.keys(state.cards).find(k => state.cards[k] === c) });
  }
  if (m === 'POST' && p === '/api/admin/deleteCard') {
    if (!admin(req)) return json(res, 403, { ok: false, msg: '未授权' });
    const o = await body(req); delete state.cards[String(o.key || '')]; save();
    return json(res, 200, { ok: true });
  }
  if (m === 'POST' && p === '/api/admin/banCard') {
    if (!admin(req)) return json(res, 403, { ok: false, msg: '未授权' });
    const o = await body(req); const c = state.cards[String(o.key || '')];
    if (!c) return json(res, 200, { ok: false, msg: '卡密不存在' });
    if (o.ban === false) { c.banned = false; c.banReason = ''; }
    else { c.banned = true; c.banReason = String(o.reason || ''); }
    save(); return json(res, 200, { ok: true, banned: c.banned, reason: c.banReason });
  }
  if (m === 'GET' && p === '/api/admin/cards') {
    if (!admin(req)) return json(res, 403, { ok: false, msg: '未授权' });
    return json(res, 200, { ok: true, cards: state.cards });
  }

  // ============ 卡密：客户端 ============
  if (m === 'POST' && p === '/api/card/verify') {
    const o = await body(req); const c = state.cards[String(o.key || '')];
    const dev = String(o.deviceId || '');
    if (!c) return json(res, 200, { ok: false, msg: '卡密不存在' });
    if (c.banned) return json(res, 200, { ok: false, msg: '已被封禁' + (c.banReason ? '：' + c.banReason : '') });
    if (c.expiresAt && Date.now() > c.expiresAt) return json(res, 200, { ok: false, msg: '卡密已过期' });
    if (c.boundDevice && c.boundDevice !== dev) return json(res, 200, { ok: false, msg: '该卡密已被其他设备绑定' });
    c.boundDevice = dev || c.boundDevice;
    if (dev) { state.devices[dev] = Date.now(); state.totalUsers = Object.keys(state.devices).length; }
    save();
    return json(res, 200, { ok: true, msg: '验证通过', type: c.type });
  }
  if (m === 'POST' && p === '/api/card/unbind') {
    const o = await body(req); const c = state.cards[String(o.key || '')];
    if (!c) return json(res, 200, { ok: false, msg: '卡密不存在' });
    if (c.unbindCount >= 1) return json(res, 200, { ok: false, msg: '每个卡密仅可解绑一次' });
    c.boundDevice = null; c.unbindCount = 1; save();
    return json(res, 200, { ok: true, msg: '已解绑' });
  }
  if (m === 'POST' && p === '/api/heartbeat') {
    const o = await body(req); const dev = String(o.deviceId || '');
    if (dev) { state.devices[dev] = Date.now(); save(); }
    return json(res, 200, { ok: true });
  }
  if (m === 'GET' && p === '/api/stats') {
    const now = Date.now(); let online = 0;
    for (const d in state.devices) if (now - state.devices[d] < 5 * 60000) online++;
    return json(res, 200, { ok: true, online, total: state.totalUsers || Object.keys(state.devices).length });
  }

  // ============ 状态（客户端拉取）============
  if (m === 'GET' && p === '/api/status') {
    return json(res, 200, {
      serviceDisabled: state.serviceDisabled,
      announcement: state.announcement,
      update: state.update,
      online: (() => { let n = 0, now = Date.now(); for (const d in state.devices) if (now - state.devices[d] < 5 * 60000) n++; return n; })(),
      total: state.totalUsers,
    });
  }

  // ============ 配置 / 公告 / 更新 / 视频 / 目录 ============
  if (m === 'GET' && p === '/api/config') {
    return json(res, 200, { ok: true, importDir: state.importDir, videoBg: '/videoBg' });
  }
  if (m === 'POST' && p === '/api/admin/announce') {
    if (!admin(req)) return json(res, 403, { ok: false, msg: '未授权' });
    const o = await body(req); state.announcement = { title: String(o.title || ''), content: String(o.content || ''), icon: String(o.icon || '') }; save();
    return json(res, 200, { ok: true });
  }
  if (m === 'POST' && p === '/api/admin/update') {
    if (!admin(req)) return json(res, 403, { ok: false, msg: '未授权' });
    const o = await body(req);
    state.update = { enabled: !!o.enabled, minVersion: String(o.minVersion || '0.0.0'), url: String(o.url || ''), force: !!o.force, content: String(o.content || ''), image: String(o.image || '') };
    save(); return json(res, 200, { ok: true, update: state.update });
  }
  if (m === 'POST' && p === '/api/admin/disable') {
    if (!admin(req)) return json(res, 403, { ok: false, msg: '未授权' });
    const o = await body(req); state.serviceDisabled = !!o.on; save();
    return json(res, 200, { ok: true, serviceDisabled: state.serviceDisabled });
  }
  if (m === 'POST' && p === '/api/admin/setImportDir') {
    if (!admin(req)) return json(res, 403, { ok: false, msg: '未授权' });
    const o = await body(req); state.importDir[String(o.which || 'default')] = String(o.path || ''); save();
    return json(res, 200, { ok: true, importDir: state.importDir });
  }
  if (m === 'POST' && p === '/api/admin/uploadVideo') {
    if (!admin(req)) return json(res, 403, { ok: false, msg: '未授权' });
    const o = await body(req); if (!o.contentBase64) return json(res, 400, { ok: false, msg: '缺少 contentBase64' });
    fs.writeFileSync(path.join(VID, 'bg.mp4'), Buffer.from(o.contentBase64, 'base64'));
    save(); return json(res, 200, { ok: true, videoBg: '/videoBg' });
  }

  // ============ 客服（问答 + 留言收件箱）============
  if (m === 'GET' && p === '/api/csQa') {
    return json(res, 200, { ok: true, qa: state.cs.qa });
  }
  if (m === 'POST' && p === '/api/admin/csQa') {
    if (!admin(req)) return json(res, 403, { ok: false, msg: '未授权' });
    const o = await body(req);
    if (o.del) { state.cs.qa = state.cs.qa.filter(x => x.id !== o.del); save(); return json(res, 200, { ok: true }); }
    state.cs.qa.push({ id: crypto.randomBytes(4).toString('hex'), q: String(o.q || ''), a: String(o.a || '') }); save();
    return json(res, 200, { ok: true, qa: state.cs.qa });
  }
  // 客户端：用户把消息发给客服（含卡密/设备标识 + 文本 + 媒体dataurl）
  if (m === 'POST' && p === '/api/cs/message') {
    const o = await body(req);
    state.cs.inbox.push({ ts: Date.now(), card: String(o.card || ''), device: String(o.device || ''), text: String(o.text || ''), media: o.media ? String(o.media).slice(0, 500000) : '' });
    if (state.cs.inbox.length > 500) state.cs.inbox = state.cs.inbox.slice(-500);
    save();
    return json(res, 200, { ok: true, msg: '已转人工，留言看到会回复' });
  }
  if (m === 'GET' && p === '/api/admin/csInbox') {
    if (!admin(req)) return json(res, 403, { ok: false, msg: '未授权' });
    return json(res, 200, { ok: true, inbox: state.cs.inbox.slice().reverse() });
  }

  // ============ 文件 / 音乐（分开）============
  const manifest = (k) => path.join(DATA, k === 'music' ? 'music.json' : 'files.json');
  const rdList = (k) => { try { return JSON.parse(fs.readFileSync(manifest(k), 'utf8')); } catch (e) { return []; } };
  const wrList = (k, a) => fs.writeFileSync(manifest(k), JSON.stringify(a, null, 2));
  if (m === 'POST' && (p === '/api/file/upload' || p === '/api/music/upload')) {
    const kind = p.endsWith('/music/upload') ? 'music' : 'files';
    const o = await body(req); if (!o.contentBase64) return json(res, 400, { ok: false, msg: '缺少 contentBase64' });
    const name = String(o.name || 'file').replace(/[\/\\:*?"<>|]/g, '_');
    const id = crypto.randomBytes(6).toString('hex');
    fs.writeFileSync(path.join(kind === 'music' ? MUSIC : FILES, id), Buffer.from(o.contentBase64, 'base64'));
    const list = rdList(kind); list.push({ id, name, size: fs.statSync(path.join(kind === 'music' ? MUSIC : FILES, id)).size, url: (kind === 'music' ? '/api/music/' : '/api/files/') + id, at: Date.now() });
    wrList(kind, list);
    return json(res, 200, { ok: true, id, name, url: (kind === 'music' ? '/api/music/' : '/api/files/') + id });
  }
  if (m === 'GET' && p === '/api/files') return json(res, 200, { ok: true, files: rdList('files') });
  if (m === 'GET' && p === '/api/music') return json(res, 200, { ok: true, files: rdList('music') });
  const serveFile = (kind, id) => {
    const f = rdList(kind).find(x => x.id === id); if (!f) { res.writeHead(404); return res.end('not found'); }
    const fp = path.join(kind === 'music' ? MUSIC : FILES, id), st = fs.statSync(fp);
    const rg = req.headers['range'];
    if (rg) {
      const mrg = /bytes=(\d*)-(\d*)/.exec(rg); let s = mrg && mrg[1] ? +mrg[1] : 0, e = mrg && mrg[2] ? +mrg[2] : st.size - 1;
      if (e > st.size - 1) e = st.size - 1;
      res.writeHead(206, { 'Content-Type': kind === 'music' ? 'audio/*' : 'application/octet-stream', 'Content-Range': `bytes ${s}-${e}/${st.size}`, 'Content-Length': e - s + 1, 'Access-Control-Allow-Origin': '*', 'Content-Disposition': `attachment; filename="${f.name}"` });
      fs.createReadStream(fp, { start: s, end: e }).pipe(res);
    } else {
      res.writeHead(200, { 'Content-Type': kind === 'music' ? 'audio/*' : 'application/octet-stream', 'Content-Length': st.size, 'Accept-Ranges': 'bytes', 'Access-Control-Allow-Origin': '*', 'Content-Disposition': `attachment; filename="${f.name}"` });
      fs.createReadStream(fp).pipe(res);
    }
  };
  if (m === 'GET' && p.startsWith('/api/files/')) return serveFile('files', p.slice(8));
  if (m === 'GET' && p.startsWith('/api/music/')) return serveFile('music', p.slice(9));

  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ ok: false, msg: 'not found' }));
});

const PORT = process.env.PORT || 8787;
server.listen(PORT, () => console.log('[小染注入后端] http://127.0.0.1:' + PORT + '  admin=' + (process.env.ADMIN_KEY ? '(env)' : '小染nb2026')));
