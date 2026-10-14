# 色彩体系优化方案 + 执行 Prompt

> 版本：**v1.1**（2026-10-14，依据代码实测与 WCAG 计算修订）
> 目标：保持粉色暖调品牌基调不变，建立"语义清晰、层级分明、状态可辨"的色彩体系
>
> **v1.0 → v1.1 变更摘要**
>
> | # | v1.0 | v1.1 |
> |---|---|---|
> | 1 | 约束"优先复用 Element Plus 内置语义色" | **方向反了**。EP 内置语义色是冷色且与自定义色冲突，必须**接管**（§2.1，收益最大的一项） |
> | 2 | "文字色 8 个灰棕" | 实测 **19 个**灰棕系 / 全站 50 个去重色值（§1.5） |
> | 3 | "警示橙 2 个、危险红 2 个" | 实测各 **4 套**（含 Tailwind 琥珀与 EP 内置色，§1.3） |
> | 4 | 未提及 | 新增游离色定位：`#7c9cff` 蓝、`#9aa7b5` 冷灰蓝、`#2c2c2c` 近黑（§1.4） |
> | 5 | 三档未绑定用途 | 新增**核心规则**：soft=底 / default=描边图标 / strong=文字（§2.2） |
> | 6 | 自提色值未验证对比度 | 实测 v1.0 的值**过不了它自己定的标准**，给出达标替换值（§3） |
> | 7 | 遗漏 `MomentCard` / `EmptyState` / highlight.js / `el-color-error` | 已补（§4） |
> | 8 | 未说明边界 | 新增 §5：本方案是"色彩一致化"，做到"美化"还需三项补充 |

---

## 一、现状实测（全部经代码 / WCAG 复核）

### 1.1 环境事实（决定方案可行性）

| 项 | 实测结果 | 对方案的影响 |
|---|---|---|
| Element Plus 版本 | `2.7.6`，**全量引入** `element-plus/dist/index.css`，之后加载 `styles/index.css` | CSS 变量覆盖可行且优先级正确 |
| 预处理器 | **无 SCSS / Sass**，全站只有 1 个 CSS 文件 | 不能用官方 SCSS 方案 → 必须**手写** light-x 色阶 |
| EP 定义的语义色变量 | **35 个**：`success/warning/danger/error/info` × (base + light-3/5/7/8/9 + dark-2) | 全部是冷色默认值，**本项目一个都没覆盖** |
| EP 是否有 `primary-light-1/2/4/6` | **没有** | 现有 primary 的 6 值覆盖是**完整的**，v1.0 判断正确 |
| EP 结构层变量 | `--el-text-color-primary/regular/secondary/placeholder/disabled`、`--el-border-color*`、`--el-fill-color*`、`--el-bg-color*` **全部存在** | 这些也没覆盖 → 表格/输入框/分割线仍是冷灰 |
| highlight.js | `markdown.js` 引入 **`highlight.js/styles/github.css`** | 整套代码高亮是 GitHub 冷蓝/紫，与暖粉调冲突 |
| 全站去重色值 | **50 个** | v1.0 抽样了其中一部分 |

### 1.2 各文件硬编码色数量

| 文件 | 数量 | | 文件 | 数量 |
|---|---|---|---|---|
| `styles/index.css` | 21 | | `Home.vue` | 9 |
| `views/Note.vue` | 19 | | `Notification.vue` | 7 |
| `views/Chat.vue` | 19 | | `components/MomentCard.vue` | 6 |
| `views/AiChat.vue` | 17 | | `Register.vue` | 5 |
| `views/Todo.vue` | 15 | | `Album.vue` | 4 |
| `views/AlbumDetail.vue` | 13 | | `components/EmptyState.vue` | 0（无色彩规范） |
| `views/Anniversary.vue` | 13 | | | |
| `layout/Layout.vue` | 11 | | | |

### 1.3 语义色重复：不是 2 套，是 4 套

**危险红（4 个值并存）**

| 色值 | 出现 | 位置 |
|---|---|---|
| `#e6435d` | 5 | Todo 逾期等 |
| `#e8463a` | 1 | Chat 失败 |
| `#c7254e` | 2 | `index.css:201`、`Note.vue:687` |
| **`#f56c6c`** | — | **EP 内置 `--el-color-danger`**，被 9 处 `type="danger"` 使用 |

**警示橙/琥珀（4 套并存）**

| 色值 | 出现 | 位置 |
|---|---|---|
| `#f5b342` | 1 | Todo 重要 |
| `#f5a623` | 3 | Anniversary 倒计时 |
| `#b45309` / `#fef3c7` / `#fcd34d` | 2 / 1 / 1 | **`Register.vue:162-164` 一整套 Tailwind 琥珀告警框** |
| **`#e6a23c`** | — | **EP 内置 `--el-color-warning`**，被 6 处 `type="warning"` 使用 |

