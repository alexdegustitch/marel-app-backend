package com.aleksandarparipovic.marel_app.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserUpdateRequest {

    /**
     * REFUSED, always.
     *
     * <p>Kept as a field rather than removed so that a client which sends it is
     * TOLD, instead of watching the value silently do nothing. A username is what
     * somebody signs in with and what the audit trail says did a thing; moving it
     * breaks the record of who did what, and there is no second identifier on
     * those rows to fall back on. Not even an administrator may change it.
     */
    private String username;

    private String firstName;

    private String lastName;

    @Size(max = 150, message = "Prikazno ime je predugačko.")
    private String displayName;

    private String mobilePhone;

    @Email(message = "Email must be valid")
    private String emailAddress;

    // optional
    @Size(min = 4, message = "Password must be at least 4 characters")
    private String password;

    private String roleName;

    private Boolean active;

    /**
     * Which worker this account is. Null leaves the link exactly as it stands,
     * in line with every other field here.
     *
     * <p>Which is why {@link #unlinkEmployee} exists: null cannot mean both
     * "leave it" and "remove it", and cutting an account loose from its payslips
     * is a decision that has to be stated rather than expressed by omission.
     */
    private Long employeeId;

    /** Remove the link. Refused together with {@link #employeeId} — pick one. */
    private Boolean unlinkEmployee;
}
