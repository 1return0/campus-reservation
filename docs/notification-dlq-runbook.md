# Notification DLQ：只读检查与人工单条重放

## 现有链路审计与改动边界

原链路未变：`reservation.#` → `campus.notification.reservation.v1` → Consumer → Inbox/通知同事务 → AUTO ACK。

- 现有 stateless retry 最多尝试 3 次（含首次）；失败间隔按 1 秒起步、2 倍退避，最大间隔 5 秒。
- `JsonProcessingException` / 参数契约错误转换成 `AmqpRejectAndDontRequeueException`，不进行本地重试。
- 耗尽重试后仍拒绝且不 requeue，由原业务队列的 DLX 配置路由到 `campus.notification.reservation.dlq`；没有自动 DLQ Consumer 或 DLQ → 业务队列的循环绑定。
- Inbox 的 `event_id` 唯一约束及通知写入事务未改；相同事件再次消费正常结束，不重复创建通知。
- 只把原 Recoverer 换成等效的脱敏拒绝实现，添加安全的 eventId、eventType、失败分类、异常类型和尝试次数日志，不输出消息正文或底层异常文本。
- 监控与 CLI 不创建/删除原有拓扑，不修改 Outbox、Inbox、数据库迁移或正常队列绑定。

## 构建与权限

Java 17，从仓库根目录执行：

```powershell
mvn -pl notification-service -am -DskipTests package
```

CLI 运行打包 JAR 内的独立 main，不启动 Spring Boot Web、数据库、Consumer 或定时任务，不需要数据库凭据。复用项目已有 Spring AMQP/Rabbit Java Client/Jackson 依赖，无新增 Maven 依赖。

它是**本地管理员运维工具，不是新增公开接口**：执行权限由主机账户、部署凭据与 RabbitMQ vhost 权限控制，不使用浏览器 JWT。只读凭据至少需要检查/读取目标 DLQ 的权限；重放凭据需要读取 DLQ、向业务交换机发布，以及 Broker 对 passive declare 要求的权限。不要向普通学生发放运维凭据。

PowerShell 脚本从 Git 忽略的 `deploy/.env` 读取 RabbitMQ/相关拓扑变量，进程环境变量优先。密码不放命令行参数，也不输出；Linux/容器可直接使用后文 Java 命令与部署已有环境变量。

## 查看计数与告警

```powershell
$env:JAVA_HOME = '你的 JDK 17 安装目录'
powershell -NoProfile -File deploy/ops/notification-dlq.ps1
```

默认 action 为 `status`，只做被动队列检查，不声明新队列、不取消息。输出字段：

- `readyMessages`：可领取消息数；**不包含 unacknowledged，不能冒称总数**。
- `consumers`：消费者数量；本方案不注册 DLQ 消费者，人工 basic.get 不计为持续消费者。
- `backlogAlert`：是否达到阈值。非零积压返回退出码 3，便于现有脚本/任务计划告警。

准确的 `messages`、`messages_ready`、`messages_unacknowledged` 可查看已有 RabbitMQ Management UI 的 Queues 页面；CLI 不需要另配管理 HTTP 凭据。

通知服务持续日志告警为可选功能，默认关闭：

```properties
NOTIFICATION_DLQ_MONITOR_ENABLED=true
NOTIFICATION_DLQ_MONITOR_INTERVAL_MILLIS=60000
NOTIFICATION_DLQ_ALERT_THRESHOLD=1
```

重启 notification-service 后生效。积压时每个检查周期输出 `notification-dlq-backlog` WARN；Broker 不可用或队列不存在则输出 `notification-dlq-monitor-unavailable`，不把缺失误报成 0，也不使业务线程等待或变更健康探针。它是日志告警信号，不是自动短信/邮件系统。

## 查看脱敏消息元数据

```powershell
powershell -NoProfile -File deploy/ops/notification-dlq.ps1 -Action inspect -Limit 10
```

最多检查 100 条。输出正文 SHA-256、合法 UUID eventId、已知 eventType、可否重放/契约错误码、redelivered、`x-death` 原因和次数、是否来自配置的业务队列。**不输出正文、接收人、预约编号、任意错误 header 或任意堆栈。** 非法标识显示 unknown，不将其原样写日志。

`x-death.count` 是 Broker 死信次数，不是 Spring 的本地调用次数。现有历史消息没有持久化底层异常或本地重试次数时，工具显示 unknown，不能推测成 3；新日志可按 eventId 对照 `invalid-message`（1 次）或 `consumer-retries-exhausted`（配置的最大次数）及 failureType。

AMQP 没有真正的无副作用“浏览消息”接口：inspect 使用专用 Channel 的 `basic.get(autoAck=false)`，暂时持有有界数量的消息，关闭 Channel 后归还。**不发布、不 ACK、不删除**，但可能改变 redelivered 标记或队列顺序；检查期间 ready 数可能短暂下降。不要让多个管理员同时检查/重放同一 DLQ。

## 指定单条重放：先预览，再确认

先用 inspect 结果的真实 eventId 与 sha256 填入以下占位符。两个选择器一起匹配，防止重放相同 ID 的不同内容。

```powershell
# 默认预览：不发布、不 ACK
powershell -NoProfile -File deploy/ops/notification-dlq.ps1 -Action replay `
  -EventId '<eventId>' -Sha256 '<sha256>' -Limit 10

# 修复根因、核对预览后，明确确认才执行
powershell -NoProfile -File deploy/ops/notification-dlq.ps1 -Action replay `
  -EventId '<eventId>' -Sha256 '<sha256>' -Limit 10 -ConfirmReplay
```

重放只扫描前 Limit 条，暂存未匹配消息，结束时全部归还；找不到返回 not-found 或 scan-limit-reached，绝不自动无限扫描/重放。需扫描更深时可人工增加 Limit（上限 100），而不是通过脚本持续循环。

