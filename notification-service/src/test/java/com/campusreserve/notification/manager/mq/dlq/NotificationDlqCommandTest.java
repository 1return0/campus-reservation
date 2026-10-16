package com.campusreserve.notification.manager.mq.dlq;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationDlqCommandTest {
    @Test void refuses_mutation_flags_without_a_specific_message_before_connecting() {
        assertThat(NotificationDlqCommand.run(new String[]{"replay", "--confirm-replay"})).isEqualTo(2);
        assertThat(NotificationDlqCommand.run(new String[]{"status", "--confirm-replay"})).isEqualTo(2);
        assertThat(NotificationDlqCommand.run(new String[]{"inspect", "--limit", "101"})).isEqualTo(2);
    }
}