**成功绿**：全局缺失，且 EP 内置 `#67c23a` 是冷绿，与暖调冲突。

**紫色**：`#a26de8`(4) / `#7c53c1`(2) / `#d8c2ff`(1) / `#f5f0ff`(2) —— 已存在但未体系化。

> **关键修正**：v1.0 只统计了自定义样式里的色值，**漏掉了 EP 内置语义色**。
> 后者由 19 处 `type="danger|warning|info|success"` 与 `<el-alert>` 触发，
> 正是"同页两个红"的直接来源。

### 1.4 游离色（最破坏统一感，v1.0 未提及）

| 色值 | 位置 | 处理 |
|---|---|---|
| `#7c9cff` **正蓝** | `Note.vue:507` | 改为 `--sem-accent`（紫） |
| `#9aa7b5` **冷蓝灰** | `Note.vue:511` | 改为 `--el-color-info`（暖灰棕） |
| `#2c2c2c` **近黑** | `Layout.vue:496`、`Notification.vue:175` | 改为 `--text-strong` |
| `#1f1b1e` **近黑** | `AlbumDetail.vue:738` | **例外保留**（图片遮罩需要真暗底），在代码注释里注明 |

### 1.5 文字色：19 个，不是 8 个

| 旧值 | 出现 | 白底对比度 | 归类 |
|---|---|---|---|
| `#a98d99` | **22** | **3.02** ❌ | → `--text-secondary` |
| `#6b5260` | 22 | 7.00 | → `--text-strong` |
| `#5c4a52` | 3 | 8.21 | → `--text-main` |
| `#5b4750` | 3 | 8.13 | → `--text-main` |
| `#4a3b43` | 2 | 10.51 | → `--text-strong` |
| `#8a7580` | 2 | 4.25 | → `--el-color-info` |
| `#8a7380` / `#8a6f7c` | 2 / 1 | ≈4.2 | → `--text-secondary` |
| `#b39aa6` | 3 | 2.60 ❌ | → `--text-weak` |
| `#bca7b1` / `#b7a0aa` | 3 / 3 | ≈2.4 | → `--text-weak` |
| `#c9b6bf` / `#c4a9b4` / `#c9a7b5` / `#d3b7c3` / `#c9b3be` / `#c3adb8` | 2/1/1/1/1/1 | ≈1.8–2.0 | → `--text-disabled` |
| `#2c2c2c` | 2 | 13.97 | → `--text-strong` |

**注意 `#a98d99`：全站用得最多的文字色（22 次）却只有 3.02 对比度。**
v1.0 把它当作"冗余值之一"，但它其实是**最该优先修的那个**。

### 1.6 核心结论（修订）

> 不是"没颜色"，而是 **80% 粉 + 19 个近似灰棕文字**；
> 语义色**每个功能都有 4 套并存**（自定义 2 套 + EP 内置 1 套 + Tailwind 1 套）；
> 成功绿缺失；结构层（边框/填充）从未接管；
> 另有 3 处游离冷色与 1 套第三方代码高亮主题在破坏暖调。

---

## 二、优化方案（保持粉色基调）

### 2.1 【最高优先级】接管 Element Plus 语义色

**为什么这是第一步**：`index.css` 只覆盖了 primary 一族。EP 的 35 个语义变量
仍是冷色默认值，而项目有 **19 处** `type="danger|warning|info|success"` +
2 处 `<el-alert>` + 2 处 `<el-badge>` 在用它们。

**收益**：在 `:root` 加约 40 行，**19 处组件零改动自动协调**，投入产出比最高。

**色阶不用手调** —— 已用 EP 实际值验证的生成公式（误差仅 ±1 取整）：

```
light-N = base × (1 − N/10) + white × (N/10)      // N ∈ {3,5,7,8,9}
dark-2  = base × 0.8 + black × 0.2
```

