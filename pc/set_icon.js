// 用 resedit（纯 JS）给 exe 写入图标 + 版本信息
const fs = require('fs');
const path = require('path');
const { load } = require('resedit/cjs.cjs');

const EXE = process.argv[2];
const ICO = process.argv[3];
if (!EXE || !ICO) { console.error('用法: node _set_icon.js <exe> <ico>'); process.exit(1); }

(async () => {
  const ResEdit = await load();

  const data = fs.readFileSync(EXE);
  const exe = ResEdit.NtExecutable.from(data, { ignoreCert: true });
  const res = ResEdit.NtExecutableResource.from(exe);

  // 1) 图标：替换主图标组（id=1, lang=1033）
  const iconFile = ResEdit.Data.IconFile.from(fs.readFileSync(ICO));
  ResEdit.Resource.IconGroupEntry.replaceIconsForResource(
    res.entries, 1, 1033, iconFile.icons.map(i => i.data)
  );

  // 2) 版本信息（资源管理器里鼠标悬停可见）
  const vi = ResEdit.Resource.VersionInfo.createEmpty();
  vi.setFileVersion(1, 0, 0, 0);
  vi.setProductVersion(1, 0, 0, 0);
  vi.setStringValues({ lang: 1033, codepage: 1200 }, {
    ProductName: '汽水导出',
    FileDescription: '汽水导出 —— 无损音乐导出工具',
    OriginalFilename: path.basename(EXE),
  });
  vi.outputToResourceEntries(res.entries);

  res.outputResource(exe);
  fs.writeFileSync(EXE, Buffer.from(exe.generate()));
  console.log('已写入图标与版本信息:', EXE, (fs.statSync(EXE).size / 1048576).toFixed(1), 'MB');
})().catch(e => { console.error('失败:', e.message); process.exit(1); });
