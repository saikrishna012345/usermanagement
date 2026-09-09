package com.company.mobilebackend.dto;

import java.time.LocalDateTime;

public class ApiResponse<T> {

    private boolean success;
    private Integer status;
    private String message;
    private T data;
    private String errorCode;
    private LocalDateTime timestamp;

    private ApiResponse() {
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = true;
        response.message = message;
        response.data = data;
        return response;
    }

    public static <T> ApiResponse<T> error(String message, String errorCode, int status) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = false;
        response.message = message;
        response.errorCode = errorCode;
        response.status = status;
        response.timestamp = LocalDateTime.now();
        return response;
    }

    public void setData(T data) {
        this.data = data;
    }

    public boolean isSuccess() {
        return success;
    }

    public Integer getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }
}