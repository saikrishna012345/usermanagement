package com.company.mobilebackend.service;

import com.company.mobilebackend.dto.AddressRequest;
import com.company.mobilebackend.dto.AddressResponse;
import com.company.mobilebackend.exception.UserNotFoundException;
import com.company.mobilebackend.model.Address;
import com.company.mobilebackend.model.User;
import com.company.mobilebackend.repository.AddressRepository;
import com.company.mobilebackend.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Service
public class AddressService {

    private final AddressRepository addressRepository;
    private final UserRepository userRepository;

    public AddressService(AddressRepository addressRepository, UserRepository userRepository) {
        this.addressRepository = addressRepository;
        this.userRepository = userRepository;
    }

    public AddressResponse createAddress(Long userId, AddressRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        Address address = new Address(request.getStreet(), request.getCity(),
                request.getState(), request.getPostalCode(), request.getCountry());
        address.setUser(user);

        Address saved = addressRepository.save(address);
        return toResponse(saved);
    }

    public List<AddressResponse> getAddresses(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException("User not found with id: " + userId);
        }
        return addressRepository.findByUserId(userId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public void deleteAddress(Long addressId) {
        if (!addressRepository.existsById(addressId)) {
            throw new NoSuchElementException("Address not found with id: " + addressId);
        }
        addressRepository.deleteById(addressId);
    }

    private AddressResponse toResponse(Address address) {
        return new AddressResponse(address.getId(), address.getStreet(), address.getCity(),
                address.getState(), address.getPostalCode(), address.getCountry());
    }
}