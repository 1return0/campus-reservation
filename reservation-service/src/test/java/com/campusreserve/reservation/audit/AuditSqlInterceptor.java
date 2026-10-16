package com.campusreserve.reservation.audit;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.Statement;
import java.util.List;

/** Counts actual StatementHandler executions separately from Executor calls served from MyBatis local cache. */
@Intercepts({
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}),
        @Signature(type = StatementHandler.class, method = "update", args = {Statement.class})
})
public class AuditSqlInterceptor implements Interceptor {
    @Override public Object intercept(Invocation invocation) throws Throwable {
        var sample = AuditRecorder.CURRENT.get();
        if (sample == null) return invocation.proceed();
        if (invocation.getTarget() instanceof Executor) {
            sample.logicalQueries++;
            Object result = invocation.proceed();
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getId().endsWith("StudyRoomBookingMapper.waiting") && result instanceof List<?> list) {
                sample.candidateRows += list.size();
            }
            return result;
        }
        Statement jdbcStatement = (Statement) invocation.getArgs()[0];
        sample.connectionId = jdbcStatement.getConnection().unwrap(com.mysql.cj.jdbc.JdbcConnection.class).getId();
        Object target = invocation.getTarget();
        while (java.lang.reflect.Proxy.isProxyClass(target.getClass())) {
            target = SystemMetaObject.forObject(java.lang.reflect.Proxy.getInvocationHandler(target)).getValue("target");
        }
        var meta = SystemMetaObject.forObject(target);
        MappedStatement mapped = (MappedStatement) meta.getValue("delegate.mappedStatement");
        String name = mapped.getId().substring(mapped.getId().lastIndexOf(".mapper.") + 8);
        var timing = sample.sql.computeIfAbsent(name, key -> new AuditRecorder.Timing());
        sample.physicalQueries++;
        long start = System.nanoTime();
        try {
            Object result = invocation.proceed();
            if (result instanceof List<?> list) timing.rows += list.size();
            else if (result instanceof Number n) timing.rows += n.intValue();
            if (name.equals("ResourceSlotMapper.lockById") || name.equals("ReservableResourceMapper.lockById")) {
                sample.lockAcquired = System.nanoTime();
            }
            return result;
        } finally {
            timing.calls++;
            double millis = (System.nanoTime() - start) / 1_000_000.0;
            timing.millis += millis;
            timing.executions.add(millis);
        }
    }
}
