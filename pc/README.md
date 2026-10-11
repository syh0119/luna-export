# 汽水导出（电脑端）

从汽水音乐 PC 端的接口直接取流并解密，得到带完整标签（歌词 + 封面）的 FLAC / M4A。

**不需要装汽水客户端、不需要登录账号、不需要 Python。** 一个 exe，双击即用。

---

## 原理

```
搜索(GET /luna/pc/sug) ─┐
                        ├─→ 取 track_id
粘贴 ID / 分享文案 ─────┘
                              │
                              ▼
        POST /luna/pc/track_v2   ← 需要 X-Helios / X-Medusa 签名头
                              │
        ┌─────────────────────┴─────────────────────┐
        ▼                                           ▼
  track_player.video_model                  track_player.url_player_info
  （一次给全档位 + 各档 spade_a）              （预签名 URL → GetPlayInfo）
        └─────────────────────┬─────────────────────┘
                              ▼
                  decodeSpade(spade_a) → AES-128 key
                              │
                              ▼
                    下载加密流（CDN 预签名 URL）
                              │
                              ▼
              CENC 解密（AES-128-CTR 逐样本）→ 明文
                              │
                              ▼
              写入标签（VORBIS_COMMENT / ilst）+ 封面
                              │
                              ▼
                   曲名 - 歌手.flac / .m4a
```

签名是唯一需要原生代码的一环：客户端把签名算法封在 `bdms.node`（N-API 模块）里，
实际算法在 `metasecml.dll`（字节 MetaSec SDK）。这两个文件是运行时依赖，本仓库不包含。

---

## 三条实测结论

这三条决定了整个设计，都做过对照实验：

**1. 签名只绑请求本身，不依赖 Cookie**

同一 URL + body，四组对照：签名含/不含 cookie、请求带/不带 cookie、签名与实发 cookie 不一致 ——
**全部返回 200 且档位相同**。所以签名与登录态可以彻底解耦，签名输入里不放 cookie 也照样通过。

**2. 播放权益绑设备，不绑 Cookie**

完全不带 cookie 时服务端仍下发全部 6 档（含 lossless）。权益跟着 URL query 里的
`device_id` / `iid` 走，不是跟着 cookie 走。

**3. 设备身份可以自助注册**

`POST /service/2/desktop/device_register/`，body 是 `gzip(payload)` 再经 `applogDecorated()` 装饰。
新注册的设备（`new_user=1`）实测**同样能拿到 lossless**。所以程序不内置任何人的身份，
每台机器首次运行注册自己的 did/iid，存在本地复用。

---

## 目录

| 文件 | 说明 |
|---|---|
| `luna_pc_server.js` | 本地服务：HTTP API + 下载队列 + 设备注册 + 可选登录态 |
| `luna_pc_ui.html` | 界面（Miuix 风格，浏览器打开） |
| `luna_core.js` | 解密核心（JS）：MP4 box 解析 / CENC-AES-CTR / FLAC 重组 / 标签与封面写入 |
| `sign.js` | 签名器（库 / CLI / stdin 三形态），可单独用于给任意 luna 请求签名 |
| `fetch.js` | 命令行版：批量取歌（`--ids` / `--batch`），自包含 |
| `merge_app.py` | 把 core + ui + server 合并成单入口（SEA 打包用） |
| `sea-config.json` | Node SEA 打包配置（内嵌原生模块用） |
| `set_icon.js` | 写 PE 图标与版本信息（纯 JS，resedit） |
| `make_icon.py` | 生成多尺寸 ICO（PIL） |
| `legacy-python/` | 早期的 Python 解密实现（JS 版是它的逐函数移植，保留供对照） |

---

## 运行

需要 Node.js 18+（开发验证用 22.x），以及三个原生模块：
`bdms.node`、`device.node`、`metasecml.dll`（从汽水客户端 `resources/app.asar.unpacked/` 提取）。

```bash
node luna_pc_server.js        # 起服务并自动开窗口
```

首次运行会注册设备身份，落到 `%APPDATA%\luna_export\device.json`（便携模式下为 exe 同目录）。

### 命令行

```bash
node fetch.js <track_id> [out_dir] [quality] [cookie_file]            # 单曲
node fetch.js --ids "id1,id2,..." [out_dir] [quality]                  # 内联多曲
node fetch.js --batch ids.txt [out_dir] [quality]                      # 列表文件

# 单独用签名器
node sign.js --url "https://api.qishui.com/..." --header "user-agent:LunaPC/3.8.0(467160162)"
```

命令行版会自动复用图形版注册的设备身份（`device.json`）；也可用 `LUNA_DID` / `LUNA_IID`
环境变量指定，或在 `sign.js` 里调 `init(did)`。

---

## 构建单文件 exe

```bash
# 1) 改 sea-config.json 里的原生模块路径为你本机的实际位置
# 2) 合并单入口
python merge_app.py

# 3) 生成 SEA blob
node --experimental-sea-config sea-config.json

# 4) 拷 node 运行时并注入
cp "$(which node)" 汽水导出.exe
npx postject 汽水导出.exe NODE_SEA_BLOB sea-prep.blob \
  --sentinel-fuse NODE_SEA_FUSE_fce680ab2cc467b6e072b8b5df1996b2

# 5) 改 PE 子系统为 GUI（双击不弹黑窗口）
python -c "
import struct
p='汽水导出.exe'; d=bytearray(open(p,'rb').read())
e=struct.unpack_from('<I',d,0x3C)[0]
struct.pack_into('<H',d,e+24+68,2)
open(p,'wb').write(d)"

# 6) （可选）写图标与版本信息
npm i resedit
node set_icon.js 汽水导出.exe icon.ico
```

产物约 92 MB（内含 Node 运行时 + 三个原生模块）。

---

## 说明

- 原生模块（`bdms.node` / `metasecml.dll`）是客户端的二进制文件，本仓库不包含，需自行提取。
- 程序默认**无状态**：不读 cookie、不碰客户端。若需要按账号维度取流，界面上有「从客户端同步」——
  从本机客户端 Cookies 库本地解密提取（DPAPI + AES-GCM），**不产生任何登录请求**。
- 内置「环境自检」：一键输出设备身份、运行环境、实际可用档位（结果存 `luna_music/_selfcheck.txt`）。
- 设备身份换机器自动重新注册；想强制换身份用界面上的「重新注册」。
