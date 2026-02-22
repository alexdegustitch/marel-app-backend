package com.aleksandarparipovic.marel_app.bonus.dto;

import java.math.BigDecimal;

public record BonusCategoryOptionDto(    Long id,
                                         String name,
                                         BigDecimal amount) {

}
