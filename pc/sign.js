/**
 * 汽水（luna）播放流签名模块 —— 独立提取版
 * ============================================================
 * 用途：为汽水 PC 接口（track_v2 等）生成 x-helios / x-medusa 签名头。
 * 依赖：同目录的 bdms.node + metasecml.dll（字节官方 N-API 原生模块）。
 *      两个文件是本模块的全部，拷走即可用，不需要 node_modules。
 *
 * 语义：签名只吃「请求本身」——URL（含 query）+ 参与签名的请求头。
 *      登录 Cookie 不需要参与签名；它作为普通请求头随请求发送即可。
 *
 * 环境要求：Node.js（本机 22.x，需支持 process.dlopen）
 *
 * —— 用法（作为库）——
 *   const S = require('./sign');
 *   S.init('');                       // 设备注册返回的 did
 *   const h = S.sign(url, { 'content-type': 'application/json; charset=utf-8' }, body);
 *   // h → { 'x-helios': '...', 'x-medusa': '...', 'x-ss-stub': '...' }（有 body 时才有 stub）
 *   // 把 h 合并进你的请求头即可。
 *
 * —— 用法（命令行）——
 *   node sign.js --did 2187... --url "https://api.qishui.com/luna/pc/track_v2?..." \
 *                --header "content-type:application/json" --body '{"track_id":"..."}'
 *   cat req.json | node sign.js --stdin          # {"did":..,"url":..,"headers":{..},"body":..}
 */
'use strict';

const path = require('path');
const fs = require('fs');
const crypto = require('crypto');

// bdms.node 通过相对名加载 metasecml.dll，必须在文件所在目录下 dlopen
process.chdir(__dirname);

/** SEA / 普通 Node 通用的原生模块加载（不依赖 require 的 .node 解析） */
function loadNative(p) {
  const m = { exports: {} };
  process.dlopen(m, p);
  return m.exports;
}

const BDMS = loadNative(path.join(__dirname, 'bdms.node'));

let _inited = false;

/** 读取设备 did：参数 > 环境变量 LUNA_DID > 内置默认 */
function resolveDid(did) {
  const v = did || process.env.LUNA_DID || DEFAULT_DID;
  if (!v) throw new Error('缺少 deviceId（设备注册返回的 did）：请传参或设 LUNA_DID 环境变量');
  return String(v);
}
// 内置默认 did（取自本机汽水客户端设备注册结果，换机器请覆盖）
const DEFAULT_DID = '';

/** 初始化签名器。（签名算法与登录态无关，只需设备 did） */
function init(did) {
  BDMS.init({ deviceId: resolveDid(did) });
  _inited = true;
  return true;
}

function ensureInited() {
  if (!_inited) init();
}

/**
 * 计算请求体摘要头（JS 层算法，非原生模块产物；body 为空则不返回）
 * 汽水接口的 x-ss-stub = body 的 MD5 大写
 */
function bodyStub(body) {
  if (body === undefined || body === null || body === '') return {};
  return { 'x-ss-stub': crypto.createHash('md5').update(body).digest('hex').toUpperCase() };
}

/**
 * 生成签名头。
 * @param {string} url     完整请求 URL（必须带 query，签名与 query 相关）
 * @param {object} headers 参与签名的请求头（键值对）。cookie 可传可不传。
 * @param {string} [body]  请求体；提供时会附带 x-ss-stub
 * @returns {object} 需要合并进请求的签名头
 */
function sign(url, headers, body) {
  if (!url) throw new Error('需要 url');
  ensureInited();
  // 注意顺序：x-ss-stub 必须在签名前并入，签名覆盖的是「最终实发的头集合」
  const h = Object.assign({}, headers || {});
  const stub = bodyStub(body);
  Object.assign(h, stub);
  const lines = Object.entries(h)
    .map(([k, v]) => k + '\r\n' + v)
    .join('\r\n');
  const raw = BDMS.generateHttpSignatureHeaders(url, lines);
  const parts = String(raw).split('\r\n').filter(t => t.trim());
  const out = {};
  for (let i = 0; i < parts.length / 2; i++) out[parts[i * 2]] = parts[i * 2 + 1];
  return Object.assign(out, stub);
}

/** 便捷封装：输入一个请求描述，返回补好签名头的完整 headers */
function signRequest(req) {
  const { url, headers = {}, body } = req || {};
  return Object.assign({}, headers, sign(url, headers, body));
}

module.exports = { init, sign, signRequest, bodyStub, loadNative, DEFAULT_DID };
module.exports.default = module.exports;

// ---------------------------------------------------------------- CLI
if (require.main === module) {
  const args = process.argv.slice(2);
  const get = k => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : null; };
  const all = k => args.reduce((a, v, i) => (v === k && args[i + 1] ? a.concat(args[i + 1]) : a), []);

  let input = null;
  if (args.includes('--stdin')) {
    input = JSON.parse(fs.readFileSync(0, 'utf8'));
  } else if (args.includes('--file')) {
    input = JSON.parse(fs.readFileSync(get('--file'), 'utf8'));
  }

  if (input) {
    init(input.did);
    console.log(JSON.stringify(signRequest(input), null, 2));
  } else {
    const url = get('--url');
    if (!url) {
      console.error('用法: node sign.js --did <did> --url <url> [--header k:v ...] [--body <json>]');
      console.error('  或: echo \'{"url":"..","headers":{},"body":".."}\' | node sign.js --stdin');
      process.exit(1);
    }
    const headers = {};
    for (const h of all('--header')) {
      const i = h.indexOf(':');
      if (i > 0) headers[h.slice(0, i).trim()] = h.slice(i + 1).trim();
    }
    init(get('--did'));
    console.log(JSON.stringify(signRequest({ url, headers, body: get('--body') }), null, 2));
  }
}
