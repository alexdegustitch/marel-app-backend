package com.aleksandarparipovic.marel_app.auth;

import com.aleksandarparipovic.marel_app.common.WrongPasswordException;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * The caller re-types their password as a signature under a destructive action.
 *
 * <p>One seam instead of the same three lines in every service that archives
 * something (operations and registration requests already carried their own
 * copies; the catalogue archives made it a pattern). Throws
 * {@link WrongPasswordException}, which the global handler answers with
 * {@code code: "WRONG_PASSWORD"} so the form can say so under the field.
 */
@Component
@RequiredArgsConstructor
public class PasswordConfirmationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public void confirm(Authentication authentication, String password) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow();

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new WrongPasswordException("Wrong password");
        }
    }
}
