package com.company.mobilebackend.exception;
import com.company.mobilebackend.dto.ApiResponse;
import org.springframework.http.*; import org.springframework.security.access.AccessDeniedException; import org.springframework.validation.FieldError; import org.springframework.web.bind.MethodArgumentNotValidException; import org.springframework.web.bind.annotation.*; import java.util.*;
@RestControllerAdvice public class GlobalExceptionHandler {
 @ExceptionHandler(ResourceNotFoundException.class) public ResponseEntity<ApiResponse<Object>> notFound(ResourceNotFoundException e){return build(e.getMessage(),"RESOURCE_NOT_FOUND",HttpStatus.NOT_FOUND);}
 @ExceptionHandler(BusinessException.class) public ResponseEntity<ApiResponse<Object>> business(BusinessException e){return build(e.getMessage(),"BUSINESS_RULE_VIOLATION",HttpStatus.CONFLICT);}
 @ExceptionHandler(InvalidRequestException.class) public ResponseEntity<ApiResponse<Object>> invalid(InvalidRequestException e){return build(e.getMessage(),"INVALID_REQUEST",HttpStatus.BAD_REQUEST);}
 @ExceptionHandler(AccessDeniedException.class) public ResponseEntity<ApiResponse<Object>> denied(AccessDeniedException e){return build("You do not have permission to access this resource","FORBIDDEN",HttpStatus.FORBIDDEN);}
 @ExceptionHandler(MethodArgumentNotValidException.class) public ResponseEntity<ApiResponse<Object>> validation(MethodArgumentNotValidException e){Map<String,String> m=new HashMap<>(); for(FieldError f:e.getBindingResult().getFieldErrors())m.put(f.getField(),f.getDefaultMessage()); ApiResponse<Object> r=ApiResponse.error("Validation failed","VALIDATION_ERROR",400);r.setData(m);return new ResponseEntity<>(r,HttpStatus.BAD_REQUEST);}
 @ExceptionHandler(Exception.class) public ResponseEntity<ApiResponse<Object>> generic(Exception e){return build("Something went wrong. Please try again later.","INTERNAL_SERVER_ERROR",HttpStatus.INTERNAL_SERVER_ERROR);}
 private ResponseEntity<ApiResponse<Object>> build(String m,String c,HttpStatus s){return new ResponseEntity<>(ApiResponse.error(m,c,s.value()),s);}
}
