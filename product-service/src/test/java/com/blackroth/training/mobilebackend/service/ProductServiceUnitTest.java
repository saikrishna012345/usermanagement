package com.blackroth.training.mobilebackend.service;

import com.blackroth.training.mobilebackend.dto.ProductResponse;
import com.blackroth.training.mobilebackend.exception.BusinessException;
import com.blackroth.training.mobilebackend.exception.ResourceNotFoundException;
import com.blackroth.training.mobilebackend.model.Category;
import com.blackroth.training.mobilebackend.model.InventoryReservation;
import com.blackroth.training.mobilebackend.model.Product;
import com.blackroth.training.mobilebackend.repository.CategoryRepository;
import com.blackroth.training.mobilebackend.repository.InventoryReservationRepository;
import com.blackroth.training.mobilebackend.repository.ProductRepository;
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
class ProductServiceUnitTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private InventoryReservationRepository reservationRepository;

    @InjectMocks
    private ProductService productService;

    private Product testProduct;
    private Category testCategory;

    @BeforeEach
    void setUp() {
        testCategory = new Category("Electronics");
        testProduct = new Product("Smartphone", "Flagship Phone", new BigDecimal("799.99"), 10, testCategory);
    }

    @Test
    @DisplayName("Successfully reserves inventory and decrements stock")
    void testReserveStockSuccess() {
        Long orderId = 100L;
        Long productId = 1L;
        int qty = 2;

        when(reservationRepository.findByOrderIdAndProductId(orderId, productId)).thenReturn(Optional.empty());
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
        when(productRepository.saveAndFlush(any(Product.class))).thenReturn(testProduct);

        ProductResponse response = productService.reserveStock(orderId, productId, qty);

        assertNotNull(response);
        assertEquals(8, testProduct.getStockQuantity());
        verify(reservationRepository).saveAndFlush(any(InventoryReservation.class));
    }

    @Test
    @DisplayName("Reserve stock is idempotent when already reserved with same quantity")
    void testReserveStockIdempotent() {
        Long orderId = 100L;
        Long productId = 1L;
        int qty = 2;

        InventoryReservation existing = new InventoryReservation(orderId, productId, qty);
        existing.setStatus(InventoryReservation.Status.RESERVED);

        when(reservationRepository.findByOrderIdAndProductId(orderId, productId)).thenReturn(Optional.of(existing));
        when(productRepository.findByIdWithCategory(productId)).thenReturn(testProduct);

        ProductResponse response = productService.reserveStock(orderId, productId, qty);

        assertNotNull(response);
        // Stock should not be decremented again
        assertEquals(10, testProduct.getStockQuantity());
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Release reservation restores stock and updates status to RELEASED")
    void testReleaseReservationSuccess() {
        Long orderId = 100L;
        Long productId = 1L;
        int qty = 3;

        InventoryReservation reservation = new InventoryReservation(orderId, productId, qty);
        reservation.setStatus(InventoryReservation.Status.RESERVED);

        when(reservationRepository.findForUpdate(orderId, productId)).thenReturn(Optional.of(reservation));
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
        when(productRepository.save(any(Product.class))).thenReturn(testProduct);

        ProductResponse response = productService.releaseReservation(orderId, productId);

        assertNotNull(response);
        assertEquals(13, testProduct.getStockQuantity());
        assertEquals(InventoryReservation.Status.RELEASED, reservation.getStatus());
        verify(reservationRepository).save(reservation);
    }

    @Test
    @DisplayName("Release reservation is idempotent and prevents double stock restoration")
    void testReleaseReservationIdempotent() {
        Long orderId = 100L;
        Long productId = 1L;
        int qty = 3;

        InventoryReservation alreadyReleased = new InventoryReservation(orderId, productId, qty);
        alreadyReleased.setStatus(InventoryReservation.Status.RELEASED);

        when(reservationRepository.findForUpdate(orderId, productId)).thenReturn(Optional.of(alreadyReleased));
        when(productRepository.findByIdWithCategory(productId)).thenReturn(testProduct);

        ProductResponse response = productService.releaseReservation(orderId, productId);

        assertNotNull(response);
        // Stock must NOT be incremented again
        assertEquals(10, testProduct.getStockQuantity());
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("Commit reservation transitions status to COMMITTED")
    void testCommitReservationSuccess() {
        Long orderId = 100L;
        Long productId = 1L;
        int qty = 2;

        InventoryReservation reservation = new InventoryReservation(orderId, productId, qty);
        reservation.setStatus(InventoryReservation.Status.RESERVED);

        when(reservationRepository.findForUpdate(orderId, productId)).thenReturn(Optional.of(reservation));

        assertDoesNotThrow(() -> productService.commitReservation(orderId, productId));

        assertEquals(InventoryReservation.Status.COMMITTED, reservation.getStatus());
        verify(reservationRepository).save(reservation);
    }
}
