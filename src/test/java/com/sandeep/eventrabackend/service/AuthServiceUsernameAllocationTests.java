package com.sandeep.eventrabackend.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.sandeep.eventrabackend.dto.request.GoogleAuthRequest;
import com.sandeep.eventrabackend.dto.request.SignupRequest;
import com.sandeep.eventrabackend.dto.response.AuthResponse;
import com.sandeep.eventrabackend.model.User;
import com.sandeep.eventrabackend.repository.PasswordResetTokenRepository;
import com.sandeep.eventrabackend.repository.UserRepository;
import com.sandeep.eventrabackend.security.JwtTokenProvider;
import com.sandeep.eventrabackend.security.TokenBlacklistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceUsernameAllocationTests {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private GoogleAuthService googleAuthService;
    @Mock
    private TokenBlacklistService tokenBlacklistService;
    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock
    private EmailService emailService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                passwordEncoder,
                authenticationManager,
                jwtTokenProvider,
                googleAuthService,
                tokenBlacklistService,
                passwordResetTokenRepository,
                emailService);
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        when(jwtTokenProvider.generateToken(any(String.class))).thenReturn("token");
    }

    @Test
    void signup_uniqueIndexRace_retriesWithNextSuffix() {
        when(userRepository.existsByEmail("shared@example.net")).thenReturn(false);
        when(userRepository.existsByUsernameNormalized(any())).thenReturn(false);
        stubConcurrentUsernameClaim();

        AuthResponse response = authService.signup(signupRequest("shared@example.net"));

        assertThat(response.getUsername()).isEqualTo("shared1");
    }

    @Test
    void googleLogin_uniqueIndexRace_retriesWithNextSuffix() throws Exception {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setEmail("shared@example.net");
        payload.set("given_name", "Shared");
        payload.set("family_name", "User");
        when(googleAuthService.verifyToken("google-token")).thenReturn(payload);
        when(userRepository.findByEmail("shared@example.net")).thenReturn(Optional.empty());
        when(userRepository.existsByUsernameNormalized(any())).thenReturn(false);
        stubConcurrentUsernameClaim();

        GoogleAuthRequest request = new GoogleAuthRequest();
        request.setToken("google-token");
        AuthResponse response = authService.googleLogin(request);

        assertThat(response.getUsername()).isEqualTo("shared1");
    }

    private void stubConcurrentUsernameClaim() {
        AtomicInteger attempts = new AtomicInteger();
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) {
                throw new DataIntegrityViolationException("simulated username race");
            }
            User saved = invocation.getArgument(0);
            saved.setId(42L);
            return saved;
        });
    }

    private SignupRequest signupRequest(String email) {
        SignupRequest request = new SignupRequest();
        request.setFirstName("Shared");
        request.setLastName("User");
        request.setEmail(email);
        request.setPassword("password123");
        request.setConfirmPassword("password123");
        return request;
    }
}
