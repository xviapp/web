# 第三方来源

- 内嵌代理模块来自本工作区 note 的裁剪版本，上游 [2dust/v2rayNG](https://github.com/2dust/v2rayNG)，固定 commit `a4dd3d08de80958701247b00a8a979131192d1b4`，GPL-3.0。许可证见 `../proxy-core/LICENSE`。浏览器适配包括宿主包名、设置键、外观以及去除 Capacitor 依赖的网络状态接入。
- [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite)，固定 release `v26.9.30`，LGPL-3.0；[Xray-core](https://github.com/XTLS/Xray-core)，MPL-2.0。固定版本及校验见 `../proxy-core/README.md`、`../proxy-core/build.gradle.kts`。
- AndroidX，Apache-2.0；Kotlin，Apache-2.0；OkHttp，Apache-2.0；Jsoup，MIT。
- AI 回答渲染内置 [markdown-it](https://github.com/markdown-it/markdown-it) `15.0.2`，MIT，Copyright (c) 2014 Vitaly Puzrin, Alex Kocharin；[KaTeX](https://github.com/KaTeX/KaTeX) `0.18.9`（含字体，仅保留 woff2），MIT，Copyright (c) 2013-2020 Khan Academy and other contributors。文件位于 `app/src/main/assets/ai/`，未作修改，`katex.min.css` 仅删去 woff / ttf 字体引用。代理模块的其他依赖与声明沿用模块内的许可证资源。

保留上游作者版权及许可证；本项目分发时一并提供本地维护的源码和构建说明。
