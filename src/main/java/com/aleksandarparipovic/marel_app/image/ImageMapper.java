package com.aleksandarparipovic.marel_app.image;

import com.aleksandarparipovic.marel_app.file.FileMetadata;
import com.aleksandarparipovic.marel_app.image.dto.ImageDto;
import org.springframework.stereotype.Component;

@Component
public class ImageMapper {

    public ImageDto toDto(Image image) {
        if (image == null) return null;
        FileMetadata file = image.getFile();

        return ImageDto.builder()
                .id(image.getId())
                .fileId(file != null ? file.getId() : null)
                .downloadUrl(file != null ? "/api/files/" + file.getId() + "/content" : null)
                .originalFilename(file != null ? file.getOriginalFilename() : null)
                .contentType(file != null ? file.getContentType() : null)
                .altText(image.getAltText())
                .primary(image.getIsPrimary())
                .sortOrder(image.getSortOrder())
                .build();
    }
}
