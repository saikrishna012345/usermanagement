package com.blackroth.training.orderservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public record OrderRequest(@NotNull Long userId, @NotEmpty List<@Valid OrderItemRequest> items) {

}
