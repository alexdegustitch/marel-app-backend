package com.aleksandarparipovic.marel_app.compensation_scheme;

import com.aleksandarparipovic.marel_app.auth.PasswordConfirmationService;
import com.aleksandarparipovic.marel_app.common.ConflictException;
import com.aleksandarparipovic.marel_app.compensation_scheme.dto.CompensationSchemeDto;
import com.aleksandarparipovic.marel_app.employee_compensation_scheme_history.EmployeeCompensationSchemeHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * The šifarnik's administration of compensation schemes.
 *
 * <p>Schemes stay code-referenced constants: their code and the calculation
 * flags are seeded and never edited here. What the catalogue screen may do is
 * deliberately narrow — reword the NOTE, and archive/restore a scheme nobody
 * is on, which only removes it from the assignment pickers. Payroll history
 * keeps referencing an archived scheme by id.
 */
@Service
@RequiredArgsConstructor
public class CompensationSchemeAdminService {

    private final CompensationSchemeRepository repository;
    private final EmployeeCompensationSchemeHistoryRepository historyRepository;
    private final PasswordConfirmationService passwordConfirmation;

    /** Every scheme, archived ones included — the catalogue's own listing. */
    @Transactional(readOnly = true)
    public List<CompensationSchemeDto> listAll() {
        return repository.findAll().stream()
                .sorted((a, b) -> a.getCode().compareToIgnoreCase(b.getCode()))
                .map(CompensationSchemeDto::from)
                .toList();
    }

    @Transactional
    public CompensationSchemeDto updateNote(Long id, String note) {
        CompensationScheme scheme = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Tip obračuna nije pronađen."));
        String trimmed = note == null ? null : note.trim();
        scheme.setNote(trimmed == null || trimmed.isEmpty() ? null : trimmed);
        return CompensationSchemeDto.from(repository.save(scheme));
    }

    /** Archive, signed with the caller's re-typed password. */
    @Transactional
    public void archive(Long id, String password, Authentication authentication) {
        passwordConfirmation.confirm(authentication, password);
        CompensationScheme scheme = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Tip obračuna nije pronađen."));
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Belgrade"));
        if (historyRepository.existsInForceOnOrAfter(id, today)) {
            throw new ConflictException(
                    "Tip obračuna se ne može arhivirati — trenutno je (ili će tek biti) dodeljen radnicima. "
                            + "Prvo prebacite te radnike na drugi tip obračuna.");
        }
        scheme.setArchivedAt(OffsetDateTime.now());
        scheme.setIsActive(false);
        repository.save(scheme);
    }

    @Transactional
    public void restore(Long id) {
        CompensationScheme scheme = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Tip obračuna nije pronađen."));
        scheme.setArchivedAt(null);
        scheme.setIsActive(true);
        repository.save(scheme);
    }
}
