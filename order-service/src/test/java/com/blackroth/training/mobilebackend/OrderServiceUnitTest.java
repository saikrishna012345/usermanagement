package com.blackroth.training.mobilebackend;

import com.blackroth.training.mobilebackend.client.ProductClient;
import com.blackroth.training.mobilebackend.dto.OrderItemRequest;
import com.blackroth.training.mobilebackend.dto.OrderRequest;
import com.blackroth.training.mobilebackend.exception.BusinessException;
import com.blackroth.training.mobilebackend.repository.OrderRepository;
import com.blackroth.training.mobilebackend.service.OrderService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

class OrderServiceUnitTest {
    @Test
    void rejectsWhenProductStockIsInsufficient() {
        OrderRepository repo = Mockito.mock(OrderRepository.class);
        ProductClient client = Mockito.mock(ProductClient.class);
        when(client.getProduct(1L)).thenReturn(new ProductClient.ProductResponse(1L, "Phone", "", BigDecimal.TEN, 0, "Electronics"));
        OrderService service = new OrderService(repo, client);
        OrderRequest req = new OrderRequest();
        req.setUserId(1L);
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(1L);
        item.setQuantity(1);
        req.setItems(List.of(item));
        assertThrows(BusinessException.class, () -> service.createOrder(req));
    }
}
