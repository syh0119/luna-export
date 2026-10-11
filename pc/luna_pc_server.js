// luna_pc_server.js — 汽水音乐 PC 版导出工具（本地服务 + 浏览器 UI）
// 用法: node luna_pc_server.js  → 打开 http://127.0.0.1:18800
//
// 设计：
//   · 设备身份【自助注册】——每台机器首次运行自动注册自己的 did/iid，不共用他人身份
//   · 签名【无状态】——签名只绑请求本身，不依赖登录态
//   · 可选兜底——可从本机汽水客户端同步登录态（用自己账号的权益）
const crypto = require('crypto');
const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const os = require('os');
const zlib = require('zlib');
const { spawn } = require('child_process');

// ---- native 模块解析：SEA 内嵌自释放（无客户端也能跑）→ 回退客户端目录 ----
function resolveNative() {
  const FILES = ['bdms.node', 'device.node', 'metasecml.dll'];
  let sea = null;
  try { sea = require('node:sea'); } catch (e) {}
  if (sea && sea.isSea && sea.isSea()) {
    const dir = path.join(os.tmpdir(), 'luna_export_native');
    try {
      fs.mkdirSync(dir, { recursive: true });
      let need = false;
      for (const f of FILES) {
        const buf = Buffer.from(sea.getRawAsset(f));
        const dst = path.join(dir, f);
        if (!fs.existsSync(dst) || fs.statSync(dst).size !== buf.length) { fs.writeFileSync(dst, buf); need = true; }
      }
      if (need) console.log('native 模块已释放 ->', dir);
      return dir;
    } catch (e) { console.log('native 释放失败，回退客户端目录:', e.message); }
  }
  // 开发/回退：优先环境变量 LUNA_NATIVE，其次程序目录下的 native/
  return process.env.LUNA_NATIVE || path.join(__dirname, 'native');
}
const NATIVE = resolveNative();

const APP_ID = '386088';
const APP_VERSION = '3.8.0';
const CHANNEL = 'official';
const UA = 'LunaPC/3.8.0(467160162)';
const API = 'https://api.qishui.com';
// 下载目录：环境变量 > exe 同目录（便携）> 用户主目录
// 注意：绝不能硬编码盘符——别人的机器可能没有 F 盘
const OUTDIR = (() => {
  let d = process.env.LUNA_OUTDIR || '';
  if (!d) {
    try {
      const exe = process.execPath || '';
      if (exe && !/node\.exe$/i.test(exe)) d = path.join(path.dirname(exe), 'luna_music');
    } catch (e) {}
  }
  if (!d) d = path.join(os.homedir(), 'luna_music');
  try { fs.mkdirSync(d, { recursive: true }); } catch (e) {}
  return d;
})();
const ORDER = ['lossless', 'hi_res', 'spatial', 'highest', 'higher', 'medium'];
// 回退身份：仅当自动注册失败时使用。默认留空（无回退）；可用环境变量注入。
const FALLBACK_DID = process.env.LUNA_DID || '';
const FALLBACK_IID = process.env.LUNA_IID || '';

try { process.chdir(NATIVE); } catch (e) {}
function loadNative(p) {
  const m = { exports: {} };
  process.dlopen(m, p);
  return m.exports;
}
const CORE = globalThis.__LUNA_CORE__ || require('./luna_core.js');
const bdms = loadNative(NATIVE + '/bdms.node');
const DEVN = loadNative(NATIVE + '/device.node');

// ---------------- 设备身份 ----------------
let DEV = { did: '', iid: '', cdid: '', serial: '' };
let COOKIE = '';   // 可选兜底登录态（为空则纯无状态）

