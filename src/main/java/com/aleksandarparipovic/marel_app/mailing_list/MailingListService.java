package com.aleksandarparipovic.marel_app.mailing_list;

import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.config.security.AppPermission;
import com.aleksandarparipovic.marel_app.config.security.PermissionService;
import com.aleksandarparipovic.marel_app.mailing_list.dto.*;
import com.aleksandarparipovic.marel_app.mailing_list_access.MailingListAccess;
import com.aleksandarparipovic.marel_app.mailing_list_access.MailingListAccessRepository;
import com.aleksandarparipovic.marel_app.mailing_list_member.MailingListMember;
import com.aleksandarparipovic.marel_app.mailing_list_member.MailingListMemberRepository;
import com.aleksandarparipovic.marel_app.user.User;
import com.aleksandarparipovic.marel_app.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Mailing lists, their shared-access grants and their members.
 *
 * <p>Every read and write goes through {@link #requireCanRead} or
 * {@link #requireCanEdit}. Guessing an id must never be enough to reach somebody
 * else's private list, so authorization is checked on the loaded row, not inferred
 * from the route.
 */
@Service
@RequiredArgsConstructor
public class MailingListService {

    private final MailingListRepository mailingListRepository;
    private final MailingListMemberRepository memberRepository;
    private final MailingListAccessRepository accessRepository;
    private final UserRepository userRepository;
    private final PermissionService permissionService;

    // ---------- lists ----------

    @Transactional
    public MailingListResponse create(MailingListCreateRequest request, Long ownerId) {
        User owner = loadUser(ownerId);
        String name = request.getName().trim();

        requireGlobalPermissionFor(request.getVisibility());

        if (mailingListRepository
                .existsByOwnerUser_IdAndNameIgnoreCaseAndArchivedAtIsNull(ownerId, name)) {
            throw new ConflictException("Već imate aktivnu listu sa ovim nazivom.");
        }

        MailingList list = mailingListRepository.save(
                MailingList.builder()
                        .name(name)
                        .description(normalize(request.getDescription()))
                        .ownerUser(owner)
                        .visibility(request.getVisibility() == null
                                ? MailingListVisibility.PRIVATE
                                : request.getVisibility())
                        .build()
        );

        return toResponse(list);
    }

    /**
     * A new list of one's own, starting from somebody else's.
     *
     * <p>The reason to copy rather than attach: attaching a list means "these
     * people, as the list defines them". Copying means "these people, as a
     * starting point" — the copier then adds and removes without touching a list
     * other people rely on. A global list edited because one order needed one
     * extra address is how a shared list stops being trustworthy.
     *
     * <p><b>Always PRIVATE, always owned by the copier</b>, whatever the source
     * was. A copy of a global list landing back in the shared pool would put a
     * private edit in front of everybody.
     *
     * <p>Members are copied as they are: a user member stays a user member, so it
     * keeps following that person's address, and an external address is carried
     * across verbatim. Archived members are not copied — they were removed from
     * the source on purpose. `createdBy` on the new rows is the COPIER, because
     * they are the one who put these people on this list.
     *
     * <p>Reading the source is all the source-side permission needed. A caller who
     * may see a list may take its addresses; the copy grants them nothing over the
     * original.
     */
    @Transactional
    public MailingListResponse copy(Long sourceId, MailingListCopyRequest request, Long actorId) {
        MailingList source = loadOrThrow(sourceId);
        requireCanRead(source, actorId);

        User owner = loadUser(actorId);
        String name = resolveCopyName(request, source, actorId);

        MailingList copy = mailingListRepository.save(
                MailingList.builder()
                        .name(name)
                        .description(source.getDescription())
                        .ownerUser(owner)
                        .visibility(MailingListVisibility.PRIVATE)
                        .build()
        );

        for (MailingListMember member : memberRepository.findActiveByMailingListId(sourceId)) {
            memberRepository.save(MailingListMember.builder()
                    .mailingList(copy)
                    .createdBy(owner)
                    .user(member.getUser())
                    .externalEmail(member.getExternalEmail())
                    .displayName(member.getDisplayName())
                    .build());
        }

        return toResponse(copy);
    }

    /**
     * The copy's name, and a free one.
     *
     * <p>Names are unique per owner among active lists, so "Kupci (kopija)" fails
     * the second time somebody copies the same list. Rather than refusing — which
     * reads as the feature being broken when the person did nothing wrong — the
     * suffix counts up. A name the caller typed themselves is NOT adjusted: that
     * one they can see and correct.
     */
    private String resolveCopyName(MailingListCopyRequest request, MailingList source, Long ownerId) {
        String requested = request == null ? null : normalize(request.getName());
        if (requested != null) {
            if (mailingListRepository
                    .existsByOwnerUser_IdAndNameIgnoreCaseAndArchivedAtIsNull(ownerId, requested)) {
                throw new ConflictException("Već imate aktivnu listu sa ovim nazivom.");
            }
            return requested;
        }

        String base = truncateForCopy(source.getName());
        String candidate = base + " (kopija)";
        int n = 2;
        while (mailingListRepository
                .existsByOwnerUser_IdAndNameIgnoreCaseAndArchivedAtIsNull(ownerId, candidate)) {
            candidate = base + " (kopija " + n + ")";
            n++;
        }
        return candidate;
    }

    /** The column holds 150; the suffix has to fit inside it, not past it. */
    private static String truncateForCopy(String name) {
        int room = 150 - " (kopija 99)".length();
        return name.length() <= room ? name : name.substring(0, room).trim();
    }

    @Transactional
    public MailingListResponse update(Long listId, MailingListUpdateRequest request, Long actorId) {
        MailingList list = loadOrThrow(listId);
        requireCanEdit(list, actorId);
        requireNotArchived(list);

        if (request.getName() != null) {
            String name = request.getName().trim();
            if (!name.equalsIgnoreCase(list.getName())
                    && mailingListRepository.existsByOwnerUser_IdAndNameIgnoreCaseAndArchivedAtIsNull(
                            list.getOwnerUser().getId(), name)) {
                throw new ConflictException("Već postoji aktivna lista sa ovim nazivom.");
            }
            list.setName(name);
        }

        if (request.getDescription() != null) {
            list.setDescription(normalize(request.getDescription()));
        }

        if (request.getVisibility() != null && request.getVisibility() != list.getVisibility()) {
            // Promoting a list to GLOBAL is a privileged act, and so is editing one
            // that already is.
            requireGlobalPermissionFor(request.getVisibility());
            list.setVisibility(request.getVisibility());
        }

        return toResponse(list);
    }

    /**
     * Archives the list. Members and production-order history are untouched — an
     * archived list simply stops being offered for new orders.
     */
    @Transactional
    public MailingListResponse archive(Long listId, Long actorId) {
        MailingList list = loadOrThrow(listId);
        requireCanEdit(list, actorId);

        if (!list.isArchived()) {
            list.setArchivedAt(OffsetDateTime.now());
        }

        return toResponse(list);
    }

    @Transactional(readOnly = true)
    public Page<MailingListResponse> listAccessible(Long userId, Pageable pageable) {
        boolean canSeeGlobal =
                permissionService.hasPermission(AppPermission.MAILING_LIST_GLOBAL_MANAGE);
        return mailingListRepository.findAccessible(userId, canSeeGlobal, pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public MailingListResponse getById(Long listId, Long actorId) {
        MailingList list = loadOrThrow(listId);
        requireCanRead(list, actorId);
        return toResponse(list);
    }

    // ---------- shared access ----------

    @Transactional
    public void grantAccess(Long listId, Long targetUserId, Long actorId) {
        MailingList list = loadOrThrow(listId);
        requireCanEdit(list, actorId);
        requireNotArchived(list);

        if (list.getVisibility() != MailingListVisibility.SHARED) {
            throw new ConflictException(
                    "Deljenje je moguće samo za listu sa vidljivošću SHARED.");
        }

        if (accessRepository.existsByMailingList_IdAndUser_Id(listId, targetUserId)) {
            return; // Idempotent: granting twice is not an error.
        }

        accessRepository.save(MailingListAccess.builder()
                .mailingList(list)
                .user(loadUser(targetUserId))
                .grantedBy(loadUser(actorId))
                .build());
    }

    @Transactional
    public void revokeAccess(Long listId, Long targetUserId, Long actorId) {
        MailingList list = loadOrThrow(listId);
        requireCanEdit(list, actorId);
        accessRepository.deleteByMailingList_IdAndUser_Id(listId, targetUserId);
    }

    // ---------- members ----------

    @Transactional
    public MailingListMemberResponse addMember(
            Long listId, MailingListMemberCreateRequest request, Long actorId
    ) {
        MailingList list = loadOrThrow(listId);
        requireCanEdit(list, actorId);
        requireNotArchived(list);

        boolean hasUser = request.getUserId() != null;
        boolean hasEmail = request.getExternalEmail() != null && !request.getExternalEmail().isBlank();

        if (hasUser == hasEmail) {
            throw new IllegalArgumentException(
                    "Član mora da bude ili korisnik aplikacije ili spoljna email adresa, ne oba.");
        }

        MailingListMember.MailingListMemberBuilder member = MailingListMember.builder()
                .mailingList(list)
                .createdBy(loadUser(actorId))
                .displayName(normalize(request.getDisplayName()));

        if (hasUser) {
            User user = loadUser(request.getUserId());
            if (memberRepository.existsByMailingList_IdAndUser_IdAndArchivedAtIsNull(
                    listId, user.getId())) {
                throw new ConflictException("Ovaj korisnik je već član liste.");
            }
            requireEmailNotAlreadyPresent(listId, normalizeEmail(user.getEmailAddress()));
            member.user(user);
        } else {
            String email = normalizeEmail(request.getExternalEmail());
            requireEmailNotAlreadyPresent(listId, email);
            member.externalEmail(email);
        }

        return toMemberResponse(memberRepository.save(member.build()));
    }

    /**
     * The full duplicate check across both member kinds.
     *
     * <p>The database can only stop "same user twice" and "same external address
     * twice" separately. It cannot see that an external address equals some user
     * member's current address, because that address lives in users.email_address.
     * That case is caught here.
     */
    private void requireEmailNotAlreadyPresent(Long listId, String email) {
        if (email == null) {
            return;
        }
        if (memberRepository.existsByMailingList_IdAndExternalEmailAndArchivedAtIsNull(listId, email)
                || memberRepository.existsActiveUserMemberWithEmail(listId, email)) {
            throw new ConflictException("Ova email adresa je već član liste.");
        }
    }

    /** Removal is an archive, so the membership stays auditable. */
    @Transactional
    public void removeMember(Long listId, Long memberId, Long actorId) {
        MailingList list = loadOrThrow(listId);
        requireCanEdit(list, actorId);

        MailingListMember member = memberRepository.findDetailById(memberId)
                .orElseThrow(() -> new EntityNotFoundException("Član nije pronađen: " + memberId));

        if (!member.getMailingList().getId().equals(listId)) {
            throw new IllegalArgumentException("Član ne pripada ovoj listi.");
        }

        if (member.getArchivedAt() == null) {
            member.setArchivedAt(OffsetDateTime.now());
        }
    }

    @Transactional(readOnly = true)
    public List<MailingListMemberResponse> listMembers(Long listId, Long actorId) {
        MailingList list = loadOrThrow(listId);
        requireCanRead(list, actorId);
        return memberRepository.findActiveByMailingListId(listId).stream()
                .map(this::toMemberResponse)
                .toList();
    }

    /**
     * Active members for snapshotting, excluding users whose account can no longer
     * receive mail. They stay visible in the list's own membership view — only new
     * deliveries skip them.
     */
    @Transactional(readOnly = true)
    public List<MailingListMember> activeDeliverableMembers(Long listId) {
        return memberRepository.findActiveByMailingListId(listId).stream()
                .filter(m -> m.getUser() == null || isDeliverable(m.getUser()))
                .filter(m -> m.effectiveEmail() != null)
                .toList();
    }

    private static boolean isDeliverable(User user) {
        return user.getAccountStatus() == com.aleksandarparipovic.marel_app.user.UserAccountStatus.ACTIVE;
    }

    // ---------- authorization ----------

    /**
     * Read access. PRIVATE is owner-only; SHARED needs a grant; GLOBAL needs the
     * permission.
     */
    public void requireCanRead(MailingList list, Long actorId) {
        if (isOwner(list, actorId)) {
            return;
        }

        boolean allowed = switch (list.getVisibility()) {
            case PRIVATE -> false;
            case SHARED -> accessRepository.existsByMailingList_IdAndUser_Id(list.getId(), actorId);
            case GLOBAL -> permissionService.hasPermission(AppPermission.MAILING_LIST_GLOBAL_MANAGE);
        };

        if (!allowed) {
            // Deliberately the same message whether the list is private or missing,
            // so ids cannot be probed for existence.
            throw new AccessDeniedException("Nemate pristup ovoj mailing listi.");
        }
    }

    /** Editing is the owner's right; GLOBAL lists additionally need the permission. */
    public void requireCanEdit(MailingList list, Long actorId) {
        if (list.getVisibility() == MailingListVisibility.GLOBAL) {
            if (!permissionService.hasPermission(AppPermission.MAILING_LIST_GLOBAL_MANAGE)) {
                throw new AccessDeniedException("Za izmenu globalne liste potrebna je dozvola.");
            }
            return;
        }

        if (!isOwner(list, actorId)) {
            throw new AccessDeniedException("Samo vlasnik može da menja ovu mailing listu.");
        }
    }

    private static boolean isOwner(MailingList list, Long actorId) {
        return list.getOwnerUser().getId().equals(actorId);
    }

    private void requireGlobalPermissionFor(MailingListVisibility visibility) {
        if (visibility == MailingListVisibility.GLOBAL
                && !permissionService.hasPermission(AppPermission.MAILING_LIST_GLOBAL_MANAGE)) {
            throw new AccessDeniedException("Nemate dozvolu za globalne mailing liste.");
        }
    }

    private static void requireNotArchived(MailingList list) {
        if (list.isArchived()) {
            throw new ConflictException("Lista je arhivirana i ne može da se menja.");
        }
    }

    // ---------- helpers ----------

    public MailingList loadOrThrow(Long listId) {
        return mailingListRepository.findDetailById(listId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Mailing lista nije pronađena: " + listId));
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Korisnik nije pronađen: " + userId));
    }

    static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private MailingListResponse toResponse(MailingList list) {
        return new MailingListResponse(
                list.getId(),
                list.getName(),
                list.getDescription(),
                list.getOwnerUser().getId(),
                list.getOwnerUser().getFullName(),
                list.getVisibility(),
                memberRepository.findActiveByMailingListId(list.getId()).size(),
                list.isArchived(),
                list.getCreatedAt()
        );
    }

    private MailingListMemberResponse toMemberResponse(MailingListMember member) {
        return new MailingListMemberResponse(
                member.getId(),
                member.getUser() == null ? null : member.getUser().getId(),
                member.getExternalEmail(),
                member.effectiveEmail(),
                member.effectiveName(),
                member.getArchivedAt() != null,
                member.getCreatedAt()
        );
    }
}
