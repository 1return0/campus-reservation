package com.campusreserve.reservation.audit;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.*;

/** In-memory instrumentation of this opt-in test JVM only; no production source/JAR changes. */
public final class AuditInstrumentation {
    public static void install() {
        new AgentBuilder.Default().disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(named("com.campusreserve.reservation.service.impl.ReservationServiceImpl")
                        .or(named("com.campusreserve.reservation.service.impl.StudyRoomServiceImpl"))
                        .or(named("org.springframework.jdbc.datasource.DataSourceTransactionManager"))
                        .or(named("com.zaxxer.hikari.HikariDataSource")))
                .transform((builder, type, classLoader, module, domain) -> builder.visit(Advice.to(PhaseAdvice.class)
                        .on(named("promoteWaiting").or(named("requireRoom"))
                                .or(named("reserve")).or(named("book"))
                                .or(named("doBegin")).or(named("doCommit")).or(named("doRollback"))
                                .or(named("doCleanupAfterCompletion"))
                                .or(named("getConnection").and(takesArguments(0))))))
                .installOn(ByteBuddyAgent.install());
    }

    public static class PhaseAdvice {
        @Advice.OnMethodEnter
        public static long[] enter(@Advice.Origin("#m") String name) { return AuditRecorder.enter(name); }
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Origin("#m") String name, @Advice.Enter long[] checkpoint,
                                @Advice.Thrown Throwable error) { AuditRecorder.exit(name, checkpoint, error); }
    }
}