function appDataDir() {
  const base = process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming');
  return path.join(base, 'luna_export');
}
function devicePaths() {
  const out = [];
  try {
    const exe = process.execPath || '';
    // 仅独立 exe（SEA）用「exe 同目录」以便便携；开发模式（node.exe）只用 APPDATA
    if (exe && !/node\.exe$/i.test(exe)) out.push(path.join(path.dirname(exe), 'device.json'));
  } catch (e) {}
  out.push(path.join(appDataDir(), 'device.json'));
  return out;
}
function loadDeviceFile() {
  for (const f of devicePaths()) {
    try {
      const d = JSON.parse(fs.readFileSync(f, 'utf8'));
      if (d && d.did && d.iid) return Object.assign(d, { _file: f });
    } catch (e) {}
  }
  return null;
}
function saveDeviceFile(d) {
  for (const f of devicePaths()) {
    try { fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(d, null, 1)); return f; } catch (e) {}
  }
  return null;
}
function postRaw(host, p, headers, body) {
  return new Promise((resolve, reject) => {
    const r = https.request({ hostname: host, path: p, method: 'POST', headers }, res => {
      const c = []; res.on('data', x => c.push(x));
      res.on('end', () => resolve({ status: res.statusCode, text: Buffer.concat(c).toString() }));
    });
    r.on('error', reject);
    if (body) r.write(body);
    r.end();
  });
}
/** 复刻客户端 device.ts 的 registerDevice：gzip(payload) → applogDecorated → POST */
async function registerDevice() {
  const cdid = crypto.randomUUID();
  const serial = String(DEVN.getSerial() || '').replace(/\0+$/, '');
  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai';
  const payload = {
    header: {
      device_id: '', install_id: '',
      os: 'Windows', device_platform: 'PC',
      sdk_version: '1.0', sdk_version_code: 1,
      aid: APP_ID, mc: '00:00:00:00:00:00', channel: CHANNEL,
      package: 'com.soda.music', language: 'zh-CN',
      app_version: APP_VERSION, os_version: os.version(),
      device_model: 'no detected', time_zone: tz, tz_name: tz, tz_offset: 0,
      resolution: 'no detected', app_region: 'cn', app_language: 'zh-CN',
      display_name: '汽水音乐',
      pc_uuid: cdid, pc_serial: serial,
    },
    gen_time: Date.now(),
    magic_tag: 'ss_app_log',
  };
  const gz = zlib.gzipSync(Buffer.from(JSON.stringify(payload)));
  const decorated = DEVN.applogDecorated(gz);
  const qs = Object.entries({ aid: APP_ID, channel: CHANNEL, os: os.type(), os_version: os.version(), device_platform: 'PC', app_version: APP_VERSION })
    .map(([k, v]) => k + '=' + encodeURIComponent(v)).join('&');
  const r = await postRaw('api.qishui.com', '/service/2/desktop/device_register/?' + qs,
    { 'content-type': 'application/octet-stream', 'user-agent': UA }, decorated);
  let j; try { j = JSON.parse(r.text); } catch (e) { throw new Error('注册响应非 JSON: ' + r.text.slice(0, 120)); }
  if (!j.device_id_str) throw new Error('注册失败: ' + r.text.slice(0, 160));
  return { did: String(j.device_id_str), iid: String(j.install_id_str), cdid, serial, ts: Date.now() };
}

function urlTpl() {
  const q = {
    aid: '386088', app_name: 'luna_pc', region: 'cn', geo_region: 'cn', os_region: 'cn', sim_region: '',
    device_id: DEV.did, cdid: '', iid: DEV.iid, version_name: '3.8.0', version_code: '30080000',
    channel: 'official', build_mode: 'master', network_carrier: '', ac: 'wifi',
    tz_name: 'Asia/Shanghai', resolution: '', device_platform: 'windows', device_type: 'Windows',
    os_version: 'Windows 11 Pro for Workstations', fp: DEV.did,
  };
  return API + '/luna/pc/track_v2?' + Object.entries(q).map(([k, v]) => k + '=' + encodeURIComponent(v)).join('&');
}
function baseQ() {
  const u = new URL(urlTpl());
  const o = {};
  for (const k of ['aid', 'app_name', 'region', 'geo_region', 'os_region', 'sim_region', 'device_id',
    'cdid', 'iid', 'version_name', 'version_code', 'channel', 'build_mode', 'ac', 'tz_name',
    'device_platform', 'device_type', 'os_version', 'fp']) o[k] = u.searchParams.get(k) || '';
  return o;
}

