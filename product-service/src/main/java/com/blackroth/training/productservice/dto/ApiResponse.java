package com.blackroth.training.productservice.dto;

import java.time.LocalDateTime;

public record ApiResponse<T>(boolean success, Integer status, String message, T data, String errorCode,
                             LocalDateTime timestamp) {
    public static <T> ApiResponse<T> success(String m, T d) {
        return new ApiResponse<>(true, 200, m, d, null, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> error(int s, String m, String c) {
        return new ApiResponse<>(false, s, m, null, c, LocalDateTime.now());
    }
}
