# 纪念日模块分层与通知类型枚举化 — 学习笔记

> 日期：2026-09-11
> 关联每日总结：[daily.md](daily.md)

## 一、核心概念

纪念日模块实现时，按照"CRUD 一套、提醒一套、调度一层"划分，最终结构：

```
com.couple.anniversary
├── controller
│   └── AnniversaryController.java
├── service
│   ├── AnniversaryService.java              ← 已有，管 CRUD
│   ├── AnniversaryReminderService.java      ← 新增，管"提醒"业务逻辑
│   ├── NotificationService.java             ← 新增，管"发通知"
│   └── impl
│       ├── AnniversaryServiceImpl.java
│       ├── AnniversaryReminderServiceImpl.java
│       └── NotificationServiceImpl.java
├── task
│   └── AnniversaryReminderTask.java         ← 调度层，只调 Service
├── mapper
│   ├── AnniversaryMapper.java
│   ├── ReminderLogMapper.java               ← 新增
│   └── NotificationMapper.java              ← 新增
├── entity
│   ├── Anniversary.java
│   ├── ReminderLog.java                     ← 新增
│   └── Notification.java                    ← 新增
└── util
    └── AnniversaryDateUtil.java             ← 抽 nextOccurrence
```

**三层职责**：
- 调度层 `AnniversaryReminderTask`：只管"什么时候跑"，一行调用 Service
- 业务层 `AnniversaryReminderService`：查纪念日、算天数、判断是否该提醒、防重、发通知
- 通知层 `NotificationService`：只管"怎么发"（站内信/邮件/短信）

**站内通知类型枚举化**：`type` 字段先固定 `ANNIVERSARY_REMIND`，随着业务增多用枚举承载：

```java
public enum NotificationType {
    ANNIVERSARY_REMIND("ANNIVERSARY_REMIND", "纪念日提醒");

    private final String code;      // 存库/接口传输用的编码
    private final String description; // 展示用描述

    public static NotificationType fromCode(String code) { ... }
}
```

## 二、独立 Service 的理由（为什么提醒不塞进 AnniversaryService）

| 角度 | 说明 |
|---|---|
| 职责不同 | CRUD 是被动触发，提醒是主动业务动作 |
| 依赖不同 | 提醒依赖 NotificationService、ReminderLogMapper，塞进去会让 CRUD Service 膨胀 |
| 方便升级 | 换 XXL-JOB 只动 task 层，Service 不动 |
| 可测试 | 单独测提醒逻辑，不用 mock 一堆 CRUD |

## 三、我的思考

> _把"提醒"拆成独立 Service 而不是塞进 AnniversaryService，我是对比了两种写法的：如果塞进去，Controller 会看到一个它根本不关心的 remind() 方法，impl 还要注入一堆通知相关依赖。独立之后 job、测试、渠道扩展都是增量。enum 也是同样的道理——先字符串先跑通，类型多了再退一步用枚举管理。_

## 四、规范总结

- 模块结构：controller / service(+impl) / task / mapper / entity / util
- 三层职责：调度(何时) → 业务(做什么+防重) → 通知(怎么发)
- 独立 Service 更有利于职责、依赖、升级、测试
- 通知类型：固定字符串 → 枚举（code + description + fromCode）
- `AnniversaryDateUtil` 抽公共 nextOccurrence，前后端口径统一

## 五、延伸 / 待验证

- 后续实现站内信完整功能（分页/未读数/已读/读全部）时，枚举化是否够用
- NotificationType.fromCode 传未知字符串返回 null，调用方需判空或升级为异常