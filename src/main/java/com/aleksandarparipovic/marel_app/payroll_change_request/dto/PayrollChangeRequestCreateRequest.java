package com.aleksandarparipovic.marel_app.payroll_change_request.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PayrollChangeRequestCreateRequest {

    @NotNull(message = "Obračun je obavezan")
    private Long payrollRunItemId;

    /**
     * Why the month has to come back. Compulsory, and the whole reason this is a
     * request rather than a button: payroll cannot act on "the month is wrong".
     */
    @NotBlank(message = "Obrazloženje je obavezno")
    @Size(max = 2000, message = "Obrazloženje može imati najviše 2000 karaktera")
    private String reason;
}
