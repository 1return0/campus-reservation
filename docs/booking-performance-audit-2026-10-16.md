# CampusReserve 预约写链路性能审计（2026-10-16）

## 范围与结论

基线：`e1cf801debf6fa485bb7a1eb7297971468155f17`，已推送 `origin/main`。该提交包含先前完成的 DLQ 人工处理、讲座候补 FIFO 修正及 500 请求压测材料。本次性能审计未修改正式业务源码、Mapper、迁移、依赖或正式配置，未实施性能优化。

实际发现，按场景区分优先级：

1. **热点预约的主要吞吐约束是同一资源的持锁事务串行执行**。大量请求先排连接池，再等资源行锁；不能把数秒 HTTP 延迟全部解释为 MySQL 行锁等待。讲座 500 并发批次 62.73 HTTP TPS，单房间研讨室 40.04 HTTP TPS；12 房间分散请求、100 并发为 149.22 HTTP TPS。
2. **复杂候补队列中，`promoteWaiting()` 出现真实查询放大，显著延长房间持锁时间**。500 条成员冲突候补，单次扫描约 1003 条实际 SQL，扫描 P95 722ms。200 条成员冲突候补、10 并发时，扫描约 404 SQL/次，吞吐只有 3.15 HTTP TPS。
3. **相同区间候补不是同样的 SQL N+1**。MyBatis 一级缓存使大部分重复查询不回源，但 Java 仍遍历全部候补并重复构造查询。不能用这个最容易命中缓存的样本代表所有候补。
4. **同键并发重放的接口语义存在缺陷**：不重复落库，但部分请求返回 409；另有数据库异常被外层呈现为 403 的问题。两者是待修复发现，不是已经完成的优化。

## 隔离环境与统计口径

- 独立随机库 `campus_audit_f0d1fdbcb858`，真实 Flyway 建表；补跑一致性检查使用另一个随机审计库。每次脚本仅删除自己创建的库，清理记录在相应 `target/booking-audit-*/environment.json`。
- Windows Java 17，Gateway `127.0.0.1:18600` → 独立 Reservation JVM `127.0.0.1:18602`；服务堆上限各 512MiB；MySQL 8.0.45、REPEATABLE READ、Hikari 默认 10 个连接，主要并发批次实际观察到 10 个 JDBC connection ID。
- 数据库位于既有共享虚拟机。**隔离了库、数据、应用 JVM 和端口，没有独占物理 CPU、磁盘或数据库实例**。
- Redis 缓存、两种预约限流、Nacos、Outbox 投递、资源生命周期及自动爽约调度均关闭；不使用“60 秒仅放行 20 次”过滤测试流量。所有性能基线的限流拒绝数均为 0。
- 使用合成用户、有效签名 JWT、预置已接受的队伍成员。测到了 HTTP Gateway、两端 JWT 校验、应用业务、MySQL 事务及 Outbox 落库；**不包含真实注册登录、Outbox Scheduler、RabbitMQ 投递和通知消费的耗时**，不是新一轮消息全链路延迟验收。
- 每个分级场景 500 请求，最大在途数依次 1/10/50/100/500；同一讲座容量 20，同一研讨室请求同一时间区间。另测 12 房间分散请求。闭环客户端，不是持续开放到达率或稳态长时间压测。
- TPS = 批次完成 HTTP 请求数 / 批次墙钟秒数，**包含合法的 WAITLISTED，不是成功占座 TPS**。另保留 CONFIRMED / WAITLISTED / 错误的分组统计。
- 性能主体共 24 个场景、5566 请求：11 个常规批次共 5500，12 个候补深度小实验共 36，成员冲突并发对照 30。预热 20 请求及幂等检查另计。
- 每场景只跑一轮，顺序固定。JIT、共享环境及探针有开销，没有完成无探针对照或多轮置信区间；不要把相邻并发档位的 TPS 差异宣称为稳定提升。

### 监测方式及边界

