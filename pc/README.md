# 汽水导出（电脑端）

汽水音乐 PC 端的下载工具。给一个 track_id，把歌拉下来解密成 FLAC / M4A，歌词和封面写进文件内部。

exe 里打包了签名模块，不用另外装客户端；命令行版要自备 Node 18+ 和三个原生模块，说明见下。

先说清楚一件事：**能不能拿到完整歌曲和无损，是服务端按登录态判定的，工具本身不做任何绕过。**

匿名身份下，VIP 专属歌只返回约 30 秒的试听片段，而且只有一个 `higher` 档；带上会员账号的登录态，才会给完整时长和全部档位（`medium / higher / highest / hi_res / lossless / spatial`）。免费歌不受这个限制，匿名也能完整下载，有时候还给到无损。

同一首《我不难过》，同一个设备 ID，只差一个 cookie：

```
不带 cookie  → 1 档 [higher]       0.5 MB  ≈13 kbps    （30 秒试听）
带 cookie    → 5 档 [hi_res ...]   9.9 MB  ≈253 kbps   （完整）
```

所以想要 VIP 音乐，还是得有 VIP 的登录态。客户端侧的 hook（moonhook 那类）只是把 App 里的音质选项解锁出来，播放流本身照样来自账号权益，它变不出服务端没下发的数据。

## 用法

下载 exe 双击，浏览器会弹出界面。

搜索框里输入歌名可以从联想结果直接挑；也可以粘贴 track_id，App 分享文案里的长数字会被自动抓出来，粘一坨也行。勾上要的歌，点导出。

文件默认落在 exe 同目录的 luna_music 里，想放别处设环境变量 `LUNA_OUTDIR`。

第一次运行会注册本机设备身份，存在 `%APPDATA%\luna_export\device.json`。程序里没有内置任何账号，每台机器自己注册一个。删掉那个文件就换个新身份，不过换了也只是另一个匿名身份，VIP 歌的完整流还是拿不到。

界面上的「环境自检」会真发一次请求，报告当前能拿到哪些音质档位。换机器排查问题用这个最快，也能一眼看出登录态有没有生效。

命令行版 `fetch.js` 功能一样：

```
node fetch.js <track_id> [out_dir] [quality]
node fetch.js --ids "id1,id2" [out_dir]      # 直接列 id
node fetch.js --batch ids.txt [out_dir]      # 从文件读
```

三个原生模块（`bdms.node`、`device.node`、`metasecml.dll`）躺在汽水客户端的 `resources/app.asar.unpacked/` 里，exe 版已经打包进去了。

## 原理

流程是 track_v2 拿地址、下载、解密。

track_v2 是唯一需要签名的接口，签名放在 X-Helios 和 X-Medusa 两个 header 里，由客户端的 `bdms.node` 算，真正的算法在 `metasecml.dll`（字节的 MetaSec SDK）。它的响应有两种形态：带 `video_model` 时全档位和各自的 spade_a 一次给全；带 `url_player_info` 时给一个预签名 URL，再请求一次拿 GetPlayInfo 列表。

spade_a 是授权码，解出来是 16 字节的 AES-128 key。流走 CENC 加密（AES-128-CTR，每个样本一个 IV，在 senc box 里），按样本表逐个解密。最后重写 FLAC 的 VORBIS_COMMENT / M4A 的 ilst，把标题、歌手、专辑、歌词、封面填进去。

签名只负责让请求合法，跟你能拿到什么没关系。档位和时长由服务端按 cookie 里的账号身份决定——这也是为什么同一个设备 ID 换个 cookie 结果完全不同。往请求里塞 `quality=lossless`、`is_vip=1`、`enable_lossless=1` 这类参数都试过，全都没用；试听凭证 `LimitedFreeInfo` 还带着服务端签名（`sign` / `sign_version` / `expire_time`），客户端改不动。
