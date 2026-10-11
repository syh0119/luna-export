# -*- coding: utf-8 -*-
"""合并 luna_core.js + UI + luna_pc_server.js → luna_pc_app.js（SEA 单入口，无状态版）"""
import json

core = open("luna_core.js", encoding="utf-8").read()
head = """(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.__LUNA_CORE__ = factory();
})(typeof self !== 'undefined' ? self : globalThis, function () {"""
assert head in core, "UMD 头未匹配"
core = core.replace(head, "const CORE = (function () {")
core = core.rstrip()
assert core.endswith("});"), "尾部未匹配"
core = core[:-3] + "})();\n"

server = open("luna_pc_server.js", encoding="utf-8").read()
server = server.replace("const CORE = globalThis.__LUNA_CORE__ || require('./luna_core.js');",
                        "// CORE 由文件头部的解密核心定义")
ui = open("luna_pc_ui.html", encoding="utf-8").read()

app = (core + "\nconst UI_HTML = " + json.dumps(ui, ensure_ascii=False) + ";\n"
       + server.replace("const UI = fs.readFileSync(path.join(__dirname, 'luna_pc_ui.html'), 'utf8');", "const UI = UI_HTML;"))
open("luna_pc_app.js", "w", encoding="utf-8").write(app)
print("合并完成:", len(app), "chars（无状态版）")
