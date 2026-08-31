package com.aleksandarparipovic.marel_app.payroll_change_request.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PayrollChangeRequestDecisionRequest {

    /**
     * Optional. A refusal usually needs one and an acceptance usually does not,
     * but which is which is the decider's judgement rather than a rule the
     * server should invent.
     */
    @Size(max = 2000, message = "Napomena može imati najviše 2000 karaktera")
    private String decisionNote;
}
