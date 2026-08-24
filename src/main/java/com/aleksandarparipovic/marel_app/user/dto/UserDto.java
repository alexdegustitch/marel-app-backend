package com.aleksandarparipovic.marel_app.user.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UserDto {

    private Long id;
    private String username;
    private String firstName;
    private String lastName;
    private String fullName;
    /** Optional name to be shown by; null means the real name is used. */
    private String displayName;
    private String mobilePhone;
    private String emailAddress;
    private String roleName;
    private Boolean active;

    /**
     * Whether this account has a local password at all.
     *
     * <p>False for accounts provisioned through Google. The screen needs it to
     * know which controls to offer: such an account has no password to change and
     * no way to confirm identity, so it cannot change its own e-mail address
     * either — its address IS the identity Google asserts.
     *
     * <p>A boolean, never the hash or anything derived from it. This says a fact
     * about how the account signs in, which its owner already knows.
     */
    private boolean hasPassword;

    /**
     * The worker this account belongs to, or null when it belongs to none.
     *
     * <p>Null is the normal answer for administration and payroll accounts. It is
     * also what tells a profile screen to say "this account is not linked to a
     * worker" instead of showing an empty list of payslips, which would read as
     * "you have never been paid".
     */
    private Long employeeId;

    /** For the screen that sets the link, so it can show who was chosen. */
    private String employeeName;
}
