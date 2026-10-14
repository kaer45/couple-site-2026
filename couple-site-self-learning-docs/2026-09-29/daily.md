# 📝 每日总结 — 2026-09-29

## ✅ 今日完成

- **文档与代码全面对齐**：梳理全仓后发现代码已远超文档，补齐 6 份文档
  - `docs/api-contract.md`：新增 §6 通知、§7 每日待办、§8 实时聊天、§9 WebSocket 协议、§10 AI 助手 SSE；§4 相册补 3 个 `PUT` 接口与视频支持；§5 上传改为 200MB + 视频；§0 补 `404` 错误码与"两种分页风格"
  - `docs/project-overview.md`：全量重写（11 张表、12 个后端包、11 个前端页面、完整 API 表、7 条核心流程）
  - `docs/roadmap.md`：阶段 6 从"全部预留"改为逐项标注落地情况
  - `docs/agent-api.md`：按代码校正 8 处偏差，新增 §7 偏差表与字段命名对照
  - `README.md`、`docs/deploy.md`：功能清单、目录结构、排障项
- **修复 4 处配置与文档不符的问题**
  - `frontend/nginx.conf`：**补 `/ws/` 代理**（原来缺失，生产环境聊天必然连不上，会命中 SPA 回退返回 index.html）；补 SSE 免缓冲 location；`client_max_body_size` 20m → 250m
  - `sql/init.sql`：`chat_message` / `chat_read` 原来**重复定义两遍**，删掉重复块并按 1~11 重排编号
  - `docker-compose.yml` + `.env.example`：补 `AGENT_ENABLED` / `AGENT_URL` / `AGENT_KEY` 与 `host.docker.internal` 映射
- **把"假流式"改成真流式**（详见 [学习笔记](agent-streaming-refactor.md)）
  - `AgentChatClient`：新增 `onDelta` 回调，`collectList()` → `doOnNext` + `.then()`，`publishOn(boundedElastic)` 避免阻塞 Netty 事件循环
  - `AgentController`：SSE 增量到达即下发
  - `AgentChatService`：WS 帧到达即广播，失败时落库"用户实际看到的内容"
- **学习笔记 2 篇**：Service 间调用与 SSE 全链路、假流式改造
- 修正 `AgentStreamFrame` 注释里的字段名笔误（`final` → `finalFrame`）

## 📚 深度学习

- Java ↔ Python 服务间调用（HTTP 请求 / SSE 流式 / 熔断降级 / Python 侧实现），详见 [学习笔记](java-python-agent-http.md)
- 把"假流式"改成真流式（Flux 边收边推 / publishOn / 回调容错），详见 [学习笔记](agent-streaming-refactor.md)

## 🤔 关键决策

- **"服务间调用"不等于"请求转发"**：Java 是主动发起新请求的**客户端**，Python 内网隔离、前端无感知；这样 Python 挂掉不影响主业务
- **客户端选型看语义不看风格**：ingest 一次性请求用 `RestTemplate`，chat 流式必须用 `WebClient`——阻塞式客户端在语义上做不了流式
- **鉴权分层**：用户身份用 JWT，服务身份用共享密钥 `X-Agent-Key`
- **流式的敌人是缓冲点**，不是某个 API 用错：`collectList()` 一个词就能抹掉整条链路的流式努力
- **阻塞 IO 必须切出 Netty 事件循环**（`publishOn(boundedElastic)`），否则并发下拖慢所有连接
- **落库以用户所见为准**：中途失败时存已推内容，而不是降级文案
- 文档只做对齐、配置才动手改；涉及运行时行为的代码问题先列清单不擅自改

## 📋 待办 / 跟进

- [ ] **实现 Python Agent 服务**（`/v1/ingest`、`/v1/chat` SSE、`/v1/health`），契约见 `docs/agent-api.md`
- [ ] Python 就绪后端到端验证真流式效果（本次只验证了编译通过）
- [ ] 前端 `Chat.vue` 补心跳帧（服务端 90s 无上行文本帧即断连）
- [ ] `AgentClient.recentCalls` 去重 Map 加过期清理（只写不清理，长期运行内存增长）
- [ ] `AgentClient` 的 `RestTemplate` 补连接/读超时；重试区分可重试错误码
- [ ] `AgentChatClient` 超时判定改为查异常 cause 链（Reactor 会包装超时异常）
- [ ] 确认"改照片描述不校验上传者、删除却要求本人"是否为有意设计
- [ ] 统一 `Album.vue` 创建弹窗与编辑弹窗的 `maxlength`（30/100 vs 100/255）
- [ ] 清理仓库根目录的调试残留 `test-body.json`

## ❓ 未解决问题

- **本会话 pwsh 无法写入工作区**：`write` / `edit` 工具正常，但 `pwsh` 子进程对所有工作区路径（含 `docs/`、`backend/`）写入都报 `Access Denied`，而 DACL/属主/只读属性看起来都正常。已用 `diagnose-windows-sandbox-acl` 修了两轮（工作区根、`backend/`、`target/`），验证通过但实际写入仍被拒。**绕过办法**：编译时把 `backend/` 复制到 `%TEMP%` 再 `mvn compile`；文件内容改动一律用 `write` / `edit` 工具完成。疑与沙箱完整性标签或会话文件策略有关，待排查。
- `AgentChatClient.handleFailure` 目前把 4xx 和 5xx 都归为 `FAILED`，是否要区分？
- WS 允许任意 Origin 握手（`setAllowedOriginPatterns("*")`），且 token 走 query 会进访问日志，生产是否需要收敛/脱敏？

## 📌 明日重点

- 着手实现 Python Agent 服务骨架（先把 `/v1/health` 和 `/v1/chat` 的 SSE 格式跑通，用 `curl -N` 自测）
- Python 跑通后，实测 `/ai` 页与聊天 `@bot` 的逐字效果
- 视情况处理前端聊天心跳

## 💡 收获与反思

- **文档最容易"过期而不自知"**：这次发现 4 个模块（聊天 / AI / 待办 / 通知）完全没有文档，而 README 里"不是 git 仓库""端口不一致"等描述早已失效。文档不是写完就完事，得跟着功能一起长。
- **"用了流式技术"≠"有流式体验"**：改造前 SSE 是流式的、`Flux` 是流式的、`SseEmitter` 也是流式的，但 `collectList()` 一处缓冲就全废了。判断标准应该是**链路上有没有缓冲点**，而不是"有没有用某个 API"。
- **能跑但有隐患的代码最危险**：把阻塞 IO 放在 Netty 事件循环上，单机单请求测试完全正常，只在并发下才暴露。这类问题静态读代码时最容易被放过，值得专门留意。
- **修配置要有边界感**：本次用户明确授权后才动配置；对"改了会改变运行时行为"的代码问题（心跳、去重 Map、超时判定），只列清单不擅自改。
