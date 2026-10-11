#!/usr/bin/env node
/**
 * fetch.js —— 汽水整曲导出（纯 JS 自包含，支持批量）
 * ============================================================
 * 链路：签名(sign.js) → track_v2 → 选档 → spade 解码(device.node) → 下载加密流
 *       → CENC 解密 + 歌词/封面内嵌(luna_core.js) → 成品 .flac / .m4a
 *
 * 全程**不需要登录态（Cookie）**：签名只绑请求本身，播放权益绑设备(device_id/iid)。
 * 也不需要 Python：解密已移植为 JS。
 *
 * 用法:
 *   node fetch.js <track_id> [out_dir] [quality] [cookie_file]          单曲
 *   node fetch.js --ids "id1,id2,..." [out_dir] [quality] [cookie_file] 内联多曲
 *   node fetch.js --batch ids.txt [out_dir] [quality] [cookie_file]     列表文件
 *
 *   quality: lossless | hi_res | spatial | highest | higher | medium
 *            （缺省 lossless，取不到则按优先级回退）
 *   cookie_file: 可选。需要按账号维度取流时才给（默认不带）
 *
 * 列表文件格式：每行一个 track_id；也接受分享文案（自动提取 15~20 位数字）；
 *              `#` 开头为注释。
 *
 * 例:
 *   node fetch.js 7655367581428549633 ./out
 *   node fetch.js --batch ids.txt ./out lossless
 *   node fetch.js --ids "7655367581428549633,6917878175166908418" ./out highest
 */
'use strict';
const fs = require('fs');
const path = require('path');
const https = require('https');
const S = require('./sign.js');
const CORE = require('./luna_core.js');

process.chdir(__dirname);                       // device.node 需要在本目录加载
function loadNative(p) { const m = { exports: {} }; process.dlopen(m, p); return m.exports; }
const DEVICE = loadNative(path.join(__dirname, 'device.node'));

const UA = 'LunaPC/3.8.0(467160162)';
const DEFAULT_DID = '';
const DEFAULT_IID = '';
const ORDER = ['lossless', 'hi_res', 'spatial', 'highest', 'higher', 'medium'];

/** 读取设备身份：优先图形版注册的 device.json */
function loadDeviceFile() {
  const os = require('os');
  const cands = [];
  try {
    const exe = process.execPath || '';
    if (exe && !/node\.exe$/i.test(exe)) cands.push(path.join(path.dirname(exe), 'device.json'));
  } catch (e) {}
  cands.push(path.join(process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming'), 'luna_export', 'device.json'));
  for (const f of cands) {
    try { const d = JSON.parse(fs.readFileSync(f, 'utf8')); if (d && d.did) return d; } catch (e) {}
  }
  return null;
}

/** 组装 track_v2 的完整 URL（device_id/iid 是"身份"，其余为固定参数） */
function buildUrl(did, iid) {
  const q = {
    aid: '386088', app_name: 'luna_pc', region: 'cn', geo_region: 'cn', os_region: 'cn', sim_region: '',
    device_id: did, cdid: '', iid, version_name: '3.8.0', version_code: '30080000',
    channel: 'official', build_mode: 'master', network_carrier: '', ac: 'wifi',
    tz_name: 'Asia/Shanghai', resolution: '', device_platform: 'windows', device_type: 'Windows',
    os_version: 'Windows 11 Pro for Workstations', fp: did,
  };
  return 'https://api.qishui.com/luna/pc/track_v2?' +
    Object.entries(q).map(([k, v]) => k + '=' + encodeURIComponent(v)).join('&');
}

function req(url, { method = 'GET', headers = {}, body = null } = {}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const r = https.request({ hostname: u.hostname, path: u.pathname + u.search, method, headers }, res => {
      const chunks = []; res.on('data', c => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, buf: Buffer.concat(chunks) }));
    });
    r.on('error', reject);
    if (body) r.write(body);
    r.end();
  });
}

/** 从文本里提取 track_id（每行一个；支持分享文案，自动抓 15~20 位数字） */
function extractIds(text) {
  const ids = [];
  for (const line of String(text).split(/\r?\n/)) {
    const t = line.trim();
    if (!t || t.startsWith('#')) continue;
    const m = t.match(/\d{15,20}/g);
    if (m) ids.push(...m);
  }
  return [...new Set(ids)];
}

