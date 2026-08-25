package com.aleksandarparipovic.marel_app.customer;

import com.aleksandarparipovic.marel_app.customer.dto.CustomerDto;
import com.aleksandarparipovic.marel_app.customer.dto.CustomerOptionDto;
import org.springframework.stereotype.Component;

@Component
public class CustomerMapper {

    public CustomerDto toDto(Customer c) {
        if (c == null) return null;

        return CustomerDto.builder()
                .id(c.getId())
                .code(c.getCode())
                .name(c.getName())
                .taxId(c.getTaxId())
                .website(c.getWebsite())
                .email(c.getEmail())
                .phone(c.getPhone())
                .active(c.getIsActive())
                .archivedAt(c.getArchivedAt())
                .build();
    }

    public CustomerOptionDto toOptionDto(Customer c) {
        if (c == null) return null;
        return new CustomerOptionDto(c.getId(), c.getName(), c.getCode());
    }
}
