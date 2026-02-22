package com.aleksandarparipovic.marel_app.bonus;

import com.aleksandarparipovic.marel_app.bonus.dto.BonusCategoryDto;
import com.aleksandarparipovic.marel_app.bonus.dto.BonusCategoryOptionDto;
import com.aleksandarparipovic.marel_app.department.dto.DepartmentOptionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.*;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BonusCategoryService {

    private final BonusCategoryRepository repository;
    private final BonusCategoryMapper mapper;


    List<BonusCategoryDto> search(Boolean active,
                                  String code,
                                  LocalDate validOn){
        Specification<BonusCategory> spec = Specification
                .where(BonusCategorySpecifications.notArchived())
                .and(BonusCategorySpecifications.isActive(active))
                .and(BonusCategorySpecifications.hasCode(code))
                .and(BonusCategorySpecifications.validOn(validOn));

        return repository.findAll(spec)
                .stream()
                .map(mapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BonusCategoryOptionDto> getAllActiveBonusCategories(){
        return repository.findByActiveTrueOrderByCategoryNameAsc()
                .stream()
                .map(mapper::toOptionDto)
                .toList();
    }

    @Transactional
    public BonusCategory create(BonusCategory cat) {
        cat.setId(null);
        return repository.save(cat);
    }

    @Transactional
    public BonusCategory update(Long id, BonusCategory updated) {
        BonusCategory existing = repository.findById(id)
                .orElseThrow();

        existing.setCategoryNo(updated.getCategoryNo());
        existing.setCategoryName(updated.getCategoryName());
        existing.setBonusAmount(updated.getBonusAmount());
        existing.setMinHours(updated.getMinHours());
        existing.setDescription(updated.getDescription());
        existing.setActive(updated.isActive());
        existing.setValidFrom(updated.getValidFrom());
        existing.setValidUntil(updated.getValidUntil());

        return repository.save(existing);
    }

    @Transactional
    public void archive(Long id) {
        BonusCategory c = repository.findById(id).orElseThrow();
        c.setArchivedAt(OffsetDateTime.now());
        c.setActive(false);
        repository.save(c);
    }
}
