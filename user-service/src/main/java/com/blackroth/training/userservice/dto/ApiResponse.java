package com.blackroth.training.userservice.dto;

import java.time.LocalDateTime;

public record ApiResponse<T>(
        boolean success, Integer
status,
        String message, T
data,
        String errorCode, LocalDateTime
timestamp){

public static <T> ApiResponse<T> success(String message, T data) {
    return new ApiResponse<>(true, 200, message, data, null, LocalDateTime.now());
}

public static <T> ApiResponse<T> error(int status, String message, String code) {
    return new ApiResponse<>(false, status, message, null, code, LocalDateTime.now());
}
}
