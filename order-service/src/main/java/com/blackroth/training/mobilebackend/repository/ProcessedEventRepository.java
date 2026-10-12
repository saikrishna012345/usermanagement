
package com.blackroth.training.mobilebackend.repository;

import com.blackroth.training.mobilebackend.model.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository
        extends JpaRepository<ProcessedEvent, Long> {

    boolean existsByEventId(String eventId);
}
