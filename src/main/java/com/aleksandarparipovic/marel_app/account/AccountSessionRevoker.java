package com.aleksandarparipovic.marel_app.account;

import com.aleksandarparipovic.marel_app.auth.refresh.RefreshTokenRepository;
import com.aleksandarparipovic.marel_app.user_session.UserSession;
import com.aleksandarparipovic.marel_app.user_session.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Ends every session but the one asking.
 *
 * <p>Used after a sign-in credential changes. If the change was made by somebody
 * who should not have been able to, the sessions they were using are the ones
 * that stop working; and if it WAS the owner, a device they signed in on months
 * ago and forgot about is no longer holding a session against the old address.
 *
 * <p>Both halves are revoked, and they are different things: the {@code user_sessions}
 * row is what the "where am I signed in" screen reads, and the refresh-token
 * family is what actually mints new access tokens. Revoking only the session row
 * would leave a login that keeps refreshing itself and simply stops appearing in
 * the list.
 *
 * <p>Access tokens already issued are NOT revoked — they are self-contained and
 * short-lived by design. The window is the access token's remaining lifetime,
 * after which the refresh is refused and the session is over.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountSessionRevoker {

    private final UserSessionRepository sessionRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * @param keepSessionId the family id of the session doing this, which stays.
     *                      Null keeps nothing — the caller could not identify
     *                      itself, and ending one session too many is the safe
     *                      direction to be wrong in.
     */
    @Transactional
    public void endOtherSessions(Long userId, String keepSessionId, String reason) {
        OffsetDateTime now = OffsetDateTime.now();
        List<UserSession> live = sessionRepository.findLiveByUserId(userId);

        int ended = 0;
        for (UserSession session : live) {
            if (keepSessionId != null && keepSessionId.equals(session.getFamilyId())) {
                continue;
            }

            session.setRevokedAt(now);
            refreshTokenRepository.revokeAllByFamilyId(session.getFamilyId(), now, reason);
            ended++;
        }

        if (ended > 0) {
            log.info("Ended {} other session(s) for user {} after {}", ended, userId, reason);
        }
    }
}
