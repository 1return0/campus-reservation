# Notification DLQ 验证记录（2026-10-16）

## 已执行的代码测试

根目录执行 `mvn -q test`，退出码 0。30 份本次生成的 Surefire 报告合计 **97 个测试：96 passed、1 skipped、0 failures、0 errors**。

跳过的是已有 `SlotCancellationMySqlTest`（本机 Docker/Testcontainers 条件未满足），不能记录为 passed。普通回归不包含显式 opt-in 的 `*LiveSmoke`。

新增 DLQ 代码测试共 18 项：

| 类 | 项数 | 主要边界 |
| --- | ---: | --- |
| `DeadLetterOperationsTest` | 13 | 预览不发布/不 ACK、字节与属性保留、Confirm 先于 ACK、NACK/Return/超时/发送失败保留原消息、ACK 不确定、毒消息/非持久消息/来源不符拒绝、有界扫描、重复 eventId 依靠 Inbox 去重 |
| `DeadLetterQueueMonitorTest` | 2 | 积压状态与 WARN、Broker 异常不使定时任务崩溃 |
| `DeadLetterRetryPolicyTest` | 2 | Spring AMQP stateless retry 适配器下格式错误只尝试 1 次、运行失败最多 3 次后拒绝 |
| `NotificationDlqCommandTest` | 1 | 缺少选择器/错误确认参数/超出扫描上限时连接 Broker 前拒绝 |

其中 Mockito 模拟的 NACK、确认超时、发送失败及 Inbox 重复键分支，不能替代 Broker 或数据库的真实故障验证。普通回归同时保留了原有通知 Service、接口响应及其他模块测试。

## 已执行的真实集成验证

显式开启 `CAMPUS_RESERVE_DLQ_LIVE_SMOKE=true`，执行：

```powershell
mvn -q -pl notification-service -am -Dtest=NotificationDlqLiveSmoke `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

`NotificationDlqLiveSmoke`：**3 passed、0 skipped、0 failures、0 errors**。使用现有 VM RabbitMQ 和 MySQL，不替换容器或数据库；每次测试使用随机 `campus.dlq-smoke.*` 交换机/队列和自己的 eventId。

1. **成功与重复重放**：先停隔离队列的 Listener，通过真实 `basic.reject(requeue=false)` 生成 Broker 的 x-death；检查积压与来源；dry-run 保留 DLQ；重放经过 positive Confirm 与无 Return 后移除原件；启动真实 `@RabbitListener`，落到真实 MySQL Inbox/通知。对相同正文和相同 eventId 再做一次，最终仍只有 1 条 Inbox 和 1 条通知。
2. **无路由不丢原件**：对隔离业务队列解绑，再重放已有合法死信，真实收到 mandatory Return；操作报 publish-unroutable，DLQ 仍有同一 SHA-256 原消息。
3. **真实毒消息处理**：无效 JSON 经真实业务交换机与 Listener 到 DLQ；x-death reason=rejected；人工重放工具拒绝它，原 DLQ 消息保留，Inbox 未写入。

测试还检查真实被动队列计数能触发 backlogAlert；结束清理并核对自己生成的 Inbox/通知为 0，删除隔离队列/交换机。没有消费、重放或 purge 既有业务 DLQ。

## 已执行的打包命令检查

- `mvn -q -pl notification-service -am -DskipTests package`：退出码 0（打包不是测试）。
- 打包 JAR 的 `deploy/ops/notification-dlq.ps1 -Action status`：真实默认 DLQ 返回 readyMessages=0、consumers=0、backlogAlert=false，退出码 0。这只是检查时刻的 ready 数，不是永久无死信证明。
- 缺少 eventId/SHA-256 的 `-Action replay -ConfirmReplay`：退出码 2，未执行发布或 ACK。
- PowerShell 语法检查、`git diff --check` 通过；凭据继续只在被忽略的本地配置中。

## 未验证 / 不可宣称

- 没有让共享 RabbitMQ 宕机、断电、重启或切换集群来验证恢复；
- 没有真实制造 Broker NACK、网络半断开、Confirm 丢失或 ACK 丢失，这些分支为代码模拟测试；
- 不宣称 exactly-once，发布与原 DLQ ACK 之间仍有可重复窗口；
- 不宣称增加了业务事件永久审计表、邮件/短信告警或毒消息自动修复；
- 未测试多个管理员同时扫描重放同一 DLQ，运维规程要求单操作员；
- 不证明 Publisher Confirm 等于通知已送达学生。

操作与回滚方式见 [运行手册](notification-dlq-runbook.md)。本任务不自动提交或推送 GitHub。

## 本任务文件清单

新增生产代码（均在 `notification-service/src/main/java/com/campusreserve/notification/`）：

- `config/DeadLetterMonitorConfiguration.java`
- `manager/mq/dlq/DeadLetterInspector.java`
- `manager/mq/dlq/DeadLetterOperations.java`
- `manager/mq/dlq/DeadLetterQueueMonitor.java`
- `manager/mq/dlq/LoggingRejectAndDontRequeueRecoverer.java`
- `manager/mq/dlq/NotificationDlqCommand.java`

修改现有代码/配置：

- `notification-service/src/main/java/com/campusreserve/notification/config/ReservationEventConsumerConfiguration.java`：只替换为脱敏且同样拒绝不 requeue 的 Recoverer。
- `notification-service/src/main/resources/application.yml`：可选监控及与工具一致的拓扑环境变量，原默认名不变。
- `deploy/.env.example`：可选配置示例，无真实凭据。

新增运维入口：`deploy/ops/notification-dlq.ps1`。

新增测试（均在 `notification-service/src/test/java/com/campusreserve/notification/`）：

- `manager/mq/dlq/DeadLetterOperationsTest.java`
- `manager/mq/dlq/DeadLetterQueueMonitorTest.java`
- `manager/mq/dlq/DeadLetterRetryPolicyTest.java`
- `manager/mq/dlq/NotificationDlqCommandTest.java`
- `NotificationDlqLiveSmoke.java`

新增文档：本验证记录与 `docs/notification-dlq-runbook.md`。

此前工作区已有的候补 FIFO 修改、预约压测脚本和报告属于前面的任务，保持原样，未与这次 DLQ 功能混为一项修改。
