package com.aleksandarparipovic.marel_app.common.jpa;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class EntityReferenceProvider {

    private final EntityManager entityManager;

    public <T> T getRequiredReference(Class<T> type, Long id, String fieldName) {
        if (id == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }

        return entityManager.getReference(type, id);
    }

    public <T> T getOptionalReference(Class<T> type, Long id) {
        if (id == null) {
            return null;
        }

        return entityManager.getReference(type, id);
    }
}
