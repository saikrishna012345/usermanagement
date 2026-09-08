package com.company.mobilebackend.controller;

import com.company.mobilebackend.dto.AddressRequest;
import com.company.mobilebackend.dto.AddressResponse;
import com.company.mobilebackend.dto.ApiResponse;
import com.company.mobilebackend.service.AddressService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @PostMapping("/api/users/{id}/addresses")
    public ResponseEntity<ApiResponse<AddressResponse>> createAddress(
            @PathVariable Long id, @Valid @RequestBody AddressRequest request) {
        AddressResponse created = addressService.createAddress(id, request);
        ApiResponse<AddressResponse> response = ApiResponse.success("Address created successfully", created);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping("/api/users/{id}/addresses")
    public ResponseEntity<ApiResponse<List<AddressResponse>>> getAddresses(@PathVariable Long id) {
        List<AddressResponse> addresses = addressService.getAddresses(id);
        ApiResponse<List<AddressResponse>> response = ApiResponse.success("Addresses fetched successfully", addresses);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/api/addresses/{id}")
    public ResponseEntity<Void> deleteAddress(@PathVariable Long id) {
        addressService.deleteAddress(id);
        return ResponseEntity.noContent().build();
    }
}