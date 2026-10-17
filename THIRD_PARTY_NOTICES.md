# 第三方来源

本项目原创代码采用根目录 [LICENSE](LICENSE) 中的 MIT 许可证。以下第三方依赖及资源保留其作者版权和各自许可证，不因本项目采用 MIT 而改变。

- AndroidX，Apache-2.0；Kotlin，Apache-2.0；OkHttp，Apache-2.0；Jsoup，MIT。
- AI 回答渲染内置 [markdown-it](https://github.com/markdown-it/markdown-it) `15.0.2`，MIT，Copyright (c) 2014 Vitaly Puzrin, Alex Kocharin；[KaTeX](https://github.com/KaTeX/KaTeX) `0.18.9`（含字体，仅保留 woff2），MIT，Copyright (c) 2013-2020 Khan Academy and other contributors。文件位于 `app/src/main/assets/ai/`，未作修改，`katex.min.css` 仅删去 woff / ttf 字体引用。
- 广告拦截内置 [EasyList](https://easylist.to/) 与 [EasyList China](https://github.com/easylist/easylistchina) 规则列表，GPL-3.0 与 CC BY-SA 3.0 双许可，版权归 EasyList 作者所有。文件位于 `app/src/main/assets/adblock/`，未作修改，运行时按周从上述地址更新。可选规则 [EasyPrivacy](https://easylist.to/)（同上双许可）与 [AdGuard 过滤规则](https://github.com/AdguardTeam/AdguardFilters)（GPL-3.0）不随应用分发，仅在用户开启后从原地址下载。

保留上游作者版权及许可证；本项目分发时一并提供本地维护的源码和构建说明。
