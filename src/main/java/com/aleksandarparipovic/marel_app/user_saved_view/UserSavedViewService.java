package com.aleksandarparipovic.marel_app.user_saved_view;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import com.aleksandarparipovic.marel_app.user_saved_view.dto.SavedViewRequest;
import com.aleksandarparipovic.marel_app.user_saved_view.dto.SavedViewResponse;
import com.aleksandarparipovic.marel_app.user_table_preferences.TableKey;
import com.aleksandarparipovic.marel_app.common.JsonPayloads;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Saved views.
 *
 * <p>Shared saved views are deliberately NOT implemented — nothing currently
 * requires them, and adding sharing speculatively would mean an access model with
 * no requirements to validate it against.
 */
@Service
@RequiredArgsConstructor
public class UserSavedViewService {

    private static final int MAX_FILTERS_BYTES = 32 * 1024;
    private static final int MAX_ARRAY_BYTES = 8 * 1024;

    private final UserSavedViewRepository repository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<SavedViewResponse> list(Long userId, String rawViewKey) {
        String viewKey = normalizeViewKey(rawViewKey);
        return repository
                .findByUser_IdAndViewKeyAndArchivedAtIsNullOrderByNameAsc(userId, viewKey)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public SavedViewResponse create(Long userId, String rawViewKey, SavedViewRequest request) {
        String viewKey = normalizeViewKey(rawViewKey);
        String name = request.getName().trim();

        if (repository.existsByUser_IdAndViewKeyAndNameIgnoreCaseAndArchivedAtIsNull(
                userId, viewKey, name)) {
            throw new ConflictException("Već imate prikaz sa ovim nazivom.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Korisnik nije pronađen: " + userId));

        boolean makeDefault = Boolean.TRUE.equals(request.getIsDefault());
        if (makeDefault) {
            repository.clearDefault(userId, viewKey);
        }

        UserSavedView view = repository.save(UserSavedView.builder()
                .user(user)
                .viewKey(viewKey)
                .name(name)
                .filters(validateObject(request.getFilters(), MAX_FILTERS_BYTES, "filteri"))
                .sorting(validateArray(request.getSorting(), "sortiranje"))
                .columns(validateArray(request.getColumns(), "kolone"))
                .isDefault(makeDefault)
                .build());

        return toResponse(view);
    }

    @Transactional
    public SavedViewResponse update(Long viewId, Long userId, SavedViewRequest request) {
        UserSavedView view = loadOwned(viewId, userId);

        if (view.isArchived()) {
            throw new ConflictException("Arhiviran prikaz ne može da se menja.");
        }

        String name = request.getName().trim();
        if (!name.equalsIgnoreCase(view.getName())
                && repository.existsByUser_IdAndViewKeyAndNameIgnoreCaseAndArchivedAtIsNull(
                        userId, view.getViewKey(), name)) {
            throw new ConflictException("Već imate prikaz sa ovim nazivom.");
        }
        view.setName(name);

        if (request.getFilters() != null) {
            view.setFilters(validateObject(request.getFilters(), MAX_FILTERS_BYTES, "filteri"));
        }
        if (request.getSorting() != null) {
            view.setSorting(validateArray(request.getSorting(), "sortiranje"));
        }
        if (request.getColumns() != null) {
            view.setColumns(validateArray(request.getColumns(), "kolone"));
        }

        if (Boolean.TRUE.equals(request.getIsDefault()) && !view.getIsDefault()) {
            setDefaultInternal(view, userId);
        }

        return toResponse(view);
    }

    /**
     * Promotes a view to default.
     *
     * <p>Clearing the old default and setting the new one happen in ONE transaction,
     * so there is never a moment with two defaults — and never a moment with none,
     * had it been done in the other order across two transactions.
     */
    @Transactional
    public SavedViewResponse setDefault(Long viewId, Long userId) {
        UserSavedView view = loadOwned(viewId, userId);

        if (view.isArchived()) {
            throw new ConflictException("Arhiviran prikaz ne može da bude podrazumevani.");
        }

        setDefaultInternal(view, userId);
        return toResponse(view);
    }

    private void setDefaultInternal(UserSavedView view, Long userId) {
        repository.clearDefault(userId, view.getViewKey());
        view.setIsDefault(true);
    }

    @Transactional
    public void archive(Long viewId, Long userId) {
        UserSavedView view = loadOwned(viewId, userId);

        if (!view.isArchived()) {
            // An archived view must not remain the default; the database check
            // chk_user_saved_views_default_not_archived would reject it anyway.
            view.setIsDefault(false);
            view.setArchivedAt(OffsetDateTime.now());
        }
    }

    private UserSavedView loadOwned(Long viewId, Long userId) {
        UserSavedView view = repository.findById(viewId)
                .orElseThrow(() -> new EntityNotFoundException("Prikaz nije pronađen: " + viewId));

        if (!view.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("Nemate pristup ovom prikazu.");
        }

        return view;
    }

    /** View keys reuse the table registry — the same closed set of screens. */
    private static String normalizeViewKey(String rawViewKey) {
        return TableKey.fromKey(rawViewKey).getKey();
    }

    private static JsonNode validateObject(
            java.util.Map<String, Object> value, int maxBytes, String label) {
        if (value == null) {
            return JsonPayloads.emptyObject();
        }
        JsonNode node = JsonPayloads.toNode(value);
        if (!node.isObject()) {
            throw new IllegalArgumentException(label + " moraju da budu JSON objekat.");
        }
        requireSize(node, maxBytes, label);
        return node;
    }

    private static JsonNode validateArray(java.util.List<Object> value, String label) {
        if (value == null) {
            return JsonPayloads.emptyArray();
        }
        JsonNode node = JsonPayloads.toNode(value);
        if (!node.isArray()) {
            throw new IllegalArgumentException(label + " moraju da budu JSON niz.");
        }
        requireSize(node, MAX_ARRAY_BYTES, label);
        return node;
    }

    private static void requireSize(JsonNode node, int maxBytes, String label) {
        if (JsonPayloads.byteSize(node) > maxBytes) {
            throw new IllegalArgumentException(
                    label + " su preveliki (maksimum " + maxBytes + " bajtova).");
        }
    }

    private SavedViewResponse toResponse(UserSavedView v) {
        return new SavedViewResponse(
                v.getId(),
                v.getViewKey(),
                v.getName(),
                JsonPayloads.toMap(v.getFilters()),
                JsonPayloads.toList(v.getSorting()),
                JsonPayloads.toList(v.getColumns()),
                Boolean.TRUE.equals(v.getIsDefault()),
                v.isArchived(),
                v.getCreatedAt()
        );
    }
}