// ---------------- 兜底登录态（可选） ----------------
function cookiePath() { return path.join(appDataDir(), 'cookie.txt'); }
function loadCookie() { try { COOKIE = fs.readFileSync(cookiePath(), 'utf8').trim(); } catch (e) { COOKIE = ''; } return COOKIE; }
function saveCookie(c) {
  COOKIE = c || '';
  try { fs.mkdirSync(appDataDir(), { recursive: true }); fs.writeFileSync(cookiePath(), COOKIE); } catch (e) {}
}
function ps(cmd) {
  return new Promise((resolve, reject) => {
    const p = spawn('powershell', ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-Command', cmd], { windowsHide: true });
    let o = '', e = '';
    p.stdout.on('data', d => o += d);
    p.stderr.on('data', d => e += d);
    p.on('exit', c => c === 0 ? resolve(o.trim()) : reject(new Error(e.trim() || ('powershell exit ' + c))));
  });
}
/** 从本机汽水客户端的 Cookies 库提取登录态（DPAPI + AES-GCM），作为兜底 */
async function refreshSession() {
  const appdata = process.env.APPDATA || '';
  const ckPath = appdata + '/SodaMusic/Network/Cookies';
  const lsPath = appdata + '/SodaMusic/Local State';
  if (!fs.existsSync(ckPath)) throw new Error('没找到汽水客户端数据目录（需先安装并登录汽水音乐 PC 客户端）');
  const tmp = path.join(os.tmpdir(), 'luna_ck');
  fs.mkdirSync(tmp, { recursive: true });
  try { fs.copyFileSync(lsPath, path.join(tmp, 'LocalState.json')); } catch (e) { throw new Error('Local State 读取失败'); }
  try { fs.copyFileSync(ckPath, path.join(tmp, 'Cookies')); } catch (e) { throw new Error('Cookies 库被占用 —— 请先完全退出汽水音乐客户端再试'); }

  const ls = JSON.parse(fs.readFileSync(path.join(tmp, 'LocalState.json'), 'utf8'));
  const ek = Buffer.from(ls.os_crypt.encrypted_key || '', 'base64').subarray(5);
  if (!ek.length) throw new Error('Local State 无 encrypted_key');
  const b64 = ek.toString('base64');
  const masterB64 = await ps("Add-Type -AssemblyName System.Security; $b=[Convert]::FromBase64String('" + b64 + "'); [Convert]::ToBase64String([System.Security.Cryptography.ProtectedData]::Unprotect($b,$null,'CurrentUser'))");
  const master = Buffer.from(masterB64, 'base64');

  const { DatabaseSync } = require('node:sqlite');
  const db = new DatabaseSync(path.join(tmp, 'Cookies'));
  const rows = db.prepare('SELECT host_key, name, value, encrypted_value FROM cookies').all();
  db.close();

  function dec(enc) {
    if (!enc || !enc.length) return '';
    const buf = Buffer.from(enc);
    const tag = buf.toString('latin1', 0, 3);
    if (tag === 'v10' || tag === 'v20') {
      const nonce = buf.subarray(3, 15), ct = buf.subarray(15);
      const d = crypto.createDecipheriv('aes-256-gcm', master, nonce);
      d.setAuthTag(ct.subarray(ct.length - 16));
      let out = Buffer.concat([d.update(ct.subarray(0, ct.length - 16)), d.final()]);
      if (tag === 'v20' && out.length > 32) out = out.subarray(32);
      return out.toString('utf8');
    }
    return buf.toString('utf8');
  }
  const ck = {};
  for (const r of rows) if (r.host_key === '.qishui.com') ck[r.name] = r.value || dec(r.encrypted_value);
  if (!ck.sessionid) throw new Error('未找到 sessionid（客户端里还没登录？）');
  saveCookie(Object.entries(ck).map(([k, v]) => k + '=' + v).join('; '));
  log('已同步登录态（兜底）: sessionid=' + String(ck.sessionid).slice(0, 10) + '… 共 ' + Object.keys(ck).length + ' 个 cookie');
  return { ok: true, cookies: Object.keys(ck).length, sessionid: String(ck.sessionid).slice(0, 10) + '…' };
}

// ---------------- 公共请求 ----------------
function signedHeaders(url, extra, body) {
  const h = { 'user-agent': UA, ...extra };
  if (COOKIE) h['cookie'] = COOKIE;          // 兜底登录态（有则带，无则纯无状态）
  if (body) h['x-ss-stub'] = crypto.createHash('md5').update(body).digest('hex').toUpperCase();
  const lines = Object.entries(h).map(([k, v]) => k + '\r\n' + v).join('\r\n');
  const parts = bdms.generateHttpSignatureHeaders(url, lines).split('\r\n').filter(t => t.trim());
  for (let i = 0; i < parts.length / 2; i++) h[parts[i * 2]] = parts[i * 2 + 1];
  return h;
}
function req(url, { method = 'GET', headers = {}, body = null } = {}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request({ hostname: u.hostname, path: u.pathname + u.search, method, headers }, res => {
      let d = [];
      res.on('data', c => d.push(c));
      res.on('end', () => resolve({ status: res.statusCode, text: Buffer.concat(d).toString() }));
    });
    r.on('error', reject);
    if (body) r.write(body);
    r.end();
  });
}
async function rpc(p, body, method) {
  const url = API + p;
  const b = body === undefined ? null : JSON.stringify(body);
  const headers = signedHeaders(url, { 'content-type': 'application/json; charset=utf-8' }, b);
  const r = await req(url, { method: method || 'POST', headers, body: b });
  try { return JSON.parse(r.text); } catch (e) { throw new Error('非JSON: ' + r.text.slice(0, 200)); }
}
const jget = url => req(url).then(r => JSON.parse(r.text));
function getBuf(url, extraHeaders) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    https.get({ hostname: u.hostname, path: u.pathname + u.search, headers: { 'user-agent': UA, ...(extraHeaders || {}) } }, res => {
      if (res.statusCode !== 200) { res.resume(); return reject(new Error('HTTP ' + res.statusCode)); }
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => resolve(Buffer.concat(chunks)));
    }).on('error', reject);
  });
}
async function rpcGet(p, extra) {
  const qs = Object.entries({ ...baseQ(), ...extra }).map(([k, v]) => k + '=' + encodeURIComponent(v)).join('&');
  const url = API + p + '?' + qs;
  const headers = signedHeaders(url, {});
  const r = await req(url, { method: 'GET', headers });
  return JSON.parse(r.text);
}

