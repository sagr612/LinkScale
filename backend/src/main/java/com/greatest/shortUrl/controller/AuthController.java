package com.greatest.shortUrl.controller;

import com.greatest.shortUrl.config.JwtUtils;
import com.greatest.shortUrl.entity.RefreshToken;
import com.greatest.shortUrl.exceptions.UnauthorizedException;
import com.greatest.shortUrl.exceptions.UserAlreadyExistsException;
import com.greatest.shortUrl.model.*;
import com.greatest.shortUrl.services.AuthService;
import com.greatest.shortUrl.services.RefreshTokenService;
import com.greatest.shortUrl.services.UserDetailsImpl;
import com.greatest.shortUrl.services.UserDetailsServiceImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/v1/auth")

public class AuthController {
    private final JwtUtils jwtUtils;
    private final RefreshTokenService refreshTokenService;
    private final UserDetailsServiceImpl userDetailsService;
    private final AuthenticationManager authenticationManager;
    private final AuthService authService;


    @PostMapping("/signup")
    public ResponseEntity<JwtResponseDTO> signUp(@Valid @RequestBody SignUpReq userInfoDto) {

        SignUpResponse response = userDetailsService.signupUser(userInfoDto);

        if (!response.getIsSignedUp()) {
            throw new UserAlreadyExistsException(
                    "User already exists with email: " + userInfoDto.email()
            );
        }


        RefreshToken refreshToken = refreshTokenService.createRefreshToken(userInfoDto.email());
        UserDetailsImpl userDetails = UserDetailsImpl.build(response.getUser());
        String jwtToken = jwtUtils.generateJwtToken(userDetails);

        ResponseCookie cookie = createRefreshTokenCookie(refreshToken.getToken(), 7 * 24 * 60 * 60); //7 days

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(JwtResponseDTO.builder()
                        .accessToken(jwtToken)
                        .token(refreshToken.getToken())
                        .build());
    }


    @PostMapping("/login")
    public ResponseEntity<JwtResponseDTO> AuthenticateAndGetToken(@Valid @RequestBody AuthRequestDTO authRequestDTO) {
        Authentication authentication = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(authRequestDTO.getEmail(), authRequestDTO.getPassword()));
        if (!authentication.isAuthenticated()) {
            throw new UnauthorizedException("Authentication failed");
        }
        UserDetailsImpl user = (UserDetailsImpl) authentication.getPrincipal();
        String jwtToken = jwtUtils.generateJwtToken(user);
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(authRequestDTO.getEmail());

        ResponseCookie cookie = createRefreshTokenCookie(refreshToken.getToken(), 7 * 24 * 60 * 60); //7 days

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(JwtResponseDTO.builder()
                        .accessToken(jwtToken)
                        .build());
    }

    @PostMapping("/refreshToken")
    public ResponseEntity<JwtResponseDTO> refreshToken(@CookieValue(name = "refreshToken", required = false) String tokenFromCookie, @RequestBody(required = false) RefreshTokenRequestDTO request) {

        String token = (tokenFromCookie != null) ? tokenFromCookie : (request != null ? request.getToken() : null);

        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("Refresh Token is missing");
        }

        JwtResponseDTO jwtResponseDTO = refreshTokenService.refreshToken(token);

        ResponseCookie cookie = createRefreshTokenCookie(token, 7 * 24 * 60 * 60);


        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(jwtResponseDTO);

    }

    @PostMapping("/forgot-password")
    public ResponseEntity<String> forgotPassword(@RequestBody @Valid ForgotPasswordRequest request) {
        authService.forgotPassword(request.email());
        return ResponseEntity.ok("Password reset email sent");
    }

    @PostMapping("/reset-password")
    public ResponseEntity<String> resetPassword(@RequestBody @Valid ResetPasswordRequest request) {

        authService.resetPassword(request.token(), request.newPassword());

        return ResponseEntity.ok("Password updated successfully");
    }


    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = "refreshToken", required = false) String token) {
        refreshTokenService.deleteToken(token);
        ResponseCookie cleanCookie = createRefreshTokenCookie("", 0);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cleanCookie.toString())
                .build();
    }

    private ResponseCookie createRefreshTokenCookie(String token, long maxAgeSeconds) {
        return ResponseCookie.from("refreshToken", token)
                .httpOnly(true)
                .secure(false) // with https should be true
                .path("/api/v1/auth")
                .maxAge(maxAgeSeconds)
                .sameSite("Lax")
                .build();
    }


}
