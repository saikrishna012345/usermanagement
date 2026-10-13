package com.blackroth.training.mobilebackend.repository;

import com.blackroth.training.mobilebackend.model.InventoryReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryReservationRepository
        extends JpaRepository<InventoryReservation, Long> {

    Optional<InventoryReservation> findByOrderIdAndProductId(
            Long orderId,
            Long productId
    );

    List<InventoryReservation> findByOrderId(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM InventoryReservation r "
            + "WHERE r.orderId = :orderId "
            + "AND r.productId = :productId")
    Optional<InventoryReservation> findForUpdate(
            @Param("orderId") Long orderId,
            @Param("productId") Long productId
    );
}