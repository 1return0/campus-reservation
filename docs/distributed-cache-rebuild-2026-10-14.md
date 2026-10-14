# 跨实例缓存重建：2026-10-14

## 实现

公开资源目录、固定场次列表、研讨室开放窗口沿用 Spring Cache 的缓存名称、Key、序列化和失效入口。
`DistributedRebuildCacheManager` 包装 RedisCacheManager 的 `get(key, Callable)`：

1. 查 Redis，命中直接返回，不申请锁。
2. 未命中，按缓存名称与 Key 的 SHA-256 获取 Redisson RLock。
3. 最多等待 `RESERVATION_CACHE_REBUILD_WAIT_MILLIS`（默认 1000ms，允许 0–10000ms）。
4. 再次查缓存；即使等待超时，若另一实例已回填也返回缓存。
5. 持锁且仍未命中才执行业务查询并回填；只由持锁线程解锁。
6. 未拿锁且仍未命中，返回 503 / public-cache-rebuild-busy，不直接查询数据库。

使用不指定固定 leaseTime 的 tryLock，启用 Redisson watchdog 续期；进程退出后的残留锁依赖 Redis TTL 恢复。
Redisson 3.52.0 core 与现有 Lettuce 共用 Spring Redis 的 host/port/password/username/database/SSL 参数，
当前部署为 standalone Redis，不宣称已适配 Sentinel/Cluster 拓扑。
Redisson 客户端延迟初始化，缓存关闭时不创建客户端。

Redis 读、锁获取失败时回源数据库；回填失败返回已加载结果。业务查询抛异常不执行第二次查询。
中断恢复线程 interrupt 标记并受控退出。锁释放异常不把已成功的查询变成失败。
原有提交后定向失效、TTL 抖动、MySQL 预约并发保护不变。

## 真实跨进程验证

`DistributedCacheRebuildLiveSmoke` 在真实 Redis 7.0.15 上启动两个独立 JVM，每个 JVM 独立
创建 Lettuce/Redisson 客户端，20 个线程访问同一个随机未命中 Key。
回源 loader 用 Redis 原子计数记录执行次数，模拟加载延迟后返回结果。

实测通过：两个 JVM 共 40 个请求全部返回预期值，loader 执行次数为 1。
仅清理本次随机缓存 Key 和计数器，不清库，不修改学生业务数据。

这是共享 Redis 的缓存组件跨进程测试，不是两个完整 Reservation 服务的 HTTP/MySQL 端到端压测；
loader 是受控测试函数，不应把计数写成 MySQL 慢查询性能指标。

```powershell
$env:CAMPUS_RESERVE_REDIS_LIVE_SMOKE='true'
# 另行设置 RESERVATION_REDIS_HOST / PORT / PASSWORD，不在报告或 Git 保存密码。
mvn -q -pl reservation-service -am '-Dtest=DistributedCacheRebuildLiveSmoke' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

## 边界

本次根工程 `mvn -q test` 成功；未具备本机 Docker 的 MySQL Testcontainers 测试标记 skipped，
没有将其写成真实数据库测试通过。分布式重建组件的 11 项单测通过，包括 Spring 注解边界
传播 503、缓存/锁故障回源、业务异常不重复执行、回填及解锁异常、中断与原有失效 Key。
额外真实跨 JVM Redis Smoke 通过。历史 Surefire 目录可能留有先前运行报告，应以本次命令和选定测试结果判断，
不能将目录内所有旧 XML 混合计数为本次回归结果。

- Redis 故障降级时不保证集群只回源一次；极端故障流量需要其他准入保护。
- Redis 锁依赖部署与连接状态，不宣称主从故障切换下的严格互斥。
- 提交后删除缓存仍可能与在途查询回填旧数据竞争，TTL 限制陈旧时间，不宣称强一致。
- 公开可预约信息仅作展示建议，实际提交仍由 MySQL 实时判断。
- 不宣称 QPS/P95 提升、生产 SLA 或完整多实例系统验收。

## 简历可用描述

使用 Redis 缓存公开资源目录与开放时间窗，通过 Redisson 按 Key 分布式锁及锁内二次检查协调跨实例缓存重建；
结合 TTL 抖动与事务提交后定向失效降低旧数据风险，Redis 故障时回源 MySQL，实际预约以数据库实时校验为准。
