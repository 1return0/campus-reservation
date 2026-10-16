package com.campusreserve.reservation.audit;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Read-only localhost/test-only probes; no alternative production business implementation. */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="campus-reserve.test-attribution",havingValue="true")
public class AuditAttributionProbe {
    private final DataSource source;
    private final PlatformTransactionManager transactions;
    public AuditAttributionProbe(DataSource source, PlatformTransactionManager transactions) {
        this.source=source;this.transactions=transactions;
    }
    @GetMapping("/__audit/runtime")
    public Map<String,Object> runtime() throws Exception {
        List<Double> ping=new ArrayList<>();
        for(int i=0;i<20;i++) {
            long start=System.nanoTime();
            try(var connection=source.getConnection();var statement=connection.prepareStatement("SELECT 1");var result=statement.executeQuery()) {
                result.next();
            }
            ping.add((System.nanoTime()-start)/1e6);
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("dbPingMillis",ping);
        result.put("uptimeMillis",ManagementFactory.getRuntimeMXBean().getUptime());
        result.put("jitMillis",ManagementFactory.getCompilationMXBean().getTotalCompilationTime());
        result.put("heapUsed",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        result.put("gc",ManagementFactory.getGarbageCollectorMXBeans().stream().map(x->
                Map.of("name",x.getName(),"count",x.getCollectionCount(),"millis",x.getCollectionTime())).toList());
        var os=ManagementFactory.getOperatingSystemMXBean();
        if(os instanceof com.sun.management.OperatingSystemMXBean extended) {
            result.put("processCpuNanos",extended.getProcessCpuTime());
            result.put("systemCpuLoad",extended.getCpuLoad());
        }
        return result;
    }

    @GetMapping("/__audit/isolation-cost")
    public List<Map<String,Object>> isolationCost() {
        if(!"attribution".equals(System.getenv("AUDIT_MODE")))throw new IllegalStateException("attribution-only");
        List<Map<String,Object>> result=new ArrayList<>();
        // ABBA alternation, 40 discarded warm-ups then 300 transactions per isolation.
        int[] levels={TransactionDefinition.ISOLATION_REPEATABLE_READ,TransactionDefinition.ISOLATION_READ_COMMITTED,
                TransactionDefinition.ISOLATION_READ_COMMITTED,TransactionDefinition.ISOLATION_REPEATABLE_READ};
        for(int i=0;i<640;i++) {
            int isolation=levels[i%4];
            var template=new TransactionTemplate(transactions);template.setIsolationLevel(isolation);
            var sample=new AuditRecorder.Sample();long start=System.nanoTime();
            AuditRecorder.CURRENT.set(sample);
            try {
                template.execute(status->{
                    var connection=DataSourceUtils.getConnection(source);
                    long queryStart=System.nanoTime();
                    try(var statement=connection.prepareStatement("SELECT 1");var rows=statement.executeQuery()) {
                        rows.next();
                    }catch(Exception failure){throw new IllegalStateException("isolation-probe-failed",failure);}
                    finally {
                        var timing=sample.sql.computeIfAbsent("jdbc.selectOne",key->new AuditRecorder.Timing());
                        timing.calls++;timing.millis=(System.nanoTime()-queryStart)/1e6;timing.executions.add(timing.millis);
                        sample.physicalQueries++;
                        DataSourceUtils.releaseConnection(connection,source);
                    }
                    return null;
                });
            }finally{AuditRecorder.CURRENT.remove();}
            sample.serverMillis=(System.nanoTime()-start)/1e6;
            if(i>=40)result.add(Map.of("isolation",isolation==TransactionDefinition.ISOLATION_READ_COMMITTED?"RC":"RR","sample",sample));
        }
        return result;
    }
}
