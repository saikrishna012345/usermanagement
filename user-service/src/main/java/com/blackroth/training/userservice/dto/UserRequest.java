package com.blackroth.training.userservice.dto;

import jakarta.validation.constraints.*;

public class UserRequest {
    @NotBlank
    private String firstName;
    @NotBlank
    private String lastName;
    @NotBlank
    @Email
    private String email;
    @NotBlank
    @Pattern(regexp = "^[0-9]{10}$", message = "mobile number must be 10 digits")
    private String mobileNumber;
    @Size(min = 8, message = "password must contain at least 8 characters")
    private String password;
    private String role;
    private String status;

    public UserRequest() {
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String v) {
        firstName = v;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String v) {
        lastName = v;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String v) {
        email = v;
    }

    public String getMobileNumber() {
        return mobileNumber;
    }

    public void setMobileNumber(String v) {
        mobileNumber = v;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String v) {
        password = v;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String v) {
        role = v;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        status = v;
    }
}
