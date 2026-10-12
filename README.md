# luna-export

把汽水音乐（com.luna.music）手机上缓存的 `.mdl` 解密成带标签的 FLAC / M4A。纯 Java 写的，spade 密钥推导和 CENC 解密都是自己实现，产物和 Python 版对比过 SHA256，74 个文件完全一致。

一个 APK 两种用法。当普通 App 用是主要形态，打开扫描、勾选、导出，文件写到 `/sdcard/Music/汽水导出/`。当 LSPosed 模块用是可选的，挂在汽水音乐进程里被动收集密钥和曲目元数据，只写几十 KB 的 keys.json，没有界面也不常驻。

不管哪种用法，只有点了「导出选中」才会往 sdcard 写东西。打开应用、听歌、浏览都不会产生文件。

## 电脑端

`pc/` 是同一件事的电脑版，一个 exe 双击打开网页界面，搜索或粘贴 track_id 出无损 FLAC，不依赖手机也不依赖客户端。详细说明在 [pc/README.md](pc/README.md)。

## 用法

App 需要 root，要读汽水音乐的私有目录。装上直接开扫。

LSPosed 模式的话，在 LSPosed 里启用模块，作用域勾「汽水音乐」，重启宿主。它解决的是这种情况：汽水只在完整下载过的歌上落盘密钥，hook 把播放时经过的密钥也收下来，本地没缓存过的歌也能解开。

导出产物在 `/sdcard/Music/汽水导出/`，歌词 `.lrc` 跟歌放一起（要「所有文件访问」权限，没有的话歌词会退到 `Download/汽水导出/`）。报告和日志在 `/sdcard/Download/汽水导出/`。

列表里有些歌显示「缺密钥」，是因为汽水只在完整下载时才落盘 `encrypt_info`，下了一半的 `.mdl` 里连 key 都没有。想解哪首就先在汽水里把它完整下载下来。少数完整但缺密钥的，播放一次让 hook 抓到就行。

## 原理

`.mdl` 是个 MP4 容器，音频样本用 CENC 加密（AES-128-CTR，IV 在 senc box 里存 8 字节，补 8 个零当计数器块）。密钥来自接口下发的 `spade_a`：base64 解码后按 popcount 和 base36 的规则跳字节异或，得到 32 位 hex 就是 AES-128 key。这套推导参考了 yeqiu6080/qishui-decrypt（JS）和 jixunmoe/export_music（Python）。

找密钥时先看 tenc box 里的 KID 在不在索引里，没有就拿文件名 md5 反查 vid。标题、歌手、专辑、歌词、封面这些从宿主的 sqlite、keva 和网络缓存里抽出来。

## 相关项目

- [yeqiu6080/qishui-decrypt](https://github.com/yeqiu6080/qishui-decrypt) — 汽水解密的纯前端 JS 实现
- [jixunmoe/export_music](https://github.com/jixunmoe/export_music) — 从手机导出解密音乐的 Python 工具，SpadeDecryptor 出处
- [BilMiauz/NcmDumpAndroid](https://github.com/BilMiauz/NcmDumpAndroid) — 网易云版的同类思路，架构参考

汽水现有的 Xposed 模块（Deer God、moonhook 之类）都只做 VIP 绕过，没有做本地解密的。