/** 导出单曲 */
async function fetchOne(trackId, { outDir, want, cookieFile, urlTpl }) {
  const body = JSON.stringify({ track_id: String(trackId), media_type: 'track', queue_type: 'daily_mix', scene_name: 'track_reco' });
  const signInput = { 'content-type': 'application/json; charset=utf-8', 'user-agent': UA };
  if (cookieFile) signInput.cookie = fs.readFileSync(cookieFile, 'utf8').trim();   // 可选
  const headers = S.signRequest({ url: urlTpl, headers: signInput, body });

  const r1 = await req(urlTpl, { method: 'POST', headers, body });
  let j; try { j = JSON.parse(r1.buf.toString()); } catch (e) { throw new Error('响应非 JSON: ' + r1.buf.toString().slice(0, 160)); }
  const tr = j.track || {}, tp = j.track_player || {};
  if (!tp || (!tp.video_model && !tp.url_player_info)) throw new Error('无 track_player: ' + r1.buf.toString().slice(0, 200));

  // 候选档：SVIP 登录会话走 video_model；否则回落 GetPlayInfo
  let list = [];
  if (tp.video_model) {
    const vm = JSON.parse(tp.video_model);
    list = vm.video_list.map(v => ({ quality: v.video_meta.quality, url: v.main_url, spade: v.encrypt_info.spade_a }));
  } else {
    const gi = JSON.parse((await req(tp.url_player_info)).buf.toString());
    const g = (gi.Result || {}).Data || {};
    list = (g.PlayInfoList || []).map(v => ({ quality: v.Quality, url: v.MainPlayUrl, spade: v.PlayAuth }));
  }
  const pick = list.find(x => x.quality === want) || ORDER.map(q => list.find(x => x.quality === q)).find(Boolean);
  if (!pick) throw new Error('无可用档位');

  const name = `${tr.name} - ${(tr.artists || []).map(a => a.name).join('/')}`.replace(/[\\/:*?"<>|]/g, '_');
  const key = DEVICE.decodeSpade(pick.spade);
  const enc = (await req(pick.url, { headers: { 'user-agent': UA } })).buf;

  // 封面（可选，失败忽略）
  let coverBuf = null;
  const rc = (tr.album || {}).url_cover;
  if (rc && rc.urls && rc.uri) {
    const cu = rc.urls[0] + rc.uri + '~' + (rc.template_prefix || 'tplv-b829550vbb') + '-crop-center:800:800.jpg';
    try { coverBuf = (await req(cu, { headers: { 'user-agent': UA } })).buf; } catch (e) {}
  }

  const out = CORE.decrypt(enc, key, {
    title: tr.name,
    artist: (tr.artists || []).map(a => a.name).join(' / '),
    album: (tr.album || {}).name || '',
    lyric: (j.lyric || {}).content || '',
    coverBuf,
  });
  const dst = path.join(outDir, name + out.ext);
  fs.writeFileSync(dst, out.data);
  return { name, quality: pick.quality, avail: list.map(x => x.quality), size: out.data.length, dst };
}

function usage() {
  console.error('用法:');
  console.error('  node fetch.js <track_id> [out_dir] [quality] [cookie_file]');
  console.error('  node fetch.js --ids "id1,id2,..." [out_dir] [quality] [cookie_file]');
  console.error('  node fetch.js --batch ids.txt [out_dir] [quality] [cookie_file]');
}

(async () => {
  const argv = process.argv.slice(2);
  if (!argv.length) { usage(); process.exit(1); }

  let ids, rest;
  if (argv[0] === '--ids') { ids = extractIds(argv[1] || ''); rest = argv.slice(2); }
  else if (argv[0] === '--batch' || argv[0] === '-f') {
    const f = argv[1];
    if (!f || !fs.existsSync(f)) { console.error('找不到列表文件: ' + f); process.exit(1); }
    ids = extractIds(fs.readFileSync(f, 'utf8')); rest = argv.slice(2);
  } else { ids = [String(argv[0])]; rest = argv.slice(1); }

  if (!ids.length) { console.error('没有解析到 track_id'); process.exit(1); }
  const outDir = rest[0] || path.join(__dirname, 'out');
  const want = rest[1] || 'lossless';
  const cookieFile = rest[2];
  fs.mkdirSync(outDir, { recursive: true });

  // 设备身份：环境变量 > device.json（图形版注册的）> 内置默认（空）
  const dev = loadDeviceFile();
  const did = process.env.LUNA_DID || (dev && dev.did) || DEFAULT_DID;
  const iid = process.env.LUNA_IID || (dev && dev.iid) || DEFAULT_IID;
  if (!did || !iid) {
    console.error('缺少设备身份：请先运行一次图形版（会自动注册），或设置 LUNA_DID / LUNA_IID 环境变量');
    process.exit(1);
  }
  const urlTpl = buildUrl(did, iid);
  S.init(did);

  const multi = ids.length > 1;
  console.log(`${ids.length} 首 | 档位 ${want} | 输出 ${outDir} | ${cookieFile ? '带登录态' : '无登录态'}`);
  const t0 = Date.now();
  let ok = 0, fail = 0;

  for (let i = 0; i < ids.length; i++) {
    const tag = multi ? `[${i + 1}/${ids.length}] ` : '';
    try {
      const r = await fetchOne(ids[i], { outDir, want, cookieFile, urlTpl });
      console.log(`${tag}✓ ${r.name}  ${r.quality}  ${r.size.toLocaleString()} B`);
      if (!multi) console.log(`     可用档位: ${r.avail.join(', ')}\n     → ${r.dst}`);
      ok++;
    } catch (e) {
      console.log(`${tag}✗ ${ids[i]}  ${e.message}`);
      fail++;
    }
    if (i < ids.length - 1) await new Promise(r => setTimeout(r, 800));   // 轻间隔，避免触发风控
  }
  console.log(`\n完成：成功 ${ok} / 失败 ${fail}，耗时 ${((Date.now() - t0) / 1000).toFixed(1)}s`);
})().catch(e => { console.error('失败:', e.message); process.exit(1); });
