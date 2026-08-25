package com.aleksandarparipovic.marel_app.customer.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class CustomerDto {

    private Long id;
    private String code;
    private String name;
    private String taxId;
    private String website;
    private String email;
    private String phone;
    private Boolean active;
    /** When the customer was deactivated; null while active. */
    private OffsetDateTime archivedAt;
}
