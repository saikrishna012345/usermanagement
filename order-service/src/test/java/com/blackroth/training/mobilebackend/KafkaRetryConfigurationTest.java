package com.blackroth.training.mobilebackend;

import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.util.backoff.BackOffExecution;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KafkaRetryConfigurationTest {

    @Test
    void backoffAllowsThreeRetriesAtTwoSecondIntervals() {
        FixedBackOff backOff = new FixedBackOff(2000L, 3L);
        BackOffExecution execution = backOff.start();

        assertEquals(2000L, execution.nextBackOff());
        assertEquals(2000L, execution.nextBackOff());
        assertEquals(2000L, execution.nextBackOff());

        assertEquals(
                BackOffExecution.STOP,
                execution.nextBackOff()
        );
    }
}