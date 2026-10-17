# 恢复同步讲座预约（2026-10-17）

## 恢复范围

按用户要求撤回两版讲座异步预约，回到原同步业务链路，**保留第一轮正确性
修复与研讨室 N+1 优化**，不是把整个仓库硬重置到旧 commit。
恢复验证完成时尚未 commit/push，基于 `33f8d3a` 在工作区完成。
随后用户授权发布：将恢复内容作为新提交推送至 `main` 与原功能分支，
不 reset、不 force-push、不改写既有 Git 历史。

当前：`POST /api/v1/slots/{slotId}/reservations` → STUDENT 授权 → 幂等查询 →
Redis 准入 → 原场次 FOR UPDATE → fresh replay → 资格/开放校验 → 创建预约 →
MySQL 条件 UPDATE 容量 → CONFIRMED/WAITLISTED → 通知 Outbox 同事务提交 →
HTTP 200 直接返回结果。只对预约结果通知使用原 RabbitMQ/Notification Inbox。

没有 202 预约受理、requestId 轮询、预约命令队列、预约消费者身份或预约 Inbox。
原通知 DLQ 人工处理、Outbox 发布租约、Confirm/Return、通知 Inbox 均保留。

## 撤除与保留

- 撤除异步专用 Controller、Service、Entity/Mapper、DTO、命令契约、
  请求 Outbox 封装、消费者/恢复调度、消息拓扑、配置和专用测试/runner。
- `reserve()` 直接执行同步事务，删除 `reserveAccepted()` 与异步开关分支。
- Publisher、Gateway 预约路由、事件枚举和环境变量示例恢复异步前版本。
- 删除实验 V12/V13 迁移；V1–V11 保持原样，不创建闲置的实验表。
- 保留 READ_COMMITTED、锁后 fresh replay、DuplicateKey 后 fresh replay、
  数据库异常 500 映射；研讨室及其批量候补 Mapper 与恢复前 HEAD 无差异。
- 历史性能报告和结果保留并标明撤回，不将实验指标宣称为当前系统性能。
- 异步补充第 3 课取消，不要求继续答题；原四周与前两课游标不覆盖。

## 数据库安全与快照

只读检查当前开发库：Flyway 最新版本 **11**，V12/V13 历史记录数 **0**，
异步请求表/Inbox 均不存在，所有讲座请求命令 Outbox 数 **0**。
因此无需排空、迁移或删表。没有修改原数据库数据、RabbitMQ 配置或私密 `.env`。
已经执行 V12/V13 的其他环境不能直接使用此恢复版本，应另行处理迁移历史；
本次没有发现或操作这些环境。

恢复前完整代码快照在仓库外：
`D:/Study_Imformation/project/tmp/campusreserve-before-sync-restore-20261017/`。
含 HEAD ZIP、未提交 binary patch、未跟踪源码 ZIP 与路径清单；不含凭据和大型构建物。
撤除的异步源码可从快照或已存在的 Git 历史找回，不是不可恢复删除。

## 验证

`mvn -q clean package` 退出 0：**106 passed、1 skipped、0 failures/errors**；
四个服务 JAR 打包成功。clean 避免已删除异步源码的旧 class 残留。
跳过的是 Docker Testcontainers MySQL 用例，不当作通过。

独立真实 Gateway/JWT/MySQL 小规模 pilot：执行
`deploy/benchmark/booking-performance-audit.ps1 -Mode pilot`，退出 0，
`BookingPerformanceAuditLive` 1 项通过；非 Mock。

| 场景 | 请求 / 最大并发 | 实际 HTTP 结果 | 落库核对 |
| --- | --- | --- | --- |
| 讲座 | 30 / 1 | 20 CONFIRMED、10 WAITLISTED，均 200 | 30 订单、30 通知 Outbox、容量无负数 |
| 讲座 | 30 / 10 | 20 CONFIRMED、10 WAITLISTED，均 200 | 30 订单、30 通知 Outbox、容量无负数 |
| 研讨室 | 30 / 1 | 1 CONFIRMED、29 WAITLISTED，均 200 | 30 预约、30 Outbox、无确认区间重叠 |
| 研讨室 | 30 / 10 | 1 CONFIRMED、29 WAITLISTED，均 200 | 30 预约、30 Outbox、无确认区间重叠 |

以上 120 个正式请求，另有 20 个讲座预热请求不计入正式场景。
专用库 `campus_audit_638d9b659d21` 已由脚本清理，临时 Gateway 已停止。
原始摘要在本地忽略目录 `target/booking-audit-638d9b659d21/summary.json`，
日志在上述仓库外快照目录 `sync-live.log`。
该验证关闭消息投递与 Redis 准入，只证明选定同步 HTTP/MySQL/Outbox 落库链路；
不声称本轮重新验证 RabbitMQ 消费、Redis 压测或历史完整安全套件。
不重新开展多轮 A/B 归因，不把旧版同步性能数据当成本轮重测值。
