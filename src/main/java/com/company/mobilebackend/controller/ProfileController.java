package com.company.mobilebackend.controller;

import com.company.mobilebackend.dto.ApiResponse;
import com.company.mobilebackend.dto.ProfileRequest;
import com.company.mobilebackend.dto.ProfileResponse;
import com.company.mobilebackend.service.ProfileService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/{id}/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProfileResponse>> createProfile(
            @PathVariable Long id, @Valid @RequestBody ProfileRequest request) {
        ProfileResponse created = profileService.createProfile(id, request);
        ApiResponse<ProfileResponse> response = ApiResponse.success("Profile created successfully", created);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<ProfileResponse>> getProfile(@PathVariable Long id) {
        ProfileResponse profile = profileService.getProfile(id);
        ApiResponse<ProfileResponse> response = ApiResponse.success("Profile fetched successfully", profile);
        return ResponseEntity.ok(response);
    }
}