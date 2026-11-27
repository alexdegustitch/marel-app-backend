package com.aleksandarparipovic.marel_app.operation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** The wire shape of operation groups: one DTO with members, two requests. */
public final class OperationGroupDtos {

    private OperationGroupDtos() {
    }

    @Getter
    @Builder
    public static class OperationGroupDto {
        private Long id;
        private Long productId;
        private String name;
        private String note;
        private Boolean active;
        private boolean archived;
        private List<OperationGroupMemberDto> members;
    }

    @Getter
    @Builder
    public static class OperationGroupMemberDto {
        private Long operationId;
        private String operationName;
        private Integer minNorm;
        private Integer maxNorm;
        private Integer unitsPerProduct;
        private Integer sortOrder;
        private boolean groupDefault;
        private boolean archived;
    }

    @Getter
    @Setter
    public static class OperationGroupCreateRequest {
        @NotNull(message = "Grupa mora pripadati proizvodu.")
        private Long productId;
        @NotBlank(message = "Naziv grupe je obavezan.")
        @Size(max = 255, message = "Naziv grupe je predugačak.")
        private String name;
        private String note;
    }

    /** Null means "leave it". The group stays with its product. */
    @Getter
    @Setter
    public static class OperationGroupUpdateRequest {
        @Size(max = 255, message = "Naziv grupe je predugačak.")
        private String name;
        private String note;
    }
}
