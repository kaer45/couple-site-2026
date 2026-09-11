# 📝 每日总结 — 2026-09-10

## ✅ 今日完成
- **Layout.vue 布局组件**：el-container 布局、顶部栏（绑定提示/头像/昵称/退出）、对话框
- **菜单高亮跟随路由**：理解 `route.path → activeMenu → default-active → index → .is-active` 链路
- **头像上传链路**：校验 → 上传 → 保存 URL → fetchMe 四步，三个接口分工
- **后端 Security 配置**：放行路径（/api/auth、/uploads）、上传接口 vs 静态资源对比、CSRF/无状态/401/403
- **Pinia 状态管理**：三大核心概念、两种写法、userStore 解析、踩坑点
- **纪念日业务设计**：两种时间概念区分、五问思考框架、天数计算逻辑、边界处理
- **纪念日提醒定时任务**：@Scheduled 方案、可升级 XXL-JOB、站内信方案设计

## 📚 深度学习
- Layout 布局 + 菜单高亮跟路由，详见 [学习笔记](layout-menu-highlight.md)
- 头像上传完整链路，详见 [学习笔记](avatar-upload-chain.md)
- 纪念日天数计算与业务设计，详见 [学习笔记](anniversary-date-calc.md)

## 🤔 关键决策
- 业务逻辑与调度解耦：`AnniversaryReminderService.remind()`（做什么）与 `@Scheduled`/XXL-JOB（什么时候做）分离
- 防重复用 `reminder_log` 表唯一键 `UNIQUE(anniversary_id, remind_date)`，不依赖调度器
- 站内信方案：notification 表按用户各插一条，类型先固定 `ANNIVERSARY_REMIND`（后续可枚举化）
- 后端天数判断用 `!isBefore` 而非 `isAfter`，避免"今天"被算成明年

## 📋 待办 / 跟进
- [ ] 实现站内信完整方案（表 + 实体 + Mapper + 接口 + 前端铃铛/通知页）
- [ ] 把 `nextOccurrence`/`withYearSafe` 抽成公共工具类 `AnniversaryDateUtil`
- [ ] 纪念日模块改造：upcoming 只显示最近 5 个、"在一起"纪念日也参与（去掉 isStart 过滤）、天数显示"还有 N 天"

## ❓ 未解决问题
- Maven 插件启动乱码问题依旧（编码配置待排查）
- 后端 Security 的 `/uploads/**` 放行安全性：靠 UUID 文件名不可猜，"brave"策略 vs 签名 URL 的取舍

## 📌 明日重点
- 落地纪念日定时任务实现（建表 reminder_log/notification、实体、Mapper、Service、Task）
- 站内信接口设计

## 💡 收获与反思
- 核心收获：理解了"前端状态 ↔ 后端接口"的完整链路，以及"业务逻辑与调度解耦"的设计思想
- Pinia 踩坑：解构丢失响应式要用 `storeToRefs`、`useUserStore` 是函数必须调用、组件外调用必须在函数内部
- Security 关键纠正：放行 /uploads 不是因为它安全，而是因为技术上 `<img>` 无法带 token