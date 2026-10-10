
package com.blackroth.training.mobilebackend.repository;

import com.blackroth.training.mobilebackend.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository
        extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent>
    findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status,
            Instant now
    );
}
