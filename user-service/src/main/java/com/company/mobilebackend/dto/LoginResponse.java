package com.company.mobilebackend.dto;

public class LoginResponse {

    private Long userId;
    private String firstName;
    private String lastName;
    private String email;
    private String role;
    private String accessToken;
    private String refreshToken;

    public LoginResponse() {
    }

    public LoginResponse(Long userId, String firstName, String lastName, String email,
                         String role, String accessToken, String refreshToken) {
        this.userId = userId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.role = role;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }

    public Long getUserId() { return userId; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getEmail() { return email; }
    public String getRole() { return role; }
    public String getAccessToken() { return accessToken; }
    public String getRefreshToken() { return refreshToken; }
}