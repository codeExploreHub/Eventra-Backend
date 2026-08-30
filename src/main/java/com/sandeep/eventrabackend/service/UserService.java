package com.sandeep.eventrabackend.service;

import com.sandeep.eventrabackend.dto.request.UserProfileUpdateRequest;
import com.sandeep.eventrabackend.dto.response.UsernameAvailabilityResponse;
import com.sandeep.eventrabackend.dto.response.UserProfileResponse;
import com.sandeep.eventrabackend.exception.InvalidUsernameException;
import com.sandeep.eventrabackend.exception.UserAlreadyExistsException;
import com.sandeep.eventrabackend.model.User;
import com.sandeep.eventrabackend.model.UsernamePolicy;
import com.sandeep.eventrabackend.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }


    @Transactional(readOnly = true)
    public UsernameAvailabilityResponse getUsernameAvailability(
            String authenticatedEmail,
            String candidate
    ) {
        User user = findAuthenticatedUser(authenticatedEmail);
        String username = normalizeAndValidateUsername(candidate);
        boolean available = !userRepository.existsByUsernameNormalizedAndIdNot(
                User.normalizeUsernameKey(username), user.getId());
        return new UsernameAvailabilityResponse(username, available);
    }

    @Transactional
    public UserProfileResponse updateProfile(
            String authenticatedEmail,
            UserProfileUpdateRequest request
    ) {
        User user = findAuthenticatedUser(authenticatedEmail);
        String username = normalizeAndValidateUsername(request.getUsername());

        if (userRepository.existsByUsernameNormalizedAndIdNot(
                User.normalizeUsernameKey(username), user.getId())) {
            throw usernameConflict();
        }

        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setUsername(username);

        try {
            User updatedUser = userRepository.saveAndFlush(user);
            return mapToProfileResponse(updatedUser);
        } catch (DataIntegrityViolationException ex) {
            throw usernameConflict();
        }
    }

    private User findAuthenticatedUser(String authenticatedEmail) {
        return userRepository.findByEmail(authenticatedEmail)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "User not found with email: " + authenticatedEmail));
    }

    private String normalizeAndValidateUsername(String candidate) {
        if (candidate == null) {
            throw new InvalidUsernameException("Username is required");
        }

        String username;
        try {
            username = UsernamePolicy.canonicalize(candidate);
        } catch (IllegalArgumentException ex) {
            throw new InvalidUsernameException(ex.getMessage());
        }

        return username;
    }

    private UserAlreadyExistsException usernameConflict() {
        return new UserAlreadyExistsException("Username is already in use");
    }

    private UserProfileResponse mapToProfileResponse(User user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole() != null ? user.getRole().name() : null)
                .build();
    }
}