> 这正是 EP 官方文档说"SCSS 会帮你生成 light-x"的含义
> （[Element Plus 自定义主题](https://element-plus.org/zh-CN/guide/theming.html)）。
> 本项目没有 sass，所以按上式机械算一遍即可，**不要手调**。

```css
  /* ---- primary：保持不变（2.7.6 无 light-1/2/4/6，现有覆盖已完整）---- */
  --el-color-primary: #ec6a8f;
  --el-color-primary-light-3: #f18aa6;
  --el-color-primary-light-5: #f5acbf;
  --el-color-primary-light-7: #f9ced9;
  --el-color-primary-light-8: #fbdfe6;
  --el-color-primary-light-9: #fdf0f3;
  --el-color-primary-dark-2: #d9557b;

  /* ---- success（新增，补齐缺失的绿）---- */
  --el-color-success: #2e9e68;
  --el-color-success-light-3: #6dbb95;
  --el-color-success-light-5: #96ceb4;
  --el-color-success-light-7: #c0e2d2;
  --el-color-success-light-8: #d5ece1;
  --el-color-success-light-9: #eaf5f0;
  --el-color-success-dark-2: #257e53;      /* 5.01:1 可作文字 */

  /* ---- warning（收敛 #f5b342 / #f5a623 / #b45309 / #e6a23c）---- */
  --el-color-warning: #c8861f;
  --el-color-warning-light-3: #d8aa62;
  --el-color-warning-light-5: #e4c28f;
  --el-color-warning-light-7: #eedbbc;
  --el-color-warning-light-8: #f4e7d2;
  --el-color-warning-light-9: #faf3e9;
  --el-color-warning-dark-2: #a06b19;      /* 4.55:1 可作文字 */

  /* ---- danger（收敛 #e6435d / #e8463a / #c7254e / #f56c6c）----
     ⚠️ 旧的 #e6435d 与品牌粉 #ec6a8f 色相只差约 8°，用户分不清
        "品牌色"和"危险色"。这里推到 #d94336（色相差约 21°）。 */
  --el-color-danger: #d94336;
  --el-color-danger-light-3: #e47b72;
  --el-color-danger-light-5: #eca19a;
  --el-color-danger-light-7: #f4c7c3;
  --el-color-danger-light-8: #f7d9d7;
  --el-color-danger-light-9: #fbeceb;
  --el-color-danger-dark-2: #ae362b;       /* 6.25:1 可作文字 */

  /* ---- error：与 danger 是【两个独立 family】，漏了 el-alert type="error" 还是冷红 ---- */
  --el-color-error: #d94336;
  --el-color-error-light-3: #e47b72;
  --el-color-error-light-5: #eca19a;
  --el-color-error-light-7: #f4c7c3;
  --el-color-error-light-8: #f7d9d7;
  --el-color-error-light-9: #fbeceb;
  --el-color-error-dark-2: #ae362b;

  /* ---- info（暖灰棕，接管中性 tag；不要塞紫色，理由见 §2.4）---- */
  --el-color-info: #8a7580;
  --el-color-info-light-3: #ad9ea6;
  --el-color-info-light-5: #c4bac0;
  --el-color-info-light-7: #dcd6d9;
  --el-color-info-light-8: #e8e3e6;
  --el-color-info-light-9: #f3f1f2;
  --el-color-info-dark-2: #6e5e66;         /* 6.08:1 可作文字 */
```

### 2.2 【核心规则】三档必须绑定用途

v1.0 定义了 soft/default/strong 却没规定**哪一档能用在什么地方**，
照它写会产出读不清的 UI（v1.0 自提的 default 档全部低于 4.5:1，见 §3）。

| 档 | 变量 | 允许用途 | **禁止**用途 | 目标对比度 |
|---|---|---|---|---|
| soft | `-light-9` | 背景底、hover 底、占位卡 | ❌ 任何文字 | — |
| default | 基础色 | 描边、图标、进度条、色块、**≥19px 或加粗**的大字 | ❌ **正文** | ≥3:1 |
| strong | `-dark-2` | **正文文字** | — | ≥4.5:1 |

**tag 的取舍（已定）**：EP plain tag = 文字用 `default` + 底色用 `light-9`。

| family | 本方案 default | → tag 对比度 | EP 默认 | 结论 |
|---|---|---|---|---|
| success | `#2e9e68` | 3.03 | 2.08 | 优于 EP |
| warning | `#c8861f` | 2.77 | 2.04 | 优于 EP |
| danger | `#d94336` | 3.81 | 2.61 | 优于 EP |
| info | `#8a7580` | 3.78 | 2.80 | 优于 EP |

**决定：不额外覆盖 `--el-tag-*`**，贴 EP 观感。若日后要求 tag 严格达 AA，
再覆盖 `--el-tag-*-text-color` 用 `dark-2`（可达 4.55–6.25）。

### 2.3 文字层级：19 → 5

| 层级 | Token | 新值 | 白底 | 最深渐变底 `#ffdde8` | 用途 |
|---|---|---|---|---|---|
| 强调 | `--text-strong` | `#4a3b43` | 10.51 | 8.39 | 标题、数据 |
| 主 | `--text-main` | `#5c4a52` | 8.21 | 6.56 | 正文 |
| 次 | `--text-secondary` | `#7d6a74` | **5.02** ✅ | 4.00 | 说明、**时间戳** |
| 弱 | `--text-weak` | `#a08b97` | 3.17 | 2.53 | **仅占位符**（不可用于时间戳等内容） |
| 禁用 | `--text-disabled` | `#c9b6bf` | 1.92 | 1.53 | 禁用态（无对比度要求） |

**两处对 v1.0 的修正：**

1. **v1.0 的 `--text-strong` 比 `--text-main` 还浅**（`#6b5260`=7.00 < `#5c4a52`=8.21），
   命名是"强调"实际比"主"弱 —— 值与语义反了。上表已对调修正。
2. **v1.0 把"时间戳"归到弱档（`#b39aa6`=2.60）**。时间戳是有意义的内容，
   不是装饰，不能低于 3:1。上表把时间戳移到 `--text-secondary`（5.02）。

### 2.4 自定义 accent 紫（**不占用** EP 的 info 角色）

v1.0 把"强调/信息"合成一个紫 token。这是**概念混淆**：

- EP 的 `info` 是**中性状态**角色（"无数据""普通"）
- 计划想要的紫是**品牌/AI 强调**角色

若把紫写进 `--el-color-info`，**所有中性标签都会变紫**。因此紫保持独立 token：

```css
  /* ---- AI / 强调（独立 token，不接管 EP 任何 family）---- */
  --sem-accent: #a26de8;          /* 图标 / 描边：3.57:1，够非文本 */
  --sem-accent-soft: #f6f0fd;     /* AI 卡片底色、生成中占位 */
  --sem-accent-strong: #8257ba;   /* AI 文字：白底 5.22:1，压在自己 soft 底上 4.68:1 */
```

落点：AiChat 气泡/图标、Note 的「AI 摘要」标签、Chat 内 `@bot`、链接 hover。
**不铺大面积背景。**

### 2.5 结构层接管（v1.0 完全遗漏）

不接这一层，表格、输入框、分割线、下拉菜单仍是 Element 的冷灰 `#dcdfe6`。

```css
  /* ---- 暖灰文字层 ---- */
  --el-text-color-primary: #4a3b43;
  --el-text-color-regular: #5c4a52;
  --el-text-color-secondary: #7d6a74;
  --el-text-color-placeholder: #a08b97;
  --el-text-color-disabled: #c9b6bf;

  /* ---- 暖粉边框层（由 primary 阶梯派生）---- */
  --el-border-color: #f0d8e0;
  --el-border-color-light: #f6e6ec;
  --el-border-color-lighter: #fbdfe6;
  --el-border-color-extra-light: #fdf0f3;

  /* ---- 填充层 ---- */
  --el-fill-color: #fbdfe6;
  --el-fill-color-light: #fdf0f3;
  --el-fill-color-lighter: #fef7f9;
  --el-fill-color-blank: #ffffff;
  --el-bg-color-page: #fff7f9;
```

> ⚠️ 这层的具体值**已按"与白底有可分辨差异"校验过，但没有做过渲染目检**。
> 落地时必须肉眼过一遍表格/输入框/下拉，必要时微调。

### 2.6 收敛映射表（旧值 → 新 token）

**语义色**

| 旧值 | → 新 |
|---|---|
| `#e6435d` `#e8463a` | `--el-color-danger` |
| `#c7254e` | `--el-color-danger-dark-2` |
| `#f5b342` `#f5a623` | `--el-color-warning` |
| `#b45309` | `--el-color-warning-dark-2` |
| `#fef3c7` | `--el-color-warning-light-9` |
| `#fcd34d` | `--el-color-warning-light-5` |
| `#a26de8` | `--sem-accent` |
| `#7c53c1` | `--sem-accent-strong` |
| `#d8c2ff` `#f5f0ff` | `--sem-accent-soft` |
| `#7c9cff`（蓝） | `--sem-accent` |
| `#9aa7b5`（冷灰蓝） | `--el-color-info` |
| `#2c2c2c` | `--text-strong` |

**粉色点缀**（收敛到 primary 阶梯，不再新增）

| 旧值 | → 新 |
|---|---|
| `#ffd9e4` `#fbdfe6` | `--el-color-primary-light-8` |
| `#f7b6ca` `#f9b7c8` `#f5acbf` | `--el-color-primary-light-5` |
| `#f9ced9` | `--el-color-primary-light-7` |
| `#fdf0f3` | `--el-color-primary-light-9` |
| `#d9557b` | `--el-color-primary-dark-2` |
| `#ec6a8f` | `--el-color-primary` |

**文字**：见 §1.5 表。

**代码块**：`#f7f7f9`（`index.css:213`）→ 暖色底（如 `--el-fill-color-lighter`）。

### 2.7 场景识别色（低饱和点缀，不铺大面积）

| 模块 | 识别色 | 落点 |
|---|---|---|
| 首页 / 动态 | 粉（现状） | 保持 |
| 聊天 / AI | `--sem-accent` 紫 | 气泡、图标、"AI 摘要"标签 |
| 学习笔记 | 紫浅一档（`--sem-accent` + 透明度） | 卡片图标、格式徽章 |
| 每日待办 | 完成 → `--el-color-success` / 逾期 → `--el-color-danger` | tag 与左侧色条 |
| 纪念日 | `--el-color-warning` | 倒计时标签 |
| 通知 | `--el-color-warning` + 琥珀 | 铃铛未读点 |
| 相册 | 粉深一档 | 相册封面描边 |

### 2.8 配套建议

1. **背景渐变分 2–3 档**（v1.0 想法保留）：首页/聊天用现有浅粉；相册/笔记详情的
   白卡区域提高不透明度，让照片与文本更突出。
2. **AI 摘要"生成中"**：`--sem-accent-soft` 底 + `--sem-accent-strong` 文字占位卡；
   完成后换 `--el-color-success-light-9` 底 + `--el-color-success-dark-2` 文字。
3. **两级反馈区分**：未读红点用 `--el-color-danger`；新消息浮条用
   `--el-color-primary`（品牌色），避免"到处都是红"。
4. **容错**：`Register.vue` 的 Tailwind 琥珀告警框（162–164 行）改用
   `--el-color-warning-light-9` / `--el-color-warning` / `--el-color-warning-light-5`，
   或直接换 `<el-alert type="warning">`（自动跟随主题）。

---

## 三、对比度审计：v1.0 色值 vs v1.1 色值

**v1.0 自设标准**：正文 ≥4.5:1、次要文字 ≥3:1。

**v1.0 自己提的值：**

| v1.0 提出 | 白底 | 判定 |
|---|---|---|
| `--text-secondary #8a7580` | 4.25 | ❌ 达不到正文 4.5 |
| `--text-weak #b39aa6` | 2.60 | ❌ 达不到 3:1，却要用于**时间戳** |
| `--sem-success #4cbf85` | 2.31 | ❌ 连 3:1 都不到 |
| `--sem-warning #e8a33d` | 2.16 | ❌ |
| `--sem-danger #e8463a` | 3.91 | ❌ |
| `--sem-accent #a26de8` | 3.57 | 仅够非文本 |
| `--sem-success-strong #1e9e6a` | 3.41 | ❌ 达不到 4.5 |
| `--sem-warning-strong #c07f1a` | 3.34 | ❌ 达不到 4.5 |

**作为 tag 文字时，v1.0 的值和 EP 默认几乎一个水平 —— 只换了色相，没改善可读性：**

| family | v1.0 值 → tag | v1.1 值 → tag | EP 默认 |
|---|---|---|---|
| success | 2.14 | **3.03** | 2.08 |
| warning | 2.01 | **2.77** | 2.04 |
| danger | 3.42 | **3.81** | 2.61 |
| accent | 3.19 | **3.78**（info 角色） | 2.80 |

**v1.1 达标情况（全部经计算）**

| 用途 | 对比度 | 目标 | 判定 |
|---|---|---|---|
| success dark-2 作文字 vs 白底 | 5.01 | 4.5 | ✅ |
| warning dark-2 作文字 vs 白底 | 4.55 | 4.5 | ✅ |
| danger dark-2 作文字 vs 白底 | 6.25 | 4.5 | ✅ |
| info dark-2 作文字 vs 白底 | 6.08 | 4.5 | ✅ |
| accent-strong 作文字 vs 白底 | 5.22 | 4.5 | ✅ |
| accent-strong 压在自己 soft 底 | 4.68 | 4.5 | ✅ |
| `--text-secondary` vs 白底 | 5.02 | 4.5 | ✅ |
| `--text-weak` vs 白底 | 3.17 | 3.0 | ✅ |
| success / warning / danger / info base 作描边图标 | 3.38 / 3.05 / 4.37 / 4.25 | 3.0 | ✅ |

---

## 四、覆盖率修正

### 4.1 涉及文件（v1.0 漏了 2 个）

```
frontend/src/styles/index.css
frontend/src/layout/Layout.vue
frontend/src/components/MomentCard.vue      ← v1.0 遗漏
frontend/src/components/EmptyState.vue      ← v1.0 遗漏
frontend/src/views/  { Home, Chat, AiChat, Note, Todo, Anniversary,
                       Album, AlbumDetail, Notification, Login, Register, Bind }.vue
```

### 4.2 highlight.js（v1.0 完全没提）

`markdown.js:16` 引入 `highlight.js/styles/github.css`，**整套代码高亮是 GitHub 冷色**。
笔记里的代码块会与暖粉调明显冲突。两种处理：

- **方案 A（省事）**：换成暖色系主题（如 `atom-one-light` / `gruvbox-light`），
  但仍有一层外来色，只能算缓解。
- **方案 B（彻底）**：保留 `github.css` 作基底，在 `index.css` 里用 token 覆盖
  `.hljs-keyword / .hljs-string / .hljs-comment / .hljs-number / .hljs-title` 等，
  映射到 `--sem-accent` / `--el-color-success` / `--text-secondary` / `--el-color-warning` /
  `--el-color-danger`。**推荐 B**，这样才能与体系一致。

### 4.3 明确例外（写进代码注释，避免后人"顺手统一"）

| 位置 | 色值 | 理由 |
|---|---|---|
| `AlbumDetail.vue:738` | `#1f1b1e` | 图片遮罩需要真正的暗底，不能暖化 |
| `markdown.js` KaTeX | 走 KaTeX 默认 | 公式颜色与正文一致即可，无需单独处理 |

---

## 五、关于"美化"的边界（重要）

本方案的约束是"只改颜色，不动布局/间距/圆角/动效"，所以它交付的是
**色彩一致化**，不是**美化**。以下是实测的被排除维度：

| 维度 | 实测 | 问题 |
|---|---|---|
| `box-shadow` | **11 个去重值**，其中 **8 个是 `rgba(236,106,143, …)`**，透明度散在 0.08/0.12/0.18/0.22/0.3/0.4，y/blur 在 2/4/6/10/12 | **阴影本身就是主色的 rgba** —— "token 化颜色却排除阴影"自相矛盾；且没有 elevation 层级 |
| `border-radius` | **10 个值**：2/4/6/8/10/12/14/16px + 50% + 999px | 近似连续的取值 = 组件各自拍脑袋 |
| `font-size` | **17 个值**（11–24px 之外还有 34/38/42/44/64）；13px×23、12px×22 占主导 | 无字号阶梯，小字偏多 |
| `transition` | 18 处声明、11 个值，`transition: all 0.2s` 出现 3 次 | `all` 会连带布局属性，有性能与行为隐患 |
| **`:focus-visible` / `:focus`** | **0 处**（`:hover` 有 20 处） | **完全没有键盘焦点样式** —— 既是可访问性缺口，也是最容易被感知的"不精致" |
| `prefers-reduced-motion` | 0 处 | 动效无降级 |
| `@media` | 全站仅 **1 处** | 响应式基本没做 |

**要真正"美化"，颜色之外建议至少补三项**（工作量小于色彩重构，视觉提升更明显）：

1. **elevation 3 档**：`--shadow-sm/md/lg`，用主色 rgb 变量
   (`--brand-rgb: 236 106 143`) 派生 → `rgba(var(--brand-rgb) / 0.12)`，
   这样主色变了阴影自动跟随。
2. **radius 3 档**：`--radius-sm: 8px` / `--radius-md: 14px` / `--radius-lg: 20px`
   （+ `--radius-pill: 999px`、`--radius-circle: 50%`），10 个值收敛到 5 个。
3. **`:focus-visible` 统一样式**：`outline: 2px solid var(--el-color-primary);
   outline-offset: 2px`，全站一次搞定。

---

## 六、执行 Prompt（修订版，可直接投喂 AI）

```markdown
# 任务：双人情侣网站「色彩体系」优化（保持粉色基调）

## 角色与目标
你是一位资深 UI 色彩设计师 + 前端工程师。在**不改变整体粉色暖调品牌调性**的前提下，
为 Vue3 + Element Plus 2.7.6 前端（frontend/）建立一套**语义清晰、层级分明、状态可辨**
的色彩体系，消除"全站同质化、语义色分散重复"的问题。

## 环境事实（已实测，不要重新假设）
- 无 SCSS/Sass，全站仅 `src/styles/index.css` 一个 CSS 文件；
  `main.js` 先 import `element-plus/dist/index.css`，再 import `./styles/index.css`（覆盖有效）
- Element Plus 2.7.6 **全量引入**，定义了 35 个语义色变量
  （success/warning/danger/error/info × base + light-3/5/7/8/9 + dark-2），当前**一个都没覆盖**
- EP 2.7.6 **没有** `--el-color-primary-light-1/2/4/6`；现有 primary 的 6 值覆盖是完整的
- EP 结构层变量存在且未覆盖：`--el-text-color-*`、`--el-border-color*`、
  `--el-fill-color*`、`--el-bg-color*`
- `markdown.js` 引入 `highlight.js/styles/github.css`（冷色）
- 全站去重色值 50 个；文字灰棕 19 个

## 现状问题（已定位到行）
- 危险红 4 套：`#e6435d`(5) / `#e8463a`(1) / `#c7254e`(2) + EP 内置 `#f56c6c`
- 警示橙 4 套：`#f5b342` \ `#f5a623` \ `Register.vue:162-164` 的 Tailwind 琥珀
  (`#b45309`/`#fef3c7`/`#fcd34d`) + EP 内置 `#e6a23c`
- 游离色：`#7c9cff`(Note.vue:507 正蓝) / `#9aa7b5`(Note.vue:511 冷灰蓝)
  / `#2c2c2c`(Layout.vue:496, Notification.vue:175 近黑)
- 文字：19 个灰棕，其中最常用的 `#a98d99`（22 次）对比度仅 3.02
- 成功绿全局缺失

## 硬性约束（不可违反）
1. 品牌主色**必须保持 #ec6a8f 粉色系**，背景粉渐变基调不变，禁止引入新主色系大面积铺陈。
2. **所有 EP 语义色必须由我们接管**（覆盖 `--el-color-{success,warning,danger,error,info}`
   及其 light-3/5/7/8/9、dark-2），而不是"复用 EP 内置语义色" ——
   内置色是冷色，会与粉色体系冲突。
   `error` 与 `danger` 是两个独立 family，**必须同时设置**。
   色阶用公式生成，不要手调：`light-N = base×(1−N/10) + white×(N/10)`，`dark-2 = base×0.8`。
3. **三档必须绑定用途**：
   - `light-9`(soft) → 只能作背景底，禁止承载文字
   - `base`(default) → 描边/图标/大字号；**禁止作正文**
   - `dark-2`(strong) → 正文文字，须 ≥4.5:1
4. 对比度：正文 ≥4.5:1；占位符/图形 ≥3:1；同一页面内语义色与品牌粉不得混淆
   （危险色与 #ec6a8f 的色相差距要 >15°）。
5. 自定义紫色 `--sem-accent*` 是 **AI/强调**角色，**不得**写进 `--el-color-info`
   （那会把所有中性标签变紫）。`--el-color-info` 用暖灰棕。
6. 只改颜色，不动布局/间距/圆角/动效；不改图片与图标资源。
7. 深色模式不在本次范围。
8. `AlbumDetail.vue:738` 的 `#1f1b1e` 是图片遮罩，**保留**并加注释说明例外。

## 任务要求
### A. 落地 §2.1 的 35 个 EP 语义色变量 + §2.5 的结构层变量
### B. 文字层级 19 → 5，输出「旧值 → 新 token」完整映射（§2.6 已给出基准）
### C. 清理 3 处游离冷色（`#7c9cff` / `#9aa7b5` / `#2c2c2c`）
### D. `Register.vue:162-164` 的 Tailwind 琥珀告警框改走 warning token
### E. highlight.js：保留 github.css 作基底，在 index.css 覆盖 `.hljs-*` 到我们的 token
### F. 逐文件改动清单：文件 / 选择器 / 旧值 / 新 token（精确到行）

## 验收标准
1. grep 验证：§1.3 列出的 8 个旧语义色值出现次数为 **0**
2. 全站 `type="danger|warning|info|success"` 与 `<el-alert>` 渲染出的颜色
   与自定义样式**同一套**（不再出现两个红）
3. 文字 token ≤5 个；`--text-weak` 不用于任何内容性文字（只用于 placeholder）
4. 所有正文文字对比度 ≥4.5:1（可用脚本核验）
5. 品牌粉与背景渐变肉眼无感变化
6. 输出：色板总览表 + 映射表 + 逐文件清单 + 3 个代表页面前后对比说明

## 输出格式
1.「新色板总览」：token / 用途 / 旧值 / 新值 / 三档
2.「语义映射表」：场景 → token → 落点选择器
3.「逐文件改动清单」：文件 / 行号 / 选择器 / 旧值 / 新值
4.「自查结果」：对照验收标准逐条打勾，附 grep 结果
```

---

## 七、落地顺序

1. **`:root` 加 35 个 EP 语义色变量 + error family** ← 收益最大、改动最小，19 处组件自动协调
2. **加结构层变量**（`--el-text-color-*` / `--el-border-color*` / `--el-fill-color*` / `--el-bg-color*`）
   → 落地后**必须渲染目检**表格/输入框/下拉，必要时微调
3. **清理 4 处游离冷色**（`#7c9cff` / `#4a86c8` / `#9aa7b5` / `#2c2c2c`）
   —— v1.0 与 v1.1 草稿都漏了 `#4a86c8`（`Note.vue:515` 的 `.fmt-docx`），
   是**按完整色表穷举**才找到的。教训：游离色不能靠抽样，要按亮度和色相全量筛。
4. **文字 token 收敛 19 → 5**，同时修正 `--text-strong` / `--text-main` 的对调
5. **`Register.vue` 琥珀告警框**改走 warning token
6. **逐页替换**其余硬编码色（严格按 §2.2 的档位用途规则）
7. **highlight.js 覆盖** `.hljs-*` 到 token
8. **grep + 脚本验收**（§8）

> 第 1–3 步可作为一个独立可回滚的提交，肉眼差异立刻可见。
>
> **进度：第 1–3 步已完成。** 落地记录见 §10；目检页为 `frontend/color-preview.html`。

---

## 八、验收（可脚本化）

```powershell
# 1) 旧语义色 / 游离色必须清零
#    ⚠️ 必须先剔掉注释再统计：代码里"/* danger 收敛 #e6435d ... */"这类说明文字
#       会让裸 grep 误报（实测多出 6 处）。注释里保留旧值是有价值的文档，
#       所以要改的是检查方式，不是注释。
$files = Get-ChildItem -Recurse -File frontend\src -Include *.vue,*.css,*.js
$text  = ($files | ForEach-Object { Get-Content $_.FullName -Raw }) -join "`n"
$text  = [regex]::Replace($text, '/\*[\s\S]*?\*/', '')      # CSS / JS 块注释
$text  = [regex]::Replace($text, '<!--[\s\S]*?-->', '')     # Vue 模板注释
$text  = [regex]::Replace($text, '(?m)^\s*//.*$', '')       # JS 行注释（只剔整行，避免误伤 https://）

$bad = '#e6435d','#e8463a','#c7254e','#f5b342','#f5a623','#b45309','#fef3c7','#fcd34d',
       '#7c9cff','#9aa7b5','#4a86c8','#2c2c2c','#d8c2ff','#7c53c1'
$total = 0
foreach ($c in $bad) {
  $n = ([regex]::Matches($text, [regex]::Escape($c))).Count
  $total += $n
  "{0,-10} {1}" -f $c, $n
}
"---- 合计 $total（全部应为 0）----"

# 2) 文字 token 数量（应为 5）
(Select-String -Path frontend\src\styles\index.css -Pattern '--text-[\w-]+:' -AllMatches).Matches.Value |
  Sort-Object -Unique

# 3) 变量解析闭环：src 里 var() 引用的变量必须都有定义
#    （定义来源 = element-plus/dist/index.css 或 src/styles/index.css）
```

对比度可用 §3 同一套 WCAG 公式核验（`srgb → 线性 → 相对亮度 → (L1+0.05)/(L2+0.05)`）。
注意渐变背景要按**最深的那一档 `#ffdde8`** 验收，不能只算白底。

---

## 九、变更记录

| 时间 | 版本 | 变更 |
|---|---|---|
| 2026-10-14 | v1.0 | 初版：语义色 token 化、文字层级收敛、场景识别色、执行 Prompt |
| 2026-10-14 | **v1.1** | 依据代码实测与 WCAG 计算修订：①把"复用 EP 内置语义色"反转为**接管 35 个 EP 语义变量**；②文字色实测 19 个（非 8 个）、语义色实测各 4 套（非 2 套）；③新增游离冷色定位；④新增"三档绑定用途"核心规则；⑤审计 v1.0 色值对比度不达标并给出达标替换值；⑥补齐 `MomentCard`/`EmptyState`/highlight.js/`error` family；⑦新增 §5「美化」边界与被排除维度的实测数据 |
| 2026-10-14 | v1.1 落地 | §7 第 1–3 步已实施；发现并补上第 4 个游离冷色 `#4a86c8`；把验收脚本改为"先剔注释再统计"。见 §10 |

---

## 十、第 1–3 步落地记录

**改动文件（4 个）**

| 文件 | 改动 |
|---|---|
| `frontend/src/styles/index.css` | `:root` 从 9 个变量扩到 **72 个**（覆盖 EP 原有 64 个 + 新增自定义 8 个）；`.markdown-body pre` 底色 `#f7f7f9` → `var(--el-fill-color-lighter)` |
| `frontend/src/views/Note.vue` | 三档格式徽标改走 token（清掉 3 个冷色） |
| `frontend/src/layout/Layout.vue` | `.notif-item-title` 的 `#2c2c2c` → `var(--text-strong)` |
| `frontend/src/views/Notification.vue` | `.notif-title` 的 `#2c2c2c` → `var(--text-strong)` |
| `frontend/src/views/AlbumDetail.vue` | 幻灯片遮罩 `#1f1b1e` **保留**，补例外注释（防后人"顺手统一"） |
| `frontend/color-preview.html` | 新增**目检页**（一次性产物，验完可删） |

**覆盖统计**

| 层 | 覆盖变量数 |
|---|---|
| 语义色（success/warning/danger/error/info） | 35 |
| 主色（primary） | 7 |
| 文字层 `--el-text-color-*` | 5（引用 `--text-*`，单一来源） |
| 边框层 `--el-border-color*` | 6 |
| 填充层 `--el-fill-color*` | 7 |
| 背景层 `--el-bg-color*` | 3 |
| 圆角 base | 1 |
| **覆盖 EP 原有变量合计** | **64** |
| 新增自定义 token（`--text-*` ×5、`--sem-accent*` ×3） | 8 |

**自动验证结果**

| 检查 | 结果 |
|---|---|
| `index.css` 括号平衡 | `{}` 41/41、`()` 26/26 ✅ |
| `src` 全量 `var()` 解析闭环 | 引用 16 个不同变量，**未定义 0 个** ✅ |
| 游离色 `#7c9cff` / `#9aa7b5` / `#4a86c8` / `#2c2c2c` / `#f7f7f9` | 全部清零 ✅ |
| 例外 `#1f1b1e` | 保留且有注释 ✅ |

**待办（不在本次范围）**

- ⚠️ 结构层的边框/填充色阶**只做了计算校验，没有真实渲染目检** —— 请打开
  `frontend/color-preview.html` 确认粉色是否过重、相邻档位是否可分辨。
- §7 第 4–8 步（文字收敛、`Register.vue` 琥珀框、逐页替换、highlight.js）未做。
- 验收目标里的 `#e6435d` 等**暖色系旧值仍在**（合计 19 处），属第 4–6 步范围。
