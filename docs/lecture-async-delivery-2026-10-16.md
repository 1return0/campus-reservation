# 讲座异步预约 MVP 交付记录

> 历史记录：本文描述旧版序号/队首方案及当时测试，不是当前实现。
> 异步方案已全部撤回；当前见 [同步恢复记录](lecture-sync-restoration-2026-10-17.md)。

## 范围与保存

- 分支：`feature/lecture-async-reservations`；HEAD 仍为 `e1cf801debf6fa485bb7a1eb7297971468155f17`，未 commit、未 push。
- 开始前完整快照：`../tmp/campusreserve-before-lecture-async-20261016/`，含 HEAD zip、未提交 binary patch、修改及 untracked 文件 zip、SHA-256 清单。未包含 `.env`、缓存或大型构建产物。
- 第一轮幂等/错误码修复保留，研讨室 Mapper、服务及 N+1 优化与快照逐项 SHA-256 一致。研讨室没有异步化或再次重构。
- 已取消耗时 A/B 归因，保存了已完成的 A1/B1/C1；没有完成三轮，不据此下稳定性能结论。见 [停止记录](booking-attribution-stopped-2026-10-16.md)。

## 核心流程

Redis Lua 准入 → 短 MySQL 事务分配场次内序号、持久化幂等请求与原 Outbox 待投递事件 → 提交成功后 HTTP 202 + requestId/PROCESSING → 原可靠发布器 Confirm/Return → 独立命令队列 → 消费者按 MySQL 队首执行原预约事务 → 原预约单/容量/候补/通知 Outbox 与最终结果同事务提交 → 用户查询结果。

同场次顺序由独立序号行和数据库 PROCESSING 队首决定，不依赖消息到达顺序。受理事务不锁原 `resource_slot`。消费者仍使用原场次锁与条件扣减 SQL，和取消/补位、管理员维护互斥。重复消息仅唤醒队列，不产生重复预约。

接口、开关及回退步骤见 [实现说明](lecture-async-reservation.md)，逐文件清单见 [修改清单](benchmarks/lecture-async-changed-files-2026-10-16.json)。原同步入口仍在，默认未启用异步；切回同步前须停止新受理并排空 PROCESSING。

## 实际验证

- `mvn -q package` 成功：120 个测试项，118 通过、2 skipped、0 failure/error；四个服务 JAR 均完成打包。跳过项是 Docker Testcontainers 用例及显式 opt-in 的 live 用例，不冒充已运行。最后追加的服务单测覆盖开关不足、关闭准入后仍可幂等重放/跨场次 key 冲突、提交结果不确定时安全返回 500 而非 202/403。
- 单独开启 live 验证：真实 VM MySQL 8.0.45 / Redis / RabbitMQ，独立 schema、独立 MQ 拓扑；一个集成测试串行检查 13 组断言，全部通过。原始结果见 [JSON](benchmarks/lecture-async-live-2026-10-16.json)。
- 覆盖：8 个竞争 worker 的顺序与去重、20 个同键并发全部 202 且仅一笔请求/订单、他人查询 404、受理 Outbox 故障回滚并返回 500、消费事务注入异常后容量/订单/通知全部回滚、队首退避不放过后人、原取消 FIFO 递补、维护后 FAILED、持有原场次锁仍能受理、真实毒消息进入 DLQ、丢失唤醒扫描恢复、Broker Return 保留并恢复投递、Confirm 后未标记 Published 的租约过期重复投递。
- 原项目独立真实数据库安全回归：**12/13 通过，1 项失败**，不能宣称全量集成回归全绿。失败为 `maintenance-vs-new-booking-room`：维护返回 200，最终 11 笔预约均已取消、占用 bucket 全释放，但通知 Outbox 实际 13 条而非断言预期 22 条。讲座的同场景、双方幂等、取消 FIFO、数据库故障 500/回滚及跨房间 bucket 唯一性均通过。结果见 [原始 JSON](benchmarks/lecture-async-original-safety-2026-10-16.json)。
- 该失败涉及的研讨室服务、成员 Mapper、原通知 Outbox 写入实现未被本轮修改。代码检查显示维护方法仍采用默认 RR，锁房间之前已有普通读，之后以当前读查预约、再以普通读查成员快照；**疑似旧快照读不到并发提交的成员，漏写部分取消通知**。未做额外确定性复现，不把推断写成已确证根因；按“研讨室不重构”范围留待单独处理，不通过修改断言或重跑挑选通过结果掩盖。

### 本地受理性能（不是预约最终完成延迟）

| 场景 | 结果 |
| --- | --- |
| 原 resource_slot 行锁持续被占用时受理 | 32.41 ms，原锁在 HTTP 返回后才释放 |
| 500 个调用者同刻发起、同一场次，受理数 | 500/500 HTTP 202 |
| 受理 TPS | 70.56 |
| 受理 P50 / P95 / P99 | 4056 / 6578 / 6769 ms |
| 先积压 500 笔再启动两个消费者，检查全部最终结果 | 约 11.21 s（含手动 Outbox 投递循环；非纯消费者 TPS） |
| 最终预约与通知 Outbox | 前 20 个序号 CONFIRMED、后 480 个 WAITLISTED、容量 0、500 条原业务通知事件 |

为避免限流掩盖数据库瓶颈，仅测试中提升准入阈值；正式默认值未改。500 同刻连接的测试 Tomcat `accept-count=1000`、客户端连接超时 30 s；默认测试连接配置曾出现连接失败，已显式记录，不宣称默认部署也能无失败承接同样突发。100 并发/500 请求的先行功能运行也通过，但不是交替 A/B 对照，不报告相对提升比例。

## 已知限制

- 原预约锁已脱离 HTTP 路径，但短事务序号行及 MySQL 连接池仍会排队；500 突发 P95 仍约 6.58 秒，不能宣传为瞬时受理或生产 SLA。
- 这是 API 级 opt-in MVP；现有浏览器演示默认仍使用同步 API，没有静默修改 UI 合约。启用异步需使用新 POST 与轮询 GET。
- MQ 不提高单个场次的最终数据库处理上限。持久基础设施错误会保留 PROCESSING、退避恢复并记录安全日志；无请求取消/超时终止或自动修复坏数据能力。
- 本次 live 是 Reservation HTTP → 真 MySQL/Redis/Outbox/Rabbit → 消费事务，不是 Gateway/登录服务/Notification Consumer 的新全链路吞吐验证。
- 原同步安全回归另有上述研讨室维护通知数量失败，故本次不能视为整个系统的无遗留验收。
- 竞争消费者在同一 Reservation 进程内；未声称真实多 JVM 部署压测或真实 kill 进程/主机故障。崩溃窗口通过事务故障、暂停/恢复消费者、过期租约等受控场景验证。
- 所有本次创建的测试 schema 与独立 MQ 队列/交换机已清理；未切换正在运行的正式服务。
