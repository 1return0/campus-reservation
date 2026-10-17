# 讲座异步预约简化交付

> 已撤回：用户随后要求恢复同步预约。本文保留实验当时的结果，不代表当前代码。
> 当前状态见 [同步恢复记录](lecture-sync-restoration-2026-10-17.md)。

## 范围

仅简化讲座异步预约，不改研讨室、第一轮 fresh replay/错误码/N+1 修复，
不修改现有 Outbox 租约及 Notification Inbox 核心事务。未 commit、未 push。

当前工作分支：`feature/lecture-async-reservations`；修改基于 `33f8d3a`。

## 核心流程与删减

Lua 准入 → MySQL 请求 + 原 Outbox 同事务提交 → 202 → 原发布器
Confirm/Return → requestId 消息 → 消费 Inbox + 原预约事务 + 最终结果
同事务提交 → ACK → 用户按本人 requestId 查询。

保留请求表：它承担受理事实、用户/幂等键唯一性、结果查询，不承担消息去重。
新增消费者 Inbox：eventId 主键去重，与预约业务提交或回滚。
删除场次序号表、请求排序/退避字段、数据库队首调度、drain 循环及恢复扫描器。

不再保证“新请求严格按受理先后分配名额”；仍保留数据库防超卖、维护互斥、
原有取消候补 FIFO。有限消费重试耗尽进入 DLQ，需要人工修复后重放原消息；
不会把不确定的数据库提交错误直接标记 FAILED。

## 修改文件

- Entity/Mapper：`LectureReservationRequestEntity`、`LectureReservationRequestMapper`；
  新增 `LectureReservationInboxMapper`。
- 受理/命令：`LectureRequestPersistence`、`LectureRequestOutbox`；
  `LectureReservationWakeup` 替换为 `LectureReservationCommand`。
- 消费：`LectureReservationListener`、`LectureReservationWorker`、
  `LectureAsyncMessagingConfiguration`；删除 `LectureRequestRecoveryScheduler`。
- 迁移：新增 `V13__simplify_lecture_async_and_add_inbox.sql`，V12 保持不变；
  删除 application.yml 无用 recovery-delay 配置。
- 测试：更新 `LectureReservationListenerTest`、`LecturePublisherTest`、
  `LectureAsyncLiveTest`；新增 `LectureReservationWorkerTest`、`LectureRetryPolicyTest`。
- 文档：README、当前异步指南、课程游标、本报告/新版结果；历史报告添加过期提示。

## 真实验证

普通回归执行 `mvn -q test`，退出 0：127 项中 125 passed、2 skipped，
0 failures/errors。两项 skip 是显式开启的真实 MySQL/异步验证，不把 skip 当 passed。
其中异步真实验证随后通过下面的独立脚本单独执行。新增重试策略测试直接使用
实际配置的 interceptor，确认毒消息解析 1 次、基础设施异常执行 3 次后拒绝。

执行 `deploy/benchmark/lecture-async-live.ps1`，使用
`campus_audit_7acc8e2cb9ae` 专用 MySQL 库与同名随机后缀 MQ 拓扑。
Flyway V1–V13 在独立空库升级成功。脚本退出 0，专用数据库/队列已清理，
未升级本地应用原数据库。旧库有未完成请求时的升级阻断没有单独集成演练。

通过：20 个并发同幂等键请求均 202 且一笔订单、并发重复消费、
不同 eventId 对已完成请求不重复预约、请求所有权查询隔离、受理 Outbox
异常返回 500 并回滚、消费数据库异常整体回滚、关闭场次 FAILED、取消递补、
有限重试 DLQ 与人工重放、真实 Return 恢复、发布确认后未标记成功的重复投递窗口。

500 并发（仅本轮测试提高准入阈值，不改变正式配置）：

| 指标 | 实测 |
| --- | --- |
| HTTP 受理 | 500 个请求均 202 |
| HTTP 受理 TPS | 246.97 |
| HTTP P50 / P95 / P99 | 1117.92 / 1691.96 / 1748.22 ms |
| 最终结果 | 20 CONFIRMED、480 WAITLISTED、余量 0 |
| 持久化消费/通知 | 500 Inbox、500 预约结果通知 Outbox |
| 消费启动后处理完 500 笔 | 13933.48 ms，包含 Outbox 发布与消费 |
| 原场次行锁被占用时受理耗时 | 25.67 ms |

原始结构化摘要见 [实测结果](benchmarks/lecture-async-simplified-live-2026-10-17.json)。
这不是多轮同条件 A/B，不据此宣称稳定提升百分比。HTTP 202 耗时不等于预约完成耗时。
没有验证 Gateway 或 Notification Consumer 的吞吐、多应用实例、实际杀进程/宕机恢复。
没有重跑原完整真实安全套件；历史研讨室维护通知数量失败仍见旧报告，未改动或掩盖。

## 升级与回退

必须停旧版异步入口，保留旧消费者运行，先排空 PROCESSING、未发布命令及
Broker 上旧命令（含 DLQ/unacked），再执行 V13 和部署新版。V13 在业务 DDL
前检查数据库待办，不能替代 Broker 检查。保留历史请求/订单，删除的仅是过时
排队调度字段及序号表；需要常规备份与 Flyway 故障处理，不直接部署旧 V12 二进制。

运行方式回退：停止新异步受理、排空新版待办，关闭异步开关，使用保留的同步预约
接口。不删除新表、历史记录，不自动 reset 或 push。

## 学习游标

仍为补充第 3 课，不新增天数、不覆盖原四周游标。按简化版重新讲解，等待三题回答。
