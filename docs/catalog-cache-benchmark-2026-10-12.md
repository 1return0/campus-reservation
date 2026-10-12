# 公开资源目录缓存对照（2026-10-12）

这是本地开发机对虚拟机 MySQL/Redis 的单实例、只读接口测量，不是生产压测或预约写入压测。压测请求经本机 Gateway 的 JWT 验签和 Reservation 的角色校验，使用脚本签发的合成 `STUDENT` JWT；没有创建测试账号或改动预约数据。目录中有 8 个已发布资源。

## 固定口径

- 接口：`GET /api/v1/resources`；每轮先预热 40 次，再测 400 次，并发 16。
- 环境：同一台开发机运行 Gateway + Reservation，MySQL/Redis 在同一台虚拟机；Java 17；`NACOS_DISCOVERY_ENABLED=false`，Outbox 与生命周期调度器关闭。
- 无缓存组使用 `NoOpCacheManager`；Redis 组开启 `RESERVATION_REDIS_ENABLED=true`。Redis 缓存键已在虚拟机上核验存在，且具有 TTL。
- 依次运行无缓存、Redis、Redis、无缓存，避免只依赖单次顺序结果。每轮重启服务，接口数据和负载参数不变。

| 轮次 | 配置 | 成功/请求 | 吞吐（req/s） | P50（ms） | P95（ms） | P99（ms） |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 无缓存 | 400/400 | 288.405 | 48.404 | 81.863 | 111.230 |
| 2 | Redis | 400/400 | 290.568 | 53.136 | 80.231 | 100.237 |
| 3 | Redis | 400/400 | 256.365 | 57.972 | 100.359 | 116.665 |
| 4 | 无缓存 | 400/400 | 314.940 | 49.481 | 74.762 | 88.679 |

在此仅 8 条资源、数据库查询很轻的工作负载下，Redis 组没有稳定的吞吐或 P95 优势。**不能将这组结果写成“Redis 提升了 QPS / 降低了 P95”。** Redis 缓存的工程价值在于避免重复数据库读取和应对目录更大、读流量更高的情况，但后者需要单独构造有代表性的业务负载后再测。此实验未采集 CPU、DB 查询数或资源消耗，也不支持生产容量结论。

复现前先用 Maven 打包，再分别运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/benchmark/catalog-read.ps1 -Mode no-cache -Requests 400 -Concurrency 16 -Warmup 40
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/benchmark/catalog-read.ps1 -Mode redis-cache -Requests 400 -Concurrency 16 -Warmup 40
```

Redis 组需要在进程环境设置 `RESERVATION_REDIS_PASSWORD`（不写入仓库）。脚本使用 `deploy/.env` 的其他本地连接设置；测试启动并清理自己创建的 Java 进程，日志写在忽略入库的 `target/catalog-benchmark-*` 下。指标由 [Node 测量脚本](../deploy/benchmark/catalog-read.js)输出。

同日验证：`mvn test` 生成 23 份新 Surefire 报告，共 63 项测试、0 失败、0 错误、1 跳过；真实 Redis/Lua Smoke 通过，包括 12 个工作线程处理 60 次准入请求时按 20 次上限准确放行；[研讨室浏览器全链路 Smoke](../deploy/smoke/study-room-end-to-end.ps1) 通过，覆盖 Gateway、JWT、MySQL、Outbox、RabbitMQ、Inbox。Smoke 证明选定链路和条件成立，不是高并发吞吐或故障恢复证明。

后续同日将Lua准入测试扩至500个工作线程、500次同步起跑请求，实测准入20次、限流拒绝480次，详见 [500次并发验证记录](redis-admission-500-burst-2026-10-12.md)。
