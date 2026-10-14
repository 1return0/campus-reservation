# Redisson 缓存重建与有界重试：2026-10-14

## 当前实现

使用一层按缓存名称与 Key 区分的 Redisson 分布式锁，不维护本地 Future Map 或请求合并机制。
公开资源目录、固定场次列表、研讨室开放窗口仍通过 Spring Cache 调用包装后的缓存加载方法。

1. 先读 Redis，命中直接返回。
2. 未命中，`tryLock(0, MILLISECONDS)` 立即尝试获取对应的锁，使用 watchdog 续期。
3. 拿到锁后二次检查缓存，仍未命中才查询数据库、回填，最后由持锁线程释放。
4. 未拿锁，短暂退避后从步骤 1 重试；另一实例回填后无需拿锁即可返回。
5. 等待上限到达后最后复查缓存，仍未命中返回 503 / public-cache-rebuild-busy。

退避以 20–40ms 起步，逐步增长至 100–200ms，加入随机抖动，并限制在剩余等待预算内。
默认等待预算为 `RESERVATION_CACHE_REBUILD_WAIT_MILLIS=1000`。网络和查询另受各自超时约束，
该预算不是整个 HTTP 请求的一秒硬截止。
Redis 故障回源数据库；查询异常不重复执行，回填或解锁失败不丢弃已成功取得的结果。
原有 TTL 抖动、事务提交后失效、MySQL 预约保护保持原逻辑。

## 验证边界

针对性测试覆盖首次命中、锁内二次检查、重试期间回填后不再申请锁、零等待预算、锁释放后重试、
Redis 故障、业务异常不重复执行、中断、回填与解锁故障、Spring 异常传播和提交后失效。
本次根工程 `mvn -q clean test` 成功：78 项测试，77 项通过、1 项因本机 Docker 不可用跳过，0 失败、0 错误。
已移除请求合并的旧测试及旧编译产物，当前测试覆盖单层锁与有界重试策略。
真实跨 JVM Smoke 保留两进程、40 请求、一次加载检查，增加锁尝试和成功获取次数的观察。
单层锁不预设“最多两个线程获取锁”；检查缓存与获取锁间的竞争仍可能导致额外获取。

本轮此前连接 VM Redis 超时，沙箱外重试仍未连接成功；当前版本暂无新的真实跨 JVM 通过结果。
首次实现 `3848bf7` 的真实通过记录保留在原文档，不替代当前版本的真实验收。
不宣称 QPS/P95、吞吐改善百分比或完整预约多实例压测。

```powershell
# 先设置 RESERVATION_REDIS_HOST / PORT / PASSWORD。
$env:CAMPUS_RESERVE_REDIS_LIVE_SMOKE='true'
mvn -q -pl reservation-service -am '-Dtest=DistributedCacheRebuildLiveSmoke' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

## 简历句子

查询缓存与降级：采用 Redis 缓存公开资源目录与开放时间窗，通过 Redisson 按 Key 分布式锁及锁内二次检查防止热点缓存重复重建；
未获锁请求有界等待并复查缓存，结合 TTL 抖动、事务提交后定向失效与 Redis 故障回源，降低数据库回源压力及旧数据风险。
