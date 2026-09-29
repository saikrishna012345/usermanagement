package com.blackroth.training.mobilebackend.service;

import com.blackroth.training.mobilebackend.dto.ProfileRequest;
import com.blackroth.training.mobilebackend.dto.ProfileResponse;
import com.blackroth.training.mobilebackend.exception.UserNotFoundException;
import com.blackroth.training.mobilebackend.model.Profile;
import com.blackroth.training.mobilebackend.model.User;
import com.blackroth.training.mobilebackend.repository.ProfileRepository;
import com.blackroth.training.mobilebackend.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class ProfileService {

    private final ProfileRepository profileRepository;
    private final UserRepository userRepository;

    public ProfileService(ProfileRepository profileRepository, UserRepository userRepository) {
        this.profileRepository = profileRepository;
        this.userRepository = userRepository;
    }

    public ProfileResponse createProfile(Long userId, ProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        Profile profile = new Profile(request.getBio(), request.getProfilePictureUrl(),
                request.getDateOfBirth(), request.getGender());
        profile.setUser(user);

        Profile saved = profileRepository.save(profile);
        return toResponse(saved);
    }

    public ProfileResponse getProfile(Long userId) {
        Profile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new UserNotFoundException("Profile not found for user id: " + userId));
        return toResponse(profile);
    }

    private ProfileResponse toResponse(Profile profile) {
        return new ProfileResponse(profile.getId(), profile.getBio(),
                profile.getProfilePictureUrl(), profile.getDateOfBirth(), profile.getGender());
    }
}