所有监测都位于 `reservation-service/src/test/java/com/campusreserve/reservation/audit/`，仅显式启用的测试 JVM 存在，生产 JAR 不包含这些类及接口。

- ByteBuddy 内存 Advice：`reserve`、`book`、私有 `requireRoom` / `promoteWaiting`、Hikari `getConnection`、Spring `doBegin` / `doCommit` / `doRollback`。
- MyBatis 插件：Executor 查询调用为**逻辑 SELECT 数**；StatementHandler query/update 为**实际 SQL 执行数**，包括写入。两者口径不同，不能直接相减解释缓存命中率。
- SQL 执行时间为 JDBC 执行及结果映射的客户端墙钟时间，不是独立的 MySQL CPU 时间；包含网络、阻塞和调度。
- `transactionToCommitMillis` 从 `doBegin` 进入起算，**包含连接池等待**。离线分析逐请求减去该请求 `doBegin` 耗时，得到 `transactionAfterBeginMillis`，不通过相减两个 P95 估算。
- `acquiredLockToCommitMillis` 从主资源锁查询返回至 `doCommit` 成功返回；包含应用处理、SQL、提交 RPC，近似客户端观察到的持锁临界区，不是精确的服务端释放锁瞬间。
- 独立采样连接每约 20ms 查询 `performance_schema.data_lock_waits`，仅筛本审计库。未修改共享实例的全局监测开关。关联线程、请求连接、表和索引；采样有遗漏，当前语句 TIMER_WAIT 也不能当作纯行锁等待。
- 另报告热点主键查询首次至末次观察到阻塞的采样跨度，**不是每个请求精确的纯锁等待 P95**；单行热点查询场景下可辅助确认等待量级。
- 异常请求的最终 HTTP 状态以客户端为准。测试过滤器可能在容器 ERROR dispatch 前结束，不能把那时的 wrapped response 状态当作最终状态；回滚用 `doRollback`、失败 INSERT 和数据库残留检查相互印证。

