package com.storagehub.auth;

import com.storagehub.auth.dto.RegisterRequest;
import com.storagehub.auth.dto.UserResponse;
import com.storagehub.common.exception.DuplicateEmailException;
import com.storagehub.user.Role;
import com.storagehub.user.User;
import com.storagehub.user.UserRepository;
import com.storagehub.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder);
    }

    @Test
    void registerCreatesActiveCustomerWithNormalizedEmailAndHashedPassword() {
        RegisterRequest request = new RegisterRequest(
                "  Nguyen Van A  ",
                "  User@Example.com ",
                " 0901234567 ",
                "Password@123"
        );
        when(userRepository.existsByEmailIgnoreCase("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Password@123")).thenReturn("bcrypt-hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 15L);
            return user;
        });

        UserResponse response = authService.register(request);

        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getFullName()).isEqualTo("Nguyen Van A");
        assertThat(savedUser.getValue().getEmail()).isEqualTo("user@example.com");
        assertThat(savedUser.getValue().getPasswordHash()).isEqualTo("bcrypt-hash");
        assertThat(savedUser.getValue().getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(savedUser.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.id()).isEqualTo(15L);
        assertThat(response.email()).isEqualTo("user@example.com");
        assertThat(response.role()).isEqualTo(Role.CUSTOMER);
    }

    @Test
    void registerRejectsDuplicateEmailWithoutEncodingOrSavingPassword() {
        RegisterRequest request = new RegisterRequest(
                "Nguyen Van A",
                "user@example.com",
                "0901234567",
                "Password@123"
        );
        when(userRepository.existsByEmailIgnoreCase("user@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(DuplicateEmailException.class)
                .hasMessage("Email is already registered");

        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).save(any());
    }
}
