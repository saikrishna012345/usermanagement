package com.blackroth.training.mobilebackend.resilience;

import com.blackroth.training.mobilebackend.dto.ProductResponse;
import com.blackroth.training.mobilebackend.exception.BusinessException;
import com.blackroth.training.mobilebackend.exception.InvalidRequestException;
import com.blackroth.training.mobilebackend.exception.ResourceNotFoundException;
import com.blackroth.training.mobilebackend.model.Category;
import com.blackroth.training.mobilebackend.model.InventoryReservation;
import com.blackroth.training.mobilebackend.model.Product;
import com.blackroth.training.mobilebackend.repository.CategoryRepository;
import com.blackroth.training.mobilebackend.repository.InventoryReservationRepository;
import com.blackroth.training.mobilebackend.repository.ProductRepository;
import com.blackroth.training.mobilebackend.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Product Reservation Idempotency, Concurrency, and Conflict Chaos Tests")
class ProductReservationChaosTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private InventoryReservationRepository reservationRepository;

    @InjectMocks
    private ProductService productService;

    private Product product;
    private Category category;
    private static final Long ORDER_ID = 777L;
    private static final Long PRODUCT_ID = 88L;

    @BeforeEach
    void setUp() {
        category = new Category("Gadgets");
        product = new Product("Smart Watch", "GPS enabled", new BigDecimal("199.99"), 15, category);
        product.setId(PRODUCT_ID);
    }

    @Test
    @DisplayName("Reservation retry with conflicting quantity throws BusinessException without modifying stock")
    void whenReservationRetriedWithConflictingQuantity_thenThrowsBusinessException() {
        int initialStock = product.getStockQuantity();
        InventoryReservation existing = new InventoryReservation(ORDER_ID, PRODUCT_ID, 2);
        existing.setStatus(InventoryReservation.Status.RESERVED);

        when(reservationRepository.findByOrderIdAndProductId(ORDER_ID, PRODUCT_ID))
                .thenReturn(Optional.of(existing));

        // Call with quantity 5 instead of existing quantity 2
        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> productService.reserveStock(ORDER_ID, PRODUCT_ID, 5)
        );

        assertTrue(ex.getMessage().contains("already exists with a different state or quantity"));
        // Stock remained unchanged
        assertEquals(initialStock, product.getStockQuantity());
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Reservation fails when requested quantity exceeds available stock")
    void whenStockInsufficient_thenThrowsBusinessException() {
        int initialStock = product.getStockQuantity(); // 15
        when(reservationRepository.findByOrderIdAndProductId(ORDER_ID, PRODUCT_ID))
                .thenReturn(Optional.empty());
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        // Request 20 when only 15 available
        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> productService.reserveStock(ORDER_ID, PRODUCT_ID, 20)
        );

        assertTrue(ex.getMessage().contains("Insufficient stock"));
        assertEquals(initialStock, product.getStockQuantity());
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Releasing already committed reservation throws BusinessException to prevent inconsistent state")
    void whenReleasingCommittedReservation_thenThrowsBusinessException() {
        InventoryReservation committed = new InventoryReservation(ORDER_ID, PRODUCT_ID, 3);
        committed.setStatus(InventoryReservation.Status.COMMITTED);

        when(reservationRepository.findForUpdate(ORDER_ID, PRODUCT_ID))
                .thenReturn(Optional.of(committed));

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> productService.releaseReservation(ORDER_ID, PRODUCT_ID)
        );

        assertTrue(ex.getMessage().contains("Only reserved inventory can be released"));
        assertEquals(InventoryReservation.Status.COMMITTED, committed.getStatus());
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("Committing already released reservation throws BusinessException")
    void whenCommittingReleasedReservation_thenThrowsBusinessException() {
        InventoryReservation released = new InventoryReservation(ORDER_ID, PRODUCT_ID, 3);
        released.setStatus(InventoryReservation.Status.RELEASED);

        when(reservationRepository.findForUpdate(ORDER_ID, PRODUCT_ID))
                .thenReturn(Optional.of(released));

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> productService.commitReservation(ORDER_ID, PRODUCT_ID)
        );

        assertTrue(ex.getMessage().contains("Only reserved inventory can be committed"));
        assertEquals(InventoryReservation.Status.RELEASED, released.getStatus());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Invalid parameters reject reservation with InvalidRequestException")
    void whenInvalidParameters_thenThrowsInvalidRequestException() {
        assertThrows(InvalidRequestException.class, () -> productService.reserveStock(null, PRODUCT_ID, 2));
        assertThrows(InvalidRequestException.class, () -> productService.reserveStock(-1L, PRODUCT_ID, 2));
        assertThrows(InvalidRequestException.class, () -> productService.reserveStock(ORDER_ID, PRODUCT_ID, null));
        assertThrows(InvalidRequestException.class, () -> productService.reserveStock(ORDER_ID, PRODUCT_ID, 0));
        assertThrows(InvalidRequestException.class, () -> productService.reserveStock(ORDER_ID, PRODUCT_ID, -1));
        assertThrows(ResourceNotFoundException.class, () -> productService.reserveStock(ORDER_ID, null, 2));
    }
}