只有以下条件全部满足才发布：已知 v1 消息结构与必要字段、正文 UUID 与 header eventId 一致、存在的 eventType header 与正文一致、原消息为 persistent（deliveryMode=2）、`x-death` 来自配置的业务队列和交换机，且原路由键与 `reservation.<eventType 小写>` 相符。未知版本、格式错误、非持久消息、缺少/不符的来源都拒绝重放，保留人工检查。

发布使用**原始正文 bytes 和原始 AMQP properties**，不重建 JSON、不换 eventId、不带新幂等键，也不把 DLQ 路由键 `reservation.invalid` 当成业务路由键。

```text
独立读取 Channel：basic.get(autoAck=false)，保留原 DLQ 消息
  → 独立发布 Channel：confirm.select + mandatory=true + ReturnListener
  → 向原配置的业务 Exchange / canonical routing key 发布原消息
  → 等待 positive Publisher Confirm，检查没有 Basic.Return
  → basic.ack(原 deliveryTag, multiple=false)
  → 关闭读取 Channel，把其他暂存消息归还
```

- NACK、Return、超时、中断、连接失败或结果不确定：不 ACK 原消息，关闭 Channel 后重新入 DLQ；不自动补发。
- 发布成功后、原消息 ACK 前崩溃：允许业务队列与 DLQ 各有一份；下一次人工重放可能重复，由原 Inbox 唯一键保证通知不重复。
- 原 ACK 的结果不确定：报告 `source-ack-uncertain-published-copy-exists`。发布副本已获确认；原消息可能仍在 DLQ，先检查，不盲目循环。
- Confirm + 无 Return 说明 Broker 接受并路由到至少一个队列，**不代表 Consumer 已完成或通知已生成**。本工具不强制重声明/修复缺失绑定；即使交换机存在其他绑定也不保证目标 Consumer 正常，需核对拓扑与通知结果。

主要退出码：0 成功/预览、2 参数/配置错误、3 status 积压告警、4 找不到/非法消息拒绝、5 发布失败/连接或结果不确定。失败输出仅使用固定错误码。

### Linux / 容器等效命令

已有 RABBITMQ_HOST/PORT/USERNAME/PASSWORD/VIRTUAL_HOST 环境变量时：

```bash
java -Dloader.main=com.campusreserve.notification.manager.mq.dlq.NotificationDlqCommand \
  -cp notification-service/target/notification-service-0.1.0-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher inspect --limit 10
```

重放对应参数为 `replay --event-id '<eventId>' --sha256 '<sha256>' --limit 10`，最后明确加 `--confirm-replay` 才发布。不要把凭据写进 shell 历史。

### 非默认拓扑

服务与工具使用一致的 `RESERVATION_EVENT_EXCHANGE`、`NOTIFICATION_SOURCE_QUEUE`、`NOTIFICATION_DLQ`。它们的默认值与原来完全一致；已有直接设置 `campus-reserve.messaging.*` 的部署应为运维命令提供对应值，避免指向不同队列。工具不会自动查找其他 vhost 或任意队列。

## 毒消息的人工处理边界

本工具不会自动修正文档、丢弃消息、重置 eventId、修改原消息正文或强行跳过校验。格式损坏、header/body ID 不一致、未知 schema 等消息保留在 DLQ，优先按 eventId/hash 和安全日志定位生产端/数据原因。

确需查看正文时，由具备授权的管理员通过受限 RabbitMQ Management UI 使用 requeue 模式检查，禁止把 payload、截图、凭据贴入公开日志或 Git。修正业务来源/契约后再评审一次性数据修复步骤；**本版本不提供毒消息原地编辑与“修复后自动清除原件”**，不能用普通重放来掩盖内容错误。原消息继续保留，避免形成自动死信循环。

## 验证命令与边界

代码测试：

```powershell
mvn -pl notification-service -am test
```

真实 Broker/MySQL 测试必须显式开启，并从根目录加载已有 deploy/.env 配置到进程环境：

```powershell
$env:CAMPUS_RESERVE_DLQ_LIVE_SMOKE = 'true'
mvn -pl notification-service -am -Dtest=NotificationDlqLiveSmoke `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

该测试只用随机 `campus.dlq-smoke.*` 拓扑和自己的 UUID 数据，结束清理自己的队列、交换机、Inbox 与通知，不读取/重放生产 DLQ。Publisher 超时/NACK/连接异常的确定性分支由代码测试覆盖；真实 Broker 用撤销隔离队列绑定验证 mandatory Return 不移除原件，不通过关闭共享 Broker 制造故障。

实际执行记录见 [DLQ 验证记录](notification-dlq-verification-2026-10-16.md)。

## 回滚

1. 停止人工运维命令，将 `NOTIFICATION_DLQ_MONITOR_ENABLED=false` 并重启通知服务，即可关闭可选定时监控。未 ACK 的运维读取会随 Channel/连接关闭归还，Broker 检测断连可能需要心跳时间。
2. 需要完整代码回滚时部署本次功能之前的 notification-service JAR；本改动没有数据库迁移，也没有改变队列参数/绑定，不删除或重建 DLQ。
3. 移除部署中的可选监控参数即可；不需要回滚其他服务或此前候补 FIFO 改动。
4. 已成功重放的业务消息不能撤销。保留 Inbox 幂等记录与既有 DLQ 消息，不删表、不 purge 队列；重复副本由原消费事务处理。

协议依据：[RabbitMQ Confirm/ACK](https://www.rabbitmq.com/docs/confirms)、[死信与 x-death](https://www.rabbitmq.com/docs/dlx)、[可靠性与重复投递](https://www.rabbitmq.com/docs/reliability)。
