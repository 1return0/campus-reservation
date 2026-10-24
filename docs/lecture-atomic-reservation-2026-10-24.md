# 讲座预约优化：代码替换说明

日期：2026-10-24。范围：讲座同步预约入口。不修改研讨室业务、不调整 Redis 限流阈值、不拆分 Outbox 事务、不引入消息异步预约或库存分桶。

## 1. 前置场次锁改为原子扣减成功路径

位置：`reservation-service/src/main/java/com/campusreserve/reservation/service/impl/ReservationServiceImpl.java`，`reserve()`。

原来：

```java
ResourceSlotEntity slot = resourceSlotMapper.lockById(slotId);
// 后面创建 PENDING 预约，再 tryConsumeCapacity()
```

现在：

```java
boolean confirmed = resourceSlotMapper.tryConsumeFutureCapacity(slotId, now()) == 1;
ResourceSlotEntity slot = confirmed ? resourceSlotMapper.selectById(slotId)
        : resourceSlotMapper.lockById(slotId);
```

成功扣减的 UPDATE 自身已持有场次写锁，普通 SELECT 只读取场次资料；不再重复执行显式查锁。扣减失败时保留场次锁，取得 fresh replay、校验开放状态及是否开始后，再决定确认或候补。

SQL 影响零行不能直接解释为售罄：场次不存在、已关闭、已经开始也可能返回零行。取消事务还可能在第一次扣减失败与取得回退锁之间释放名额，所以锁内看到余量大于零时会再次尝试扣减；这次更新若失败则抛异常，避免把异常误当正常候补。

## 2. 新增带开始时间条件的扣减 SQL

位置：`reservation-service/src/main/java/com/campusreserve/reservation/dao/mapper/ResourceSlotMapper.java`，新增 `tryConsumeFutureCapacity(slotId, currentTime)`：

```sql
UPDATE resource_slot
SET available_capacity = available_capacity - 1,
    version = version + 1
WHERE id = #{slotId}
  AND status = 'OPEN'
  AND starts_at > #{currentTime}
  AND available_capacity > 0
```

原 `tryConsumeCapacity()` 保留给已经持有场次锁的取消递补流程，避免顺带改变其语义。这个 SQL 是条件原子更新，不是比较旧版本号的 CAS 乐观锁。

## 3. 删除有效预约预查询，依靠现有联合唯一约束

原来在锁内通过 `selectOne(slotId + requesterReference + activeMarker=1)` 预查询，再 INSERT；现在直接 INSERT，使用：

- `uk_reservation_idempotency(requester_reference, idempotency_key)`：同一请求键只能建一单。
- `uk_reservation_active_requester_slot(slot_id, requester_reference, active_marker)`：同一用户不能重复持有同一场次的有效预约（包括候补）。

发生唯一键冲突后重新 fresh replay：确认为同键重放才返回已有结果，否则抛 `active-reservation-exists`，整笔事务回滚先前扣减。没有将任意唯一键冲突都当成成功。

## 4. PENDING + 状态更新改为直接插入最终状态

原来：

```java
reservation.setStatus(ReservationStatus.PENDING);
reservationOrderMapper.insert(reservation);
// 再执行 markConfirmed() 或 markWaitlisted()
```

现在：

```java
reservation.setStatus(confirmed ? ReservationStatus.CONFIRMED : ReservationStatus.WAITLISTED);
requireChanged(reservationOrderMapper.insert(reservation), "reservation-create-failed");
```

候补时仍插入 `reservation_waitlist_entry`，并检查 INSERT 影响行数。最终预约查询保留，以取得数据库写入的创建时间；Outbox 仍记录同一事务中的最终业务事实。

## 5. 增加并发幂等重放的名额归还

首次幂等查询命中时仍直接返回，不消耗准入次数、不扣名额。

首次查询未命中、排队后才查到已有结果时，本请求可能已扣减了一份名额。现在在 fresh replay 命中处和 INSERT 唯一键竞争命中处都有：

```java
if (confirmed) {
    requireChanged(resourceSlotMapper.releaseCapacity(slotId),
            "reservation-replay-capacity-release-failed");
}
return toResponse(existing);
```

仅归还本次成功扣到的名额。归还失败会抛异常并回滚，不会谎报成功。其他业务冲突和数据库/Outbox 异常不做独立提交的手动补偿，由原事务一起回滚。

