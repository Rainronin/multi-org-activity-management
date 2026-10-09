# 第三方源码与许可

本项目中的 `src/shared/motion/SpotlightCard.vue`、`CountUp.vue`、`StatusMark.vue`、`RubberSegment.vue`、`BrandBeams.vue` 根据 Vue Bits 对应组件适配，仅用于本应用。

- 上游：https://github.com/DavidHDev/vue-bits
- 固定快照：07c0f76d5567db022c2e3185dd97a2311e056c0c
- 路径：src/content/Components/SpotlightCard/SpotlightCard.vue、src/content/TextAnimations/CountUp/CountUp.vue、src/content/Micro/StatusMark/StatusMark.vue、src/content/Micro/RubberSegment/RubberSegment.vue、src/content/Backgrounds/Beams/Beams.vue。
- 改动：移除 Tailwind 与额外图标依赖；统一 token 和中文语义；裁剪拖拽、进度参数及划线；按实际时间计数；登录背景低帧率、像素预算、按需加载、暂停与销毁。
- StatusMark 使用 SVG/CSS；RubberSegment 使用 motion-v；Beams 使用 Three.js。未将适配件作为独立组件库、模板或端口分发。
- Three.js、motion-v 及其间接依赖的各自许可随 npm 包保留，以锁文件所对应包内原文为准。

以下为上游固定快照 LICENSE.md 原文：
MIT + Commons Clause License Condition v1.0

Copyright (c) 2025 David Haz

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, and distribute the Software **as part of an application, website, or product**, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

## Commons Clause Restriction

You may use this Software, including for any commercial purpose, **so long as you do not sell, sublicense, or redistribute the components themselves-whether alone, in a bundle, template, or as a ported version.**

## No Warranty

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