// ---------------- 下载队列 ----------------
const queue = { items: [], running: false, log: [] };
function log(s) {
  const line = `[${new Date().toTimeString().slice(0, 8)}] ${s}`;
  queue.log.push(line);
  if (queue.log.length > 400) queue.log.shift();
  try { console.log(s); } catch (e) {}
  try { fs.appendFileSync(OUTDIR + '/_log.txt', line + '\n'); } catch (e) {}
}
function uid() { return crypto.randomBytes(6).toString('hex'); }

async function downloadOne(task) {
  const { id, want } = task;
  const body = JSON.stringify({ track_id: id, media_type: 'track', queue_type: 'daily_mix', scene_name: 'track_reco' });
  const url = urlTpl();
  const headers = signedHeaders(url, { 'content-type': 'application/json; charset=utf-8' }, body);
  const j = JSON.parse((await req(url, { method: 'POST', headers, body })).text);
  const tr = j.track, tp = j.track_player;
  if (!tp) throw new Error('track_v2 无 track_player');
  const name = `${tr.name} - ${tr.artists.map(a => a.name).join('/')}`.replace(/[\\/:*?"<>|]/g, '_');
  task.name = name;
  let items;
  if (tp.video_model) {
    const vm = JSON.parse(tp.video_model);
    items = vm.video_list.map(v => ({ quality: v.video_meta.quality, main_url: v.main_url, spade_a: v.encrypt_info ? v.encrypt_info.spade_a : null }));
  } else if (tp.url_player_info) {
    const gi = JSON.parse((await req(tp.url_player_info)).text).Result.Data;
    items = gi.PlayInfoList.map(p => ({ quality: p.Quality, main_url: p.MainPlayUrl, spade_a: p.PlayAuth }));
  } else throw new Error('无可用流');
  const pick = items.filter(x => x.quality === want)[0]
    || items.slice().sort((a, b2) => ORDER.indexOf(a.quality) - ORDER.indexOf(b2.quality))[0];
  if (!pick || !pick.spade_a) throw new Error('无可用档');
  task.quality = pick.quality;
  const key = DEVN.decodeSpade(pick.spade_a);

  // 下载封面 + 加密流，JS 解密
  let coverBuf = null;
  const coverUrl = (tr.album && tr.album.url_cover && tr.album.url_cover.urls && tr.album.url_cover.uri)
    ? (tr.album.url_cover.urls[0] + tr.album.url_cover.uri + '~' + (tr.album.url_cover.template_prefix || 'tplv-b829550vbb') + '-crop-center:800:800.jpg') : '';
  if (coverUrl) { try { coverBuf = await getBuf(coverUrl); } catch (e) { log('封面下载失败(忽略): ' + e.message); } }
  const enc = await getBuf(pick.main_url);
  const r = CORE.decrypt(enc, key, {
    title: tr.name, artist: tr.artists.map(a => a.name).join(' / '),
    album: (tr.album || {}).name || '', lyric: (j.lyric || {}).content || '', coverBuf,
  });
  task.bytes = r.data.length;
  const dst = path.join(OUTDIR, name + r.ext);
  if (fs.existsSync(dst)) fs.unlinkSync(dst);
  fs.writeFileSync(dst, r.data);
  task.file = dst;
  task.size = r.data.length;
}

async function pump() {
  if (queue.running) return;
  queue.running = true;
  while (queue.items.some(t => t.status === 'pending')) {
    const t = queue.items.find(x => x.status === 'pending');
    t.status = 'downloading';
    log(`开始: ${t.id} (期望 ${t.want})`);
    try {
      await downloadOne(t);
      t.status = 'done';
      log(`完成: ${t.name} [${t.quality}] -> ${t.file}`);
    } catch (e) {
      t.status = 'error';
      t.err = e.message;
      log(`失败: ${t.name || t.id} — ${e.message}`);
    }
  }
  queue.running = false;
}

// ---------------- 业务 API ----------------
async function apiSug(q) {
  const j = await rpcGet('/luna/pc/sug', { q, sug_scene: 'main', sug_search_id: uid() });
  return (j.sugs || []).map(s => {
    const t = s.entity && s.entity.track;
    const a = s.entity && s.entity.artist;
    return {
      name: s.suggestion,
      type: s.content_type || '',
      id: t ? String(t.id) : '',
      artistId: a ? String(a.id) : '',
    };
  }).filter(x => x.id || x.artistId);
}
async function apiArtistByName(name) {
  // sug 的 artist 条目 id 不是真实歌手 id；用若干候选曲目反查 artists，选名字匹配的
  const s = await apiSug(name);
  let tracks = s.filter(x => x.type === 'track' && x.id);
  const withName = tracks.filter(x => String(x.name).includes(name));
  tracks = (withName.length ? withName : tracks).slice(0, 6);
  for (const t of tracks) {
    try {
      const body = JSON.stringify({ track_id: String(t.id), media_type: 'track', queue_type: 'daily_mix', scene_name: 'track_reco' });
      const url = urlTpl();
      const headers = signedHeaders(url, { 'content-type': 'application/json; charset=utf-8' }, body);
      const j = JSON.parse((await req(url, { method: 'POST', headers, body })).text);
      for (const a of ((j.track || {}).artists || [])) {
        if (a && a.name && (a.name === name || String(a.name).includes(name) || name.includes(a.name))) {
          return { id: String(a.id), name: a.name };
        }
      }
    } catch (e) {}
  }
  throw new Error('未解析到该歌手');
}
async function apiArtistTracks(artistId, cursor) {
  const j = await rpcGet('/luna/pc/artists/' + artistId + '/tracks', {
    cursor: cursor || '', count: '50', sort_type: '0', locate_item_id: '',
  });
  const d = j.data || j;
  return {
    tracks: (d.tracks || []).map(t => ({
      id: String(t.id), name: t.name,
      artist: (t.artists || []).map(a => a.name).join('/'),
      album: (t.album || {}).name || '',
    })),
    has_more: !!d.has_more, cursor: d.next_cursor || '',
  };
}
async function apiPlaylists() {
  const j = await rpc('/luna/pc/me/playlist', undefined, 'GET');
  const data = j.data || j;
  const pls = data.playlists || data.playlist || [];
  return pls.map(p => ({ id: String(p.id || p.playlist_id), name: p.name, count: p.track_count || p.track_total || '' }));
}
async function apiPlaylistDetail(pid, cursor) {
  const j = await rpc('/luna/pc/playlist/detail', { playlist_id: pid, cursor: cursor || '', count: 100 });
  const data = j.data || j;
  const s = JSON.stringify(data);
  const tracks = [];
  const walk = o => {
    if (Array.isArray(o)) o.forEach(walk);
    else if (o && typeof o === 'object') {
      if (o.id && o.name && (o.artists || o.author) && !o.track_id) {
        tracks.push({ id: String(o.id), name: o.name, artist: Array.isArray(o.artists) ? o.artists.map(a => a.name || a).join('/') : (o.author || '') });
      }
      Object.values(o).forEach(walk);
    }
  };
  walk(data);
  if (!tracks.length) log('playlist detail 原始: ' + s.slice(0, 400));
  return { tracks, has_more: !!data.has_more, cursor: data.next_cursor || '' };
}

// ---------------- HTTP 服务 ----------------
const UI = fs.readFileSync(path.join(__dirname, 'luna_pc_ui.html'), 'utf8');

const server = http.createServer(async (rq, rs) => {
  const u = new URL(rq.url, 'http://x');
  try {
    if (u.pathname === '/') {
      rs.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      return rs.end(UI);
    }
    if (u.pathname === '/api/device') {
      return rs.end(JSON.stringify({
        did: DEV.did, iid: DEV.iid, cdid: DEV.cdid || '', serial: DEV.serial || '',
        file: DEV._file || '', cookie: COOKIE ? COOKIE.length : 0,
      }));
    }
    if (u.pathname === '/api/selfcheck') {
      // 环境自检：设备身份 + 实际取流能力（用于在新机器上快速验证）
      const out = {
        device: {
          did: DEV.did, iid: DEV.iid, serial: DEV.serial || '', cdid: DEV.cdid || '',
          file: DEV._file || '(未落盘)', cookie: COOKIE.length,
        },
        node: process.version, platform: process.platform, arch: process.arch,
      };
      try {
        const body = JSON.stringify({ track_id: '7655367581428549633', media_type: 'track', queue_type: 'daily_mix', scene_name: 'track_reco' });
        const url = urlTpl();
        const headers = signedHeaders(url, { 'content-type': 'application/json; charset=utf-8' }, body);
        const j = JSON.parse((await req(url, { method: 'POST', headers, body })).text);
        const tp = j.track_player || {};
        let q = [];
        if (tp.video_model) q = JSON.parse(tp.video_model).video_list.map(v => v.video_meta.quality);
        else if (tp.quality) q = [tp.quality];
        out.playback = { ok: !!tp, qualities: q, lossless: q.indexOf('lossless') >= 0, track: (j.track || {}).name || '' };
      } catch (e) { out.playback = { ok: false, error: e.message }; }
      // 落盘一份人可读版本，避免截图/弹窗截断（放在下载目录）
      out.text = [
        '【设备身份】',
        '  did: ' + out.device.did,
        '  iid: ' + out.device.iid,
        '  serial: ' + out.device.serial,
        '  存档: ' + out.device.file,
        '  兜底登录态: ' + (out.device.cookie ? (out.device.cookie + ' 字符') : '未使用（纯无状态）'),
        '',
        '【运行环境】',
        '  ' + out.platform + ' / ' + out.arch + ' / node ' + out.node,
        '  下载目录: ' + OUTDIR,
        '',
        '【取流能力】',
        out.playback.ok
          ? ('  曲目: ' + out.playback.track +
             '\n  可用档位: ' + (out.playback.qualities || []).join(', ') +
             '\n  无损: ' + (out.playback.lossless ? '可用' : '不可用'))
          : ('  失败: ' + out.playback.error),
      ].join('\r\n');
      try { fs.writeFileSync(path.join(OUTDIR, '_selfcheck.txt'), out.text); } catch (e) {}
      return rs.end(JSON.stringify(out, null, 2));
    }
    if (u.pathname === '/api/device_reset') {
      try {
        const d = await registerDevice();
        const f = saveDeviceFile(d);
        DEV = Object.assign({}, d, { _file: f || '' });
        bdms.init({ deviceId: DEV.did });
        log('已重新注册设备: did=' + d.did + (f ? (' → ' + f) : ''));
        return rs.end(JSON.stringify({ ok: true, did: d.did, iid: d.iid, file: f || '' }));
      } catch (e) { return rs.end(JSON.stringify({ error: e.message })); }
    }
    if (u.pathname === '/api/refresh_session') {
      try { return rs.end(JSON.stringify(await refreshSession())); }
      catch (e) { return rs.end(JSON.stringify({ error: e.message })); }
    }
    if (u.pathname === '/api/clear_session') {
      saveCookie('');
      log('已清除兜底登录态（回到纯无状态模式）');
      return rs.end(JSON.stringify({ ok: true }));
    }
    if (u.pathname === '/api/session') {
      return rs.end(JSON.stringify({ len: COOKIE.length, file: cookiePath(), head: COOKIE.slice(0, 40) }));
    }
    if (u.pathname === '/api/sug') {
      return rs.end(JSON.stringify(await apiSug(u.searchParams.get('q') || '')));
    }
    if (u.pathname === '/api/artist_by_name') {
      try {
        return rs.end(JSON.stringify(await apiArtistByName(u.searchParams.get('name') || '')));
      } catch (e) { return rs.end(JSON.stringify({ error: e.message })); }
    }
    if (u.pathname === '/api/artist_tracks') {
      try {
        return rs.end(JSON.stringify(await apiArtistTracks(u.searchParams.get('artist_id') || '', u.searchParams.get('cursor') || '')));
      } catch (e) { return rs.end(JSON.stringify({ error: e.message })); }
    }
    if (u.pathname === '/api/quit') {
      rs.end('ok');
      log('收到退出指令，服务已关闭');
      setTimeout(() => process.exit(0), 400);
      return;
    }
    if (u.pathname === '/api/proxy') {
      const on = u.searchParams.get('on') === '1';
      const K = 'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings';
      const r1 = spawn('reg', ['add', K, '/v', 'ProxyEnable', '/t', 'REG_DWORD', '/d', on ? '1' : '0', '/f'], { windowsHide: true });
      r1.on('exit', () => {
        if (on) spawn('reg', ['add', K, '/v', 'ProxyServer', '/t', 'REG_SZ', '/d', '127.0.0.1:8080', '/f'], { windowsHide: true });
        log('系统代理 ' + (on ? '已开启（探测模式）' : '已关闭'));
      });
      return rs.end('ok');
    }
    if (u.pathname === '/api/tracks') {
      const f = path.join(OUTDIR, 'tracks.jsonl');
      const rows = [];
      const seen = new Set();
      if (fs.existsSync(f)) {
        for (const l of fs.readFileSync(f, 'utf8').split('\n').reverse()) {
          try {
            const r = JSON.parse(l);
            if (!seen.has(r.id)) { seen.add(r.id); rows.push(r); }
          } catch (e) {}
        }
      }
      return rs.end(JSON.stringify(rows.slice(0, 200)));
    }
    if (u.pathname === '/api/playlists') {
      return rs.end(JSON.stringify(await apiPlaylists()));
    }
    if (u.pathname === '/api/playlist') {
      return rs.end(JSON.stringify(await apiPlaylistDetail(u.searchParams.get('id'), u.searchParams.get('cursor'))));
    }
    if (u.pathname === '/api/download' && rq.method === 'POST') {
      let b = '';
      rq.on('data', c => b += c);
      rq.on('end', () => {
        const { ids, quality } = JSON.parse(b);
        for (const id of ids) {
          if (queue.items.some(t => t.id === id && t.status !== 'error')) continue;
          queue.items.push({ id, want: quality || 'lossless', status: 'pending', name: '', quality: '', file: '' });
        }
        pump();
        rs.end('ok');
      });
      return;
    }
    if (u.pathname === '/api/progress') {
      return rs.end(JSON.stringify({ items: queue.items, log: queue.log.slice(-80), running: queue.running }));
    }
    if (u.pathname === '/api/open_dir') {
      spawn('cmd', ['/c', 'start', '', OUTDIR]);
      return rs.end('ok');
    }
    rs.writeHead(404); rs.end('nf');
  } catch (e) {
    rs.writeHead(500, { 'content-type': 'application/json' });
    rs.end(JSON.stringify({ error: e.message }));
  }
});
function launchWindow() {
  if (process.env.LUNA_NO_WINDOW === '1') { log('（已跳过窗口启动）'); return; }   // 供无头测试用
  const url = 'http://127.0.0.1:18800/';
  const EDGE = [
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
    ((process.env.LOCALAPPDATA || '') + '/Microsoft/Edge/Application/msedge.exe'),
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
  ].find(p => p && fs.existsSync(p));
  if (EDGE) {
    spawn(EDGE, ['--app=' + url, '--window-size=1140,920', '--window-position=80,30',
      '--user-data-dir=' + path.join(OUTDIR, '_app_profile'),
      '--no-first-run', '--no-default-browser-check'], { detached: true, stdio: 'ignore' }).unref();
    log('已启动应用窗口 (' + path.basename(EDGE) + ' --app)');
  } else {
    spawn('cmd', ['/c', 'start', '', url], { detached: true, stdio: 'ignore' }).unref();
  }
}
// 端口被占 = 上一次的实例还在跑 → 直接把窗口打开，不再起第二个服务
server.on('error', (err) => {
  if (err && err.code === 'EADDRINUSE') {
    log('检测到已有实例在运行，直接打开窗口');
    launchWindow();
    setTimeout(() => process.exit(0), 1500);
  } else {
    log('服务启动失败: ' + (err && err.message));
    setTimeout(() => process.exit(1), 300);
  }
});

// ---------------- 启动 ----------------
(async () => {
  // 1) 设备身份：本地文件 → 自动注册 → 内置回退
  const cached = loadDeviceFile();
  if (cached) {
    DEV = cached;
    log('设备身份（本地）: did=' + DEV.did);
  } else {
    log('首次运行：正在注册设备身份…');
    try {
      const d = await registerDevice();
      const f = saveDeviceFile(d);
      DEV = Object.assign({}, d, { _file: f || '' });
      log('设备已注册: did=' + d.did + ' iid=' + d.iid + (f ? (' → ' + f) : '（未落盘）'));
    } catch (e) {
      if (FALLBACK_DID) {
        DEV = { did: FALLBACK_DID, iid: FALLBACK_IID };
        log('设备注册失败，暂用回退身份: ' + e.message);
      } else {
        log('设备注册失败（无回退身份）：' + e.message + ' —— 请检查网络后重启程序');
      }
    }
  }
  bdms.init({ deviceId: DEV.did });

  // 2) 可选兜底登录态
  loadCookie();
  if (COOKIE) log('已加载兜底登录态（' + COOKIE.length + ' 字符）');

  server.listen(18800, '127.0.0.1', () => {
    log('汽水导出已启动  下载目录 ' + OUTDIR);
    launchWindow();
  });
})();
