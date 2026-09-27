package com.company.mobilebackend.dto;

public class ProfileResponse {
    private Long id;
    private String bio;
    private String profilePictureUrl;
    private String dateOfBirth;
    private String gender;

    public ProfileResponse() {
    }

    public ProfileResponse(Long id, String bio, String profilePictureUrl, String dateOfBirth, String gender) {
        this.id = id;
        this.bio = bio;
        this.profilePictureUrl = profilePictureUrl;
        this.dateOfBirth = dateOfBirth;
        this.gender = gender;
    }

    public Long getId() { return id; }
    public String getBio() { return bio; }
    public String getProfilePictureUrl() { return profilePictureUrl; }
    public String getDateOfBirth() { return dateOfBirth; }
    public String getGender() { return gender; }
}