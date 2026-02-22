package com.aleksandarparipovic.marel_app.audit;

import com.aleksandarparipovic.marel_app.auth.CustomUserDetails;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Aspect
@Component
@RequiredArgsConstructor
public class AuditUserAspect {

    private final EntityManager entityManager;

    @Before("within(@org.springframework.stereotype.Service *)")
    public void setAuditUser() {
        System.out.println("Izvrsilo se");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        System.out.println("Transakcija aktivna");
        Long userId = getCurrentUserId();
        if (userId == null) return;

        entityManager
                .createNativeQuery(
                        "SELECT set_config('app.user_id', ?1, true)"
                )
                .setParameter(1, userId.toString())
                .getSingleResult();

    }

    private Long getCurrentUserId() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            System.out.println("SOMETHING IS NULL");
            return null;
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomUserDetails user) {
            System.out.println("USER: " + user.getId() + ", " + user.getUsername());
            return user.getId();
        }

        if (principal instanceof String principalName && "anonymousUser".equals(principalName)) {
            System.out.println("SOMETHING IS NULL");
            return null;
        }

        System.out.println("Unsupported principal type: " + principal.getClass().getName());
        return null;
    }
}
