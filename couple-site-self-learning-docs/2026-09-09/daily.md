# 📝 每日总结 — 2026-09-09

## ✅ 今日完成
- **user 模块**：User 实体设计（Bcrypt 密码、唯一用户名、6位情侣码生成规则、头像上传、couple_id 关联）
- **common 模块**：`Result<T>` 统一返回体、`PageResult` 分页封装、`BusinessException`、`GlobalExceptionHandler` 全局异常处理
- **config 模块**：WebConfig 静态资源映射 `/uploads/**`、MyBatis-Plus 分页插件、`CoupleUserPrincipal`(record)、JWT 认证过滤器、Spring Security 配置
- **file 模块**：`LocalFileStorageServiceImpl` 文件上传（校验、`yyyy/MM/dd` 分目录、UUID 文件名、删除尽力而为）

## 📚 深度学习
- 全局异常处理体系（Result + BusinessException + GlobalExceptionHandler + ResponseEntity），详见 [学习笔记](global-exception-handling.md)
- 文件上传与静态资源映射完整数据链路，详见 [学习笔记](file-upload-chain.md)
- JWT 认证 + Spring Security + 构造器注入 + record 类型，详见 [学习笔记](auth-security.md)

## 🤔 关键决策
- 使用 `ResponseEntity` 让 HTTP 状态码与业务码一致，不是所有请求都返回 200
- 采用构造器注入代替 `@Autowired` 字段注入（final 不可变、循环依赖快速失败）
- 文件删除采用"尽力而为"策略，失败不阻断业务

## 📋 待办 / 跟进
- [ ] 持续看每个模块，**前后端对着看**（前端组件 ↔ 后端接口）
- [ ] 完善侧边栏，加上滑动条
- [ ] 文件上传可加魔数（文件头）验证、大文件异步删除

## ❓ 未解决问题
- Maven 插件启动有乱码问题，需要排查编码配置（`project.build.sourceEncoding=UTF-8` / 控制台编码）
- 还不完全清楚字段注入 vs 构造器注入各自具体会产生什么坑

## 📌 明日重点
- 继续 Layout 前端布局组件学习（el-container / 菜单高亮跟随路由 / 头像上传链路）
- Pinia 状态管理

## 💡 收获与反思
- 掌握 `pageResult.of(IPage, List<VO>)` 封装：返回结构解耦 ORM 框架，records 用业务 VO 而非实体
- `PageResult.of(page, voList)` 用方法级别的泛型 `<T>` 声明，由参数类型推导
- `Locale.ROOT` 保证 `toLowerCase` 跨环境（如土耳其语）转换一致；`Map.getOrDefault` 安全取值
- 静态资源映射用 `Paths.get(...).toAbsolutePath().normalize()` 处理路径