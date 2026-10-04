// 小染自动注入 —— 后端（Node，无第三方依赖，node:http 直接跑）
// 职责：卡密系统（生成/封禁/绑定设备/每卡仅解绑一次）、文件与音乐上传下载(带 Range 进度)、
//       公告、更新(下载链接+是否强制)、"服务停用"(一键跑路)。非 AI 风格。
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = __dirname;
const DATA = path.join(ROOT, 'data');
const FILES = path.join(DATA, 'files');
const MUSIC = path.join(DATA, 'music');
const STATE = path.join(DATA, 'state.json');
const ADMIN = process.env.ADMIN_KEY || 'xiaoran-admin';

for (const d of [DATA, FILES, MUSIC]) fs.mkdirSync(d, { recursive: true });

// ---- 状态（持久化到 state.json）----
let state = {
  cards: {},          // key -> {label, createdAt, banned, boundDevice, unbindCount, expiresAt}
  announcement: '',   // 公告
  serviceDisabled: false, // 一键跑路：true 时前端显示"无法使用"
  update: { enabled: false, minVersion: '0.0.0', url: '', force: false },
  importDir: '',      // zip 解压目标目录（由后端配置，不写进游戏目录）
  videoBg: '',       // 视频背景的 URL/路径（空则前端用本地默认）
};
function load() { try { state = Object.assign({ cards: {} }, JSON.parse(fs.readFileSync(STATE, 'utf8'))); } catch (e) {} }
function save() { fs.writeFileSync(STATE, JSON.stringify(state, null, 2)); }
load();

// ---- 小工具 ----
function json(res, code, obj) {
  const b = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
  res.end(b);
}
function body(req) {
  return new Promise((resolve) => {
    let s = '';
    req.on('data', (c) => { s += c; if (s.length > 64 * 1024 * 1024) req.destroy(); });
    req.on('end', () => { try { resolve(JSON.parse(s || '{}')); } catch (e) { resolve({}); } });
  });
}
function isAuth(req) { return req.headers['x-admin'] === ADMIN; }
function newKey() {
  const seg = () => crypto.randomBytes(2).toString('hex').toUpperCase();
  return `XIAO-${seg()}-${seg()}-${seg()}`;
}

// ---- 卡密 ----
function cardVerify(key, deviceId) {
  const c = state.cards[key];
  if (!c) return { ok: false, msg: '卡密不存在' };
  if (c.banned) return { ok: false, msg: '该卡密已被封禁' };
  if (c.expiresAt && Date.now() > c.expiresAt) return { ok: false, msg: '卡密已过期' };
  if (c.boundDevice && c.boundDevice !== deviceId) return { ok: false, msg: '该卡密已被其他设备绑定' };
  c.boundDevice = deviceId || c.boundDevice;
  save();
  return { ok: true, msg: '验证通过' };
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x');
  const p = u.pathname;
  const m = req.method;

  // CORS 预检
  if (m === 'OPTIONS') {
    res.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS', 'Access-Control-Allow-Headers': '*' });
    return res.end();
  }

  // ---- 卡密（客户端）----
  if (m === 'POST' && p === '/api/card/verify') {
    const b = await body(req);
    return json(res, 200, cardVerify(String(b.key || ''), String(b.deviceId || '')));
  }
  if (m === 'POST' && p === '/api/card/unbind') {   // 每个卡密仅可解绑一次
    const b = await body(req);
    const c = state.cards[String(b.key || '')];
    if (!c) return json(res, 200, { ok: false, msg: '卡密不存在' });
    if (c.unbindCount >= 1) return json(res, 200, { ok: false, msg: '每个卡密仅可解绑一次' });
    c.boundDevice = null; c.unbindCount = 1; save();
    return json(res, 200, { ok: true, msg: '已解绑（该卡密不可再次解绑）' });
  }

  // ---- 状态（客户端拉取）----
  if (m === 'GET' && p === '/api/status') {
    return json(res, 200, {
      serviceDisabled: state.serviceDisabled,
      announcement: state.announcement,
      update: state.update,
    });
  }

  // ---- 管理（生成/封禁/公告/更新/停用）----
  if (m === 'POST' && p === '/api/admin/generateCard') {
    if (!isAuth(req)) return json(res, 403, { ok: false, msg: '未授权' });
    const b = await body(req);
    const key = newKey();
    state.cards[key] = { label: String(b.label || ''), createdAt: Date.now(), banned: false, boundDevice: null, unbindCount: 0, expiresAt: b.expiresInDays ? Date.now() + b.expiresInDays * 86400000 : 0 };
    save();
    return json(res, 200, { ok: true, key, card: state.cards[key] });
  }
  if (m === 'POST' && p === '/api/admin/banCard') {
    if (!isAuth(req)) return json(res, 403, { ok: false, msg: '未授权' });
    const b = await body(req); const c = state.cards[String(b.key || '')];
    if (!c) return json(res, 200, { ok: false, msg: '卡密不存在' });
    c.banned = !c.banned; save();
    return json(res, 200, { ok: true, banned: c.banned });
  }
  if (m === 'GET' && p === '/api/admin/cards') {
    if (!isAuth(req)) return json(res, 403, { ok: false, msg: '未授权' });
    return json(res, 200, { ok: true, cards: state.cards });
  }
  if (m === 'POST' && p === '/api/admin/announce') {
    if (!isAuth(req)) return json(res, 403, { ok: false, msg: '未授权' });
    const b = await body(req); state.announcement = String(b.text || ''); save();
    return json(res, 200, { ok: true });
  }
  if (m === 'POST' && p === '/api/admin/update') {
    if (!isAuth(req)) return json(res, 403, { ok: false, msg: '未授权' });
    const b = await body(req);
    state.update = { enabled: !!b.enabled, minVersion: String(b.minVersion || '0.0.0'), url: String(b.url || ''), force: !!b.force };
    save();
    return json(res, 200, { ok: true, update: state.update });
  }
  if (m === 'POST' && p === '/api/admin/disable') {
    if (!isAuth(req)) return json(res, 403, { ok: false, msg: '未授权' });
    const b = await body(req); state.serviceDisabled = !!b.on; save();
    return json(res, 200, { ok: true, serviceDisabled: state.serviceDisabled });
  }

  // ---- 文件 / 音乐（上传 + 列表 + 下载，下载支持 Range 进度条）----
  const manifest = (kind) => path.join(DATA, kind === 'music' ? 'music.json' : 'files.json');
  const readList = (kind) => { try { return JSON.parse(fs.readFileSync(manifest(kind), 'utf8')); } catch (e) { return []; } };
  const writeList = (kind, arr) => fs.writeFileSync(manifest(kind), JSON.stringify(arr, null, 2));

  if (m === 'POST' && (p === '/api/file/upload' || p === '/api/music/upload')) {
    const kind = p.endsWith('/music/upload') ? 'music' : 'files';
    const b = await body(req);
    if (!b.contentBase64) return json(res, 400, { ok: false, msg: '缺少 contentBase64' });
    const name = String(b.name || 'file').replace(/[\/\\:*?"<>|]/g, '_');
    const id = crypto.randomBytes(6).toString('hex');
    const fp = path.join(kind === 'music' ? MUSIC : FILES, id);
    fs.writeFileSync(fp, Buffer.from(b.contentBase64, 'base64'));
    const list = readList(kind);
    list.push({ id, name, size: fs.statSync(fp).size, url: kind === 'music' ? '/api/music/' + id : '/api/files/' + id, at: Date.now() });
    writeList(kind, list);
    return json(res, 200, { ok: true, id, name, url: kind === 'music' ? '/api/music/' + id : '/api/files/' + id });
  }
  if (m === 'GET' && p === '/api/files') return json(res, 200, { ok: true, files: readList('files') });
  if (m === 'GET' && p === '/api/music') return json(res, 200, { ok: true, files: readList('music') });

  const serve = (kind, id) => {
    const list = readList(kind); const f = list.find((x) => x.id === id);
    if (!f) { res.writeHead(404); return res.end('not found'); }
    const fp = path.join(kind === 'music' ? MUSIC : FILES, id);
    const st = fs.statSync(fp);
    const startHeader = req.headers['range'];
    if (startHeader) {
      const mrg = /bytes=(\d*)-(\d*)/.exec(startHeader);
      let start = mrg && mrg[1] ? parseInt(mrg[1], 10) : 0;
      let end = mrg && mrg[2] ? parseInt(mrg[2], 10) : st.size - 1;
      if (end > st.size - 1) end = st.size - 1;
      res.writeHead(206, {
        'Content-Type': kind === 'music' ? 'audio/*' : 'application/octet-stream',
        'Content-Range': `bytes ${start}-${end}/${st.size}`,
        'Content-Length': end - start + 1,
        'Access-Control-Allow-Origin': '*',
        'Content-Disposition': `attachment; filename="${f.name}"`,
      });
      fs.createReadStream(fp, { start, end }).pipe(res);
    } else {
      res.writeHead(200, {
        'Content-Type': kind === 'music' ? 'audio/*' : 'application/octet-stream',
        'Content-Length': st.size,
        'Accept-Ranges': 'bytes',
        'Access-Control-Allow-Origin': '*',
        'Content-Disposition': `attachment; filename="${f.name}"`,
      });
      fs.createReadStream(fp).pipe(res);
    }
  };
  if (m === 'GET' && p.startsWith('/api/files/')) { return serve('files', p.slice('/api/files/'.length)); }
  if (m === 'GET' && p.startsWith('/api/music/')) { return serve('music', p.slice('/api/music/'.length)); }

  if (m === 'GET' && p === '/api/config') {
    return json(res, 200, { ok: true, importDir: state.importDir || '', videoBg: state.videoBg || '' });
  }
  if (m === 'POST' && p === '/api/admin/setImportDir') {
    if (!isAuth(req)) return json(res, 403, { ok: false, msg: '未授权' });
    const b = await body(req); state.importDir = String(b.path || ''); save();
    return json(res, 200, { ok: true, importDir: state.importDir });
  }
  if (m === 'POST' && p === '/api/admin/setVideoBg') {
    if (!isAuth(req)) return json(res, 403, { ok: false, msg: '未授权' });
    const b = await body(req); state.videoBg = String(b.url || ''); save();
    return json(res, 200, { ok: true, videoBg: state.videoBg });
  }

  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ ok: false, msg: 'not found' }));
});

const PORT = process.env.PORT || 8787;
server.listen(PORT, () => console.log('[xiaoran] server on http://127.0.0.1:' + PORT + '  admin key: ' + (process.env.ADMIN_KEY ? '(env)' : ADMIN)));
