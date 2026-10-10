
package com.blackroth.training.mobilebackend;

import com.blackroth.training.mobilebackend.client.ProductClient;
import com.blackroth.training.mobilebackend.dto.OrderItemRequest;
import com.blackroth.training.mobilebackend.dto.OrderRequest;
import com.blackroth.training.mobilebackend.exception.BusinessException;
import com.blackroth.training.mobilebackend.repository.OrderRepository;
import com.blackroth.training.mobilebackend.repository.OutboxEventRepository;
import com.blackroth.training.mobilebackend.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderServiceUnitTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsWhenProductStockIsInsufficient() {

        OrderRepository orderRepository =
                mock(OrderRepository.class);

        ProductClient productClient =
                mock(ProductClient.class);

        OutboxEventRepository outboxRepository =
                mock(OutboxEventRepository.class);

        ObjectMapper objectMapper = new ObjectMapper();

        ProductClient.ProductResponse product =
                new ProductClient.ProductResponse(
                        1L,
                        "Phone",
                        "",
                        BigDecimal.TEN,
                        0,
                        "Electronics"
                );

        ProductClient.ProductApiResponse response =
                new ProductClient.ProductApiResponse(
                        true,
                        null,
                        "Product fetched successfully",
                        product,
                        null,
                        null
                );

        when(productClient.getProduct(1L))
                .thenReturn(response);

        OrderService service = new OrderService(
                orderRepository,
                productClient,
                outboxRepository,
                objectMapper
        );

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        1L,
                        null,
                        List.of()
                )
        );

        OrderRequest request = new OrderRequest();

        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(1L);
        item.setQuantity(1);

        request.setItems(List.of(item));

        assertThrows(
                BusinessException.class,
                () -> service.createOrder(request)
        );
    }
}
