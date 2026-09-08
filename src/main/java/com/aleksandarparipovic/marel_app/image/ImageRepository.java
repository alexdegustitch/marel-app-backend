package com.aleksandarparipovic.marel_app.image;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ImageRepository extends JpaRepository<Image, Long> {

    List<Image> findByProductFamily_IdOrderBySortOrderAscIdAsc(Long familyId);

    List<Image> findByProductType_IdOrderBySortOrderAscIdAsc(Long typeId);

    List<Image> findByProduct_IdOrderBySortOrderAscIdAsc(Long productId);

    /** How many image links still point at a file — 0 after a detach means the file is now an orphan. */
    long countByFile_Id(Long fileId);
}
