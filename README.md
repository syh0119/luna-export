# luna-export

解密汽水音乐（com.luna.music）在手机上缓存的 `.mdl` 文件，得到带完整标签的 FLAC / M4A。
纯 Java 实现（spade 密钥推导 + CENC/AES-128-CTR 逐样本解密），与参考的 Python 实现逐字节一致。

一个 APK 两种用法：

- **当普通 App 用**：打开后扫描、勾选、导出，文件写到 `/sdcard/Music/汽水导出/`。这是主要形态。
- **当 LSPosed 模块用**（可选）：挂在汽水音乐进程里被动收集密钥和曲目元数据，只写一个几十 KB 的 keys.json，没有界面、不常驻、不产歌曲。

只有你手动点「导出选中」才会往 sdcard 写文件。打开 App、听歌、浏览歌单都不会产出任何东西——之前试过自动导出，正常听歌时它也会默默下歌占空间，砍掉了。

## 电脑端

`pc/` 是同一件事的电脑版：一个 exe，双击打开网页界面，搜索或粘贴 track_id 直接出无损 FLAC。
不依赖手机、不依赖汽水客户端、不需要 Python（签名走本地原生模块，解密是纯 JS）。见 [pc/README.md](pc/README.md)。

## 用法

App 需要 root（要读汽水音乐的私有目录）。装上后直接开扫。

LSPosed 模式：LSPosed 里启用本模块，作用域勾「汽水音乐」，重启宿主。它的用处是，汽水只在完整下载过的歌上缓存密钥，hook 能把播放时经过的密钥也收下来，本地没缓存到的歌也能解（实测密钥从 390 涨到 556 条）。

导出产物：

- 音频在 `/sdcard/Music/汽水导出/`，歌词 `.lrc` 与歌曲同目录同名（需要「所有文件访问」权限，没有就退到 `Download/汽水导出/`）
- 导出报告和日志在 `/sdcard/Download/汽水导出/`

关于「缺密钥」：汽水只在完整下载时才落盘 `encrypt_info`，下了一半的 .mdl 连 key 都没有，所以缺密钥的基本都是残片。想解哪首就先在汽水里完整下载它。剩下少数完整但缺密钥的，播放一次让 hook 抓到就行。

## 原理

`.mdl` 是个 MP4 容器，音频样本用 CENC（AES-128-CTR，IV 在 senc box，8 字节后补零成计数器块）加密。密钥来自接口下发的 `spade_a` 字段：base64 解码后按 popcount/base36 规则跳字节异或，得到 32 位 hex 即 AES-128 key。这套推导参考了 yeqiu6080/qishui-decrypt（JS）和 jixunmoe/export_music（Python），我用 Java 重写后和 Python 产物对过 SHA256，74/74 一致。

密钥的查找：优先 tenc box 里的 KID，查索引；兜底用文件名 md5 反查 vid。曲目元数据从宿主的 sqlite / keva / 网络缓存里抽（注意 BLOB 列也要扫，宿主把整段 JSON 存 BLOB；另外它有把 JSON 转义嵌在字符串值里的情况，比如 playableJson 里的 `\"file_hash\"`，原文扫描扫不出来，得还原转义再扫一遍）。

## 构建

没有 Gradle，脚本直编：

```bash
bash build-cli.sh   # 桌面验证：javac 直编，可与 Python 产物交叉比对
bash build-apk.sh   # 出 APK：aapt2 + javac + d8 + zipalign + apksigner
```

```
core/     纯 Java 解密核心（Json/Spade/Mp4/Cenc/Tags/Index/Scanner/Pipeline），桌面安卓通用
android/  Dirs（root 命名空间处理）/ IndexBuilder / MediaStoreSink / Exporter 等
module/   LSPosed 入口，hook okhttp/okio 的响应读取做被动抓取
app/      Manifest、资源、Miuix 风格的 MainActivity（原生 View 手绘，零依赖）
cli/      桌面验证入口
```

一个会咬人的细节：应用侧 `su` 的挂载命名空间里 `/data/data` 是个空 stub（erofs 模块视图），真数据在 `/proc/1/root/data/data/com.luna.music`，`Dirs.stage()` 会自动切过去。

## 踩坑记录

- `.lrc` 写出来变 `.lrc.txt`：MediaStore 会按 MIME 的标准扩展名补后缀，`text/plain` 必中招，换 `application/octet-stream`
- 重复导出出现 `xxx (1).flac`：MediaStore 重名时会把旧条目改名成 `xxx (1)`，按原名删不掉，要同时匹配 `name` 和 `name (%)`；另外删除时用的路径前缀必须和写入时一致
- `String.format("%05.2f")` 是 HALF_UP，CPython 是 half-even，KRC 时间戳会差一点，用 `BigDecimal.setScale(2, HALF_EVEN)`
- javac 连注释里的 `\uXXXX` 都当 unicode 转义处理，注释里别写
- 宿主的 `-wal` / `.sgv` 是「二进制头尾夹 JSON」的容器，整串 JSON 解析必失败，得逐个值 `raw_decode`（Python 用 `JSONDecoder.raw_decode`，Java 自己写了 `parsePrefix`）
- 界面没用 Compose（不想引 androidx），Miuix 那套视觉是原生 View + drawable 复刻的

## 相关项目

- [yeqiu6080/qishui-decrypt](https://github.com/yeqiu6080/qishui-decrypt) — 汽水解密的纯前端 JS 实现
- [jixunmoe/export_music](https://github.com/jixunmoe/export_music) — 从手机导出解密音乐的 Python 工具，SpadeDecryptor 出处
- [BilMiauz/NcmDumpAndroid](https://github.com/BilMiauz/NcmDumpAndroid) — 网易云版的同类思路，架构参考

汽水音乐现有的 Xposed 模块（Deer God、moonhook 之类）都只做 VIP 绕过，不做本地解密，这个位置目前是空的。