## 保留的协议

- 仍为同步 HTTP 返回 CONFIRMED / WAITLISTED。
- 保留 `READ_COMMITTED`、首次幂等查询、`findReplayFresh()` 及准入顺序。
- 候补入队、取消递补、管理员维护通过同一场次协调，维护后不得残留新的有效预约；已落库候补仍按 `created_at,id` 补位。
- 预约、候补记录、库存、Outbox 同事务；缓存仍在提交后失效。
- UPDATE 仍有数据库行锁，本次没有消除单库存行热点。

## 修改清单

- `ReservationServiceImpl.java`：新预约流程。
- `ResourceSlotMapper.java`：新增时间条件扣减 SQL。
- `ReservationOrderMapper.java`：仅更新 fresh replay 注释。
- `ReservationServiceImplTest.java`：调整原确认/候补测试的 Mapper 桩与断言。
- `LectureAtomicReservationTest.java`：8 项新边界测试（早期/晚期重放、归还失败、跨场次同键竞争、不同键冲突、回退锁后新余量、扣减异常和 Outbox 异常）。
- `AuditSqlInterceptor.java`：测试探针识别成功原子 UPDATE 的拿锁时间。

## 验证结果

执行 `mvn -o -pl reservation-service -am test`：90 项测试，89 项通过、1 项 Docker 集成测试因本机 Docker 不可用跳过，零失败、零错误。新增 8 项单元测试全部通过。没有把跳过项计为通过。

独立测试服务经 Gateway 连接虚拟机 MySQL 8.0.45，使用独立 schema；Hikari 最大连接数 10、Java 堆上限 512 MiB，先以 20 并发预热 150 次。关闭 Redis 准入与缓存、消息发布及定时任务，观察数据库真实竞争，不用限流截断请求。

最终源码先完成 50/500 并发、20/500 名额的四个场景，每个场景 500 个请求，并通过 11 项真实数据库正确性检查。随后使用完全相同数据和请求哈希，对原版与最终源码各做一轮相邻 500 并发对照，结果如下：

| 场景（均为 500 个请求、500 并发） | 原版 → 本版总耗时 | 原版 → 本版 TPS | 原版 → 本版 P95 | 原版 → 本版 P99 | 每请求平均 SQL |
| --- | --- | --- | --- | --- | --- |
| 20 名额：20 确认、480 候补 | 8.758 → 7.050 秒 | 57.09 → 70.92 | 8399 → 6693 ms | 8630 → 6937 ms | 10.92 → 8.92 |
| 500 名额：500 确认 | 6.466 → 5.774 秒 | 77.33 → 86.59 | 6125 → 5501 ms | 6349 → 5682 ms | 9 → 7 |

这是一轮本地隔离对照，不是生产容量或稳定提速比例承诺；不同轮次有环境波动。相邻对照的测试实例统一设置 TCP accept backlog 为 1024，以避免早先原版对照的 Gateway 连接拒绝；没有修改正式服务配置，不能把不同排队参数的结果直接混为同条件数据。

真实数据库检查包括：同键并发返回已有预约、同用户不同键冲突、跨场次同键竞争、取消与新预约并发及 FIFO 补位、管理员维护互斥、Outbox 注入失败时库存/预约/事件整体回滚、已开始和已取消场次拒绝预约等。对照双方 11 项均通过，最终源码四场景验证的 11 项也通过。RabbitMQ 发布被关闭，本轮只验证 Outbox 落库与事务原子性，不声称真实 Broker 投递验证通过。

原始阶段指标、SQL 明细、正确性结果和源码哈希见 [本次验证数据](benchmarks/lecture-atomic-reservation-verification-2026-10-24.json)。数据文件明确排除了连接拒绝导致样本不完整的两次原版测试，以及一次 Maven 复用旧编译产物的无效优化对照；最终有效测试刷新源码时间戳后重新编译，且三份业务源码与正式工作区 SHA-256 一致。

此前候选实验见 [方案比较](lecture-concurrency-options-2026-10-24.md)，不将候选原型数字当作最终版本成绩。此前发现的研讨室维护通知问题不在本次讲座优化范围，未修改、未声称修复。测试 schema 已删除，临时 Gateway 已停止，未提交或推送 GitHub。