MySQL 官方资料：[data_lock_waits](https://dev.mysql.com/doc/mysql-perfschema-excerpt/8.0/en/performance-schema-data-lock-waits-table.html)、[InnoDB 锁等待观测](https://dev.mysql.com/doc/refman/8.0/en/innodb-information-schema-understanding-innodb-locking.html)。没有把 statement `LOCK_TIME` 冒充精确 InnoDB 行锁计时。

## 不限流分级结果

耗时均为毫秒，分位数 nearest-rank。

| 场景 | 最大在途 | HTTP TPS | HTTP P50 | P95 | P99 |
|---|---:|---:|---:|---:|---:|
| 同讲座 | 1 | 29.40 | 30.51 | 58.60 | 80.86 |
| 同讲座 | 10 | 61.42 | 155.08 | 209.40 | 361.61 |
| 同讲座 | 50 | 79.84 | 612.56 | 680.98 | 691.46 |
| 同讲座 | 100 | 64.42 | 1449.89 | 1804.22 | 1814.79 |
| 同讲座 | 500 | 62.73 | 3909.04 | 7285.49 | 7750.14 |
| 同研讨室 | 1 | 27.10 | 34.28 | 54.37 | 72.34 |
| 同研讨室 | 10 | 41.59 | 246.37 | 292.41 | 319.33 |
| 同研讨室 | 50 | 45.44 | 1128.10 | 1262.83 | 1273.07 |
| 同研讨室 | 100 | 39.16 | 2200.86 | 3108.28 | 3193.72 |
| 同研讨室 | 500 | 40.04 | 5062.26 | 11737.27 | 12306.86 |
| 12 研讨室 | 100 | 149.22 | 627.83 | 708.91 | 757.29 |

每个讲座批次 20 CONFIRMED + 480 WAITLISTED；每个单房间批次 1 CONFIRMED + 499 WAITLISTED；12 房间为 12 CONFIRMED + 488 WAITLISTED。上述批次没有 429、503 或传输失败。每个批次新增 500 预约、500 Outbox，未发现容量负数或房间已确认预约重叠。

### 500 并发阶段拆分

| 场景/阶段 | P50 | P95 | P99 |
|---|---:|---:|---:|
| 讲座：连接池获取连接 | 2620.41 | 2949.09 | 3053.58 |
| 讲座：`lockById` JDBC 调用 | 126.36 | 205.94 | 221.88 |
| 讲座：取得锁至提交返回 | 14.11 | 23.53 | 29.88 |
| 讲座：排除 doBegin 后的事务 | 142.15 | 231.78 | 247.18 |
| 讲座：Commit RPC | 2.05 | 3.63 | 4.81 |
| 研讨室：连接池获取连接 | 3603.41 | 5849.13 | 6021.26 |
| 研讨室：资源 `lockById` JDBC 调用 | 186.75 | 310.83 | 338.90 |
| 研讨室：取得锁至提交返回 | 22.08 | 36.35 | 40.67 |
| 研讨室：排除 doBegin 后的事务 | 209.63 | 348.36 | 377.87 |
| 研讨室：Commit RPC | 1.90 | 3.11 | 3.96 |

`requireRoom()` 整体 P95 为 311.29ms，与内部锁查询 310.83ms 接近。当前同区间队列的 `promoteWaiting()` P50/P95/P99 = 6.90/16.78/18.85ms。

MySQL 确认阻塞对象分别为 `resource_slot/PRIMARY` 和 `reservable_resource/PRIMARY`，均最多同时观测到 9 个等待连接。首次至末次阻塞采样跨度 P50/P95/P99：讲座 96.59/176.28/195.58ms，研讨室 134.77/237.74/271.46ms；仅统计多次采样捕获的语句，不能冒充全部请求分布。

解释：10 个连接中一个执行热点临界区，其他连接等待该资源。更多请求积压在借连接、Servlet/Gateway 等上游位置。没有做精确的上游排队分解，也不能把客户端 P95 减服务端 P95 当作这一段排队时间。增加连接池不会增加同一行锁的持有者数量，可能只扩大等待队伍。

### 按业务结果区分（500 并发）

| 场景 | 结果 | 数量 | HTTP P50 / P95 / P99 | 实际 SQL/请求 |
|---|---|---:|---|---:|
| 讲座 | CONFIRMED | 20 | 298.04 / 402.05 / 402.15 | 8 |
| 讲座 | WAITLISTED | 480 | 4040.29 / 7304.34 / 7771.78 | 10 |
| 研讨室 | CONFIRMED | 1 | 273.23 / 273.23 / 273.23 | 19 |
| 研讨室 | WAITLISTED | 499 | 5083.26 / 11761.77 / 12346.47 | 13 |
| 所有上述批次 | 限流拒绝 | 0 | 不适用 | 不适用 |

只有 1 个确认样本的分位数仅为该样本值，不能宣称为稳定性能。确认请求通常先完成、后续请求进入候补，不能据此断言状态字段本身造成全部延迟差异。

## 候补增长实验

同一新请求仍走正常 `book()`。种入 0/50/200/500 条旧候补，每档 3 次请求，单线程；各档重置独立测试数据。扫描总数略大于预置数是因为前一个测试请求本身可能进入候补。

- 同区间：房间已被占用，候补都申请 14:00–16:00。
- 不同区间：合法 30 分钟刻度、60–240 分钟长度，既有确认预约覆盖开放时间；多个候补区间不同，部分重复。
- 成员冲突：目标房间 14:00–16:00 空闲，但这些候补成员已在其他房间该时段有有效预约及 bucket；新请求使用 08:00–09:00。关联队伍、成员快照、bucket 均在测试库构造，不修改真实用户数据。

| 候补类型 | 预置数 | 扫描条数均值 | 逻辑 SELECT/扫描 | 实际 SQL/扫描 | 扫描 P50 / P95 / P99 (ms) |
|---|---:|---:|---:|---:|---|
| 同区间 | 0 | 1 | 3 | 1.67 | 1.68 / 1.83 / 1.83 |
| 同区间 | 50 | 51 | 103 | 2 | 2.53 / 2.64 / 2.64 |
| 同区间 | 200 | 201 | 403 | 2 | 9.40 / 10.05 / 10.05 |
| 同区间 | 500 | 501 | 1003 | 2 | 13.27 / 14.58 / 14.58 |
| 不同区间 | 0 | 1 | 3 | 1.67 | 1.84 / 2.11 / 2.11 |
| 不同区间 | 50 | 51 | 103 | 101.67 | 75.97 / 76.59 / 76.59 |
| 不同区间 | 200 | 201 | 403 | 330 | 328.55 / 350.77 / 350.77 |
| 不同区间 | 500 | 501 | 1003 | 330 | 349.56 / 353.90 / 353.90 |
| 成员冲突 | 0 | 0.33 | 1.67 | 1.33 | 1.39 / 1.57 / 1.57 |
| 成员冲突 | 50 | 50.33 | 201.67 | 103.33 | 67.67 / 70.27 / 70.27 |
| 成员冲突 | 200 | 200.33 | 801.67 | 403.33 | 366.19 / 399.35 / 399.35 |
| 成员冲突 | 500 | 500.33 | 2001.67 | 1003.33 | 721.21 / 722.18 / 722.18 |

小样本三次的 P95/P99 等于最大值，主要用于结构性诊断，不是 SLA 指标。不同区间 200→500 实际 SQL 持平，是本夹具的区间组合重复、MyBatis 缓存复用造成，**不是所有真实队列都会在 330 条 SQL 封顶**。

每个新事务都会重新获取队列并遍历。单房间 500 新请求，从空队列到 499 候补，累计扫描 **124251 条候补**（每批次均可从原始样本求和）。相同区间使 SQL 回源被压低，但全队列结果映射、循环、Mapper 调用仍增长。

额外验证：200 条成员冲突候补、30 新请求、10 并发：

- 1 CONFIRMED + 29 WAITLISTED，HTTP TPS **3.15**，HTTP P50/P95/P99 **3142.02/3308.77/3321.11ms**。
- 每次补位实际 SQL 平均 **403.93**；扫描 P95 **369.71ms**；取得房间锁至提交 P95 **379.73ms**。
- 房间锁查询 P95 **2966.73ms**，连接池等待 P95 仅 **3.07ms**；此时瓶颈明确转为持锁扫描，而不是缺少连接。
- 30 请求中，成员快照 SELECT 共 **6061 次**，成员冲突 COUNT 共 **6001 次**；各自单次 P95 仅约 **1.25ms**，但重复执行约 200 次/请求，累计形成主要开销。

## 主要 SQL（500 并发同资源批次）

均为客户端实际执行分布，不包含命中 MyBatis 本地缓存的查询。

| 场景 / Mapper | 次数 | 执行 P50 / P95 / P99 (ms) |
|---|---:|---|
| 讲座 `ResourceSlotMapper.lockById` | 500 | 126.36 / 205.94 / 221.88 |
| 讲座 `ReservationOrderMapper.selectList` | 1000 | 0.89 / 1.71 / 3.46 |
| 讲座 `ReservationOrderMapper.insert` | 500 | 1.31 / 2.10 / 3.85 |
| 讲座 `ResourceSlotMapper.tryConsumeCapacity` | 500 | 1.17 / 1.93 / 3.31 |
| 讲座 `ReservationWaitlistEntryMapper.countWaitingPosition` | 480 | 1.21 / 2.35 / 3.15 |
| 讲座 `ReservationOutboxEventMapper.insert` | 500 | 1.16 / 1.94 / 3.26 |
| 研讨室 `ReservableResourceMapper.lockById` | 500 | 186.75 / 310.83 / 338.90 |
| 研讨室 `StudyRoomBookingMapper.waiting` | 500 | 4.34 / 10.91 / 12.94 |
| 研讨室 `StudyRoomOpenWindowMapper.containing` | 500 | 0.86 / 1.45 / 1.96 |
| 研讨室 `StudyRoomBookingMapper.findConfirmedOverlapForUpdate` | 500 | 0.87 / 1.69 / 2.23 |
| 研讨室 `StudyRoomBookingMemberMapper.selectList` | 1001 | 0.72 / 1.18 / 1.84 |
| 研讨室 `StudyRoomBookingMapper.insert` | 500 | 1.34 / 2.29 / 3.62 |
| 研讨室 `ReservationOutboxEventMapper.insert` | 500 | 1.11 / 1.82 / 2.40 |

当前没有证据说明某个非锁 SQL 本身是秒级慢查询。主要问题是热点串行、队列等待，以及复杂候补下大量本来很短的 SQL 被重复执行。完整每条 Mapper 的分布及业务结果分组在 JSON 附件。

## 正确性审计及优化边界

### 已验证与发现

- 讲座取消确认单后，最早候补确认、后一个仍候补；研讨室同样验证最早合格候补。均观察到 3 条初始事件 + 取消/递补 2 条 Outbox，共 5 条。
- 管理员维护关闭后，两种业务均无剩余有效预约；研讨室 bucket 清空；再产生两条取消事件，共 7 条 Outbox。
- 强制 Outbox INSERT 失败，观察业务预约、成员 bucket 和 Outbox 均无残留。补跑进一步记录 `doRollback` 与实际失败 INSERT，防止把“请求被权限拦截、根本未执行业务”误算成回滚成功。
- **同键并发重放缺陷**：20 个相同请求，两种业务均为 11 个 200、9 个 409；仅一个预约和一条 Outbox，没有重复扣减/占房。讲座错误 `active-reservation-exists`，研讨室错误 `study-team-already-booked`。
- 源码支持的原因解释：首个幂等普通 SELECT 在等锁前执行；等到锁后，MyBatis SESSION 缓存和 REPEATABLE READ 旧快照可能使后续普通查询看不到先前请求提交的预约。研讨室拿到队伍锁后再次 `byKey()` 并不自动保证最新读取。实际 409 是已证实症状；这两个机制各自贡献多少，尚未单独做开关对照。
- 注入数据库异常时实际 HTTP 返回 **403**，而不是预期服务端错误；数据回滚与错误映射必须分别评价。需另查 ERROR dispatch、Security 和未捕获数据库异常的处理，不能把这个 403 解释成正常业务拒绝。
- 本次维护和取消是选定顺序场景；**没有完成维护/提交/取消任意交错的多实例竞争验收**。

### 最小优化建议（仅建议，未实现，收益未实测）

| 顺序 | 最小方案 | 预期作用 | 必须保留 / 主要风险 |
|---|---|---|---|
| P0 | 热点锁取得后，以真正 current read 校验幂等记录；避免复用旧的普通 SELECT 缓存结果 | 修复同键并发 409，减少误导性客户端重试 | 仅 clearCache 不足以解决 RR 快照；检查跨房间同队伍、唯一键竞争、同键不同参数。仍必须只写一单一事件 |
| P1 | `promoteWaiting` 在现有事务/资源锁内批量读候补成员与相关 bucket，按原顺序判定；对相同区间复用方法内判定结果 | 消除已实测的约 2N 成员相关数据库往返，减少 Mapper 重复调用及持锁时间 | 每次成功递补后更新内存占用视图；跨房间仍靠 bucket 唯一键兜底；不能以过期快照批准成员冲突 |
| P1 | 审查 `waiting` 查询计划、队列排序索引和读取字段；必要时分批读取，按稳定 `(created_at,id)` 前进 | 减少整队列映射/排序/一次性加载，改善长队列成本 | 尚未做 EXPLAIN 对照，不直接新增索引；不得只查队首后退出或任意截断，从而饿死后续合格候补 |
| P2 | 保持锁顺序、取消/维护保护与事务范围，减少临界区里重复查询和响应组装回读 | 缩短讲座/房间每次持锁服务时间 | 不把库存/状态/快照与 Outbox 拆事务；不能仅为少一次 UPDATE 就提前扣库存并在重放分支提交 |
| P2 | 临界区优化后，再测连接池/请求排队；必要时设计有界等待和明确失败语义 | 改善用户长时间等待、保护非热点请求 | 不以扩容连接池冒充热点吞吐提升；不得用极低限流阈值取代不限流审计；幂等重试与客户端退避需匹配 |

特别禁止的“捷径”：

- 不把讲座 FIFO 改回 `SKIP LOCKED`。当前取消与维护共享 slot 行锁；移除它必须重新证明维护不会漏掉刚提交的预约。
- 不直接移除 `book()` 中的补位扫描。成员可能因另一个房间取消而重新符合条件；只在本房间取消时补位可能让新请求插队。
- 不把房间行锁拿掉，也不把读到的空闲当作占用成功；半开区间检查和成员 bucket 唯一键仍是正确性边界。
- 不把业务提交与 Outbox 分开，不声称 Publisher Confirm 可以替代业务事务。
- 本轮未使用 Redis 预扣、MQ 异步预约或修改正式业务逻辑。

## 复现与材料

从仓库根目录运行，依赖现有被 Git 忽略的 `deploy/.env`、JDK 17、Maven、Node、MySQL Shell 和已构建 Gateway JAR：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/benchmark/booking-performance-audit.ps1 -Mode pilot
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/benchmark/booking-performance-audit.ps1 -Mode full
# 只补跑一致性诊断
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/benchmark/booking-performance-audit.ps1 -Mode safety
node deploy/benchmark/booking-audit-analyze.mjs target/booking-audit-<本次run>
```

脚本必须能新建随机测试库，端口 18600/18602 必须空闲。不会复用既有业务数据库。密码只由本地配置读取，MySQL Shell 使用标准输入传入密码，不写进命令行和结果。

- 原始性能样本：`target/booking-audit-f0d1fdbcb858/*-requests.json`（本地忽略目录，不提交大文件）。分析 JSON 每个场景保存原始样本 SHA-256。
- 汇总数据：`docs/benchmarks/booking-performance-audit-2026-10-16.json`；独立一致性结果：`docs/benchmarks/booking-performance-safety-2026-10-16.json`。
- 工具：`deploy/benchmark/booking-performance-audit.ps1`、`booking-audit-{environment,load,analyze}.mjs`、`audit-common.mjs`。
- 测试探针：`AuditRecorder`、`AuditInstrumentation`、`AuditSqlInterceptor`、`AuditLockSampler`、`BookingPerformanceAuditLive`。

执行记录：最初预检曾因插件代理解包失败中止，修正后预检通过；性能主体全部采集后，首次完整 runner 被“重放无需 promote”探针断言中断。后续补跑又暴露非 JSON 错误响应和真实 403，保留失败记录，不将首次完整运行记作全绿。性能样本采集在该断言前已经完成，最终一致性结果以单独补跑附件为准。

最终验证结果：

- 最终独立 `safety` runner：JUnit 1 项执行通过；内部 8 项诊断中，FIFO、维护、事务回滚共 **6 项通过**，同键并发接口重放 **2 项不通过**。403 异常映射另外明确标记为 `unexpectedHttpStatus=true`，不能因 runner 退出 0 就宣称业务全部正确。
- 原项目根目录 `mvn -q test`：**97 项中 96 通过、1 跳过、0 失败、0 错误**；跳过的是既有 Docker/MySQL 测试，不宣称其通过。实时审计不在常规测试默认匹配范围内。
- 所有本轮创建的随机审计库均已清理；18600/18602 测试服务已停止，既有业务库未删除。
- `git diff HEAD` 的正式源码和配置无变化。审计新增的测试工具、报告、汇总 JSON 仅保存在本地，**未随此前的 `e1cf801` 推送，也未新增推送本次审计材料**。

本报告不证明多实例极限吞吐、RabbitMQ 宕机恢复或生产 SLA，也没有实际优化后的性能提升百分比。
