# 汽水导出（电脑端）

汽水音乐 PC 端的下载工具。给一个 track_id，把歌拉下来解密成 FLAC / M4A，歌词和封面写进文件内部。

不用装汽水客户端，不用登录，也不需要 Python。一个 exe，双击就开。

## 用法

下载 exe 双击，浏览器会弹出界面。

搜索框里输入歌名可以从联想结果直接挑；也可以粘贴 track_id，App 分享文案里的长数字会被自动抓出来，粘一坨也行。勾上要的歌，点导出。

文件默认落在 exe 同目录的 luna_music 里，想放别处设环境变量 `LUNA_OUTDIR`。

第一次运行会注册本机设备身份，存在 `%APPDATA%\luna_export\device.json`。程序里没有内置任何账号，每台机器自己注册一个，删掉那个文件就是换个新身份。

界面上的「环境自检」会真发一次请求，报告当前能拿到哪些音质档位。换机器排查问题用这个最快。

命令行版 `fetch.js` 功能一样：

```
node fetch.js <track_id> [out_dir] [quality]
node fetch.js --ids "id1,id2" [out_dir]      # 直接列 id
node fetch.js --batch ids.txt [out_dir]      # 从文件读
```

跑命令行要自备 Node 18+ 和三个原生模块（`bdms.node`、`device.node`、`metasecml.dll`），它们躺在汽水客户端的 `resources/app.asar.unpacked/` 里。exe 版已经把这些打包进去了。

## 原理

流程是 track_v2 拿地址、下载、解密。

track_v2 是唯一需要签名的接口，签名放在 X-Helios 和 X-Medusa 两个 header 里，由客户端的 `bdms.node` 算，真正的算法在 `metasecml.dll`（字节的 MetaSec SDK）。它的响应有两种形态：带 `video_model` 时全档位和各自的 spade_a 一次给全；带 `url_player_info` 时给一个预签名 URL，再请求一次拿 GetPlayInfo 列表。

spade_a 是授权码，解出来是 16 字节的 AES-128 key。流走 CENC 加密（AES-128-CTR，每个样本一个 IV，在 senc box 里），按样本表逐个解密。最后重写 FLAC 的 VORBIS_COMMENT / M4A 的 ilst，把标题、歌手、专辑、歌词、封面填进去。

两个原生模块是客户端的二进制文件，仓库里没有，要自己从客户端目录提取。签名只看请求本身，跟 cookie 无关；档位看 URL 里的设备 ID，所以新注册的设备也能直接拿无损。
