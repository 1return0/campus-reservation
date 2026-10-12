# 500次真实Redis准入并发验证

2026-10-12 将原先12个工作线程处理60次请求的测试扩为500个工作线程处理500次请求。请求使用不同的合成学生标识，竞争同一房间/日期的20次准入限额。

所有500个线程先到达准备门闩，确认全部就绪后由同一个起跑门闩释放；统计真实准入函数的峰值在途调用数。提前对Redis进行PING，整个请求批次最多等待30秒，限流窗口60秒。测试直接调用项目实际 `RedisStudyRoomAdmissionGuard` 与 Lua，不经过HTTP、Gateway或MySQL预约写入。

## 运行结果

| 项目 | 实测值 |
| --- | ---: |
| 请求总数 | 500 |
| 工作线程数 | 500 |
| 峰值在途准入函数调用数 | 500 |
| 正常准入 | 20 |
| 按限流错误码拒绝 | 480 |
| 未完成请求/非预期限流异常 | 0 |
| Redis范围计数器 | 500 |
| 请求批次耗时 | 555 ms |

环境为本机Java 17、Maven 3.9.16连接虚拟机Redis 7.0.15。Surefire报告：`StudyRoomRedisLiveSmoke` 三项测试全部通过，0失败、0错误、0跳过。除500请求场景，还保留每用户/房间日期的独立限额及固定场次限流检查。

实际输出：

```text
PASS Redis Lua burst: requests=500, workers=500, admitted=20, rejected=480, peak_active_calls=500, elapsed_ms=555
```

单批555ms是该测试环境下的观测值，不是系统QPS、P95或生产SLA。此结果证明本场景的Redis/Lua原子准入；MySQL容量、唯一约束与幂等性由预约业务的独立验证支撑，不把这次测试称为“500并发预约全链路验收”。

## 复现

配置进程环境 `RESERVATION_REDIS_HOST`、`RESERVATION_REDIS_PORT`、`RESERVATION_REDIS_PASSWORD` 后，在项目根目录执行：

```powershell
$env:CAMPUS_RESERVE_REDIS_LIVE_SMOKE='true'
mvn -q -pl reservation-service -am '-Dtest=StudyRoomRedisLiveSmoke' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

实现见 [StudyRoomRedisLiveSmoke.java](../reservation-service/src/test/java/com/campusreserve/reservation/manager/rate/StudyRoomRedisLiveSmoke.java)。每次使用随机测试房间标识，完成后清除本次范围计数器及500个测试学生计数器，并关闭线程池与Redis连接，不清理其他业务键。

简历可写：**设计Redis Lua双维度准入限流，在真实Redis环境的500并发准入请求测试中，按20次限额准确放行，其余480次限流拒绝。**
