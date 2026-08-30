package com.aleksandarparipovic.marel_app;

import com.aleksandarparipovic.marel_app.config.security.AppPermission;
import com.aleksandarparipovic.marel_app.config.security.RolePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The role/permission matrix, stated as assertions.
 *
 * <p>This is the one file in the application that decides who may open which
 * screen, and a permission silently added to or dropped from a role is not
 * something any other test would notice. So the cases that carry a consequence
 * are written down here: what the shop floor may not do, what commercial may
 * not reach, and what a role nobody has decided about gets.
 */
class RolePermissionsTest {

    @Nested
    @DisplayName("the supervisor")
    class Supervisor {

        @Test
        @DisplayName("owns the records, payroll screens, analytics and the workers")
        void ownsTheFloor() {
            assertThat(RolePermissions.forRole("supervisor")).contains(
                    AppPermission.WORK_RECORD_VIEW,
                    AppPermission.PAYROLL_VIEW,
                    AppPermission.ANALYTICS_VIEW,
                    AppPermission.EMPLOYEE_VIEW,
                    AppPermission.MANUFACTURING_TIME_MANAGE,
                    AppPermission.BONUS_RULE_MANAGE,
                    AppPermission.APP_SETTING_MANAGE,
                    AppPermission.WORK_CALENDAR_MANAGE);
        }

        @Test
        @DisplayName("reads orders and never writes one")
        void readsOrdersOnly() {
            assertThat(RolePermissions.roleHas("supervisor", AppPermission.PRODUCTION_ORDER_VIEW)).isTrue();
            assertThat(RolePermissions.roleHas("supervisor", AppPermission.PRODUCTION_ORDER_MANAGE)).isFalse();
        }

        @Test
        @DisplayName("sees who was told about an order and changes nobody")
        void readsRecipientsOnly() {
            assertThat(RolePermissions.roleHas("supervisor", AppPermission.PRODUCTION_ORDER_RECIPIENT_VIEW)).isTrue();
            assertThat(RolePermissions.roleHas("supervisor", AppPermission.PRODUCTION_ORDER_RECIPIENT_MANAGE)).isFalse();
            // The global-list grant went with it: managing a shared list is
            // writing, on an order, by a role that no longer writes on orders.
            assertThat(RolePermissions.roleHas("supervisor", AppPermission.MAILING_LIST_GLOBAL_MANAGE)).isFalse();
        }

        @Test
        @DisplayName("reads sample orders on exactly the same terms")
        void readsSampleOrdersOnly() {
            // The split is granted, not inherited: opening one kind of order to
            // somebody must never silently open the other.
            assertThat(RolePermissions.roleHas("supervisor", AppPermission.SAMPLE_ORDER_VIEW)).isTrue();
            assertThat(RolePermissions.roleHas("supervisor", AppPermission.SAMPLE_ORDER_MANAGE)).isFalse();
            assertThat(RolePermissions.roleHas("supervisor", AppPermission.SAMPLE_ORDER_RECIPIENT_VIEW)).isTrue();
            assertThat(RolePermissions.roleHas("supervisor", AppPermission.SAMPLE_ORDER_RECIPIENT_MANAGE)).isFalse();
        }

        @Test
        @DisplayName("writes the catalogue the norms hang off")
        void writesTheCatalogue() {
            assertThat(RolePermissions.forRole("supervisor")).contains(
                    AppPermission.PRODUCT_MANAGE,
                    AppPermission.OPERATION_MANAGE);
        }

        @Test
        @DisplayName("decides manufacturing-time requests and never raises one")
        void decidesButDoesNotRaise() {
            assertThat(RolePermissions.roleHas("supervisor", AppPermission.MANUFACTURING_TIME_REQUEST_PROCESS)).isTrue();
            assertThat(RolePermissions.roleHas("supervisor", AppPermission.MANUFACTURING_TIME_REQUEST_CREATE)).isFalse();
        }

        @Test
        @DisplayName("cannot reach the customers, the accounts, or who sees which payroll line")
        void staysOffCommercialAndAdministrativeGround() {
            assertThat(RolePermissions.forRole("supervisor")).doesNotContain(
                    AppPermission.CUSTOMER_VIEW,
                    AppPermission.PAYROLL_ACCESS_CONFIGURE,
                    AppPermission.PAYROLL_LOCK,
                    AppPermission.USER_REGISTRATION_APPROVE,
                    AppPermission.USER_SESSION_REVOKE);
        }
    }

    @Nested
    @DisplayName("commercial staff")
    class Commercial {

        @Test
        @DisplayName("own the orders and the customers behind them")
        void ownTheOrders() {
            assertThat(RolePermissions.forRole("commercial")).contains(
                    AppPermission.CUSTOMER_VIEW,
                    AppPermission.PRODUCTION_ORDER_VIEW,
                    AppPermission.PRODUCTION_ORDER_MANAGE,
                    AppPermission.MANUFACTURING_TIME_REQUEST_CREATE);
        }

        @Test
        @DisplayName("read every manufacturing-time request without deciding one")
        void readEveryRequestWithoutDecidingOne() {
            assertThat(RolePermissions.roleHas(
                    "commercial", AppPermission.MANUFACTURING_TIME_REQUEST_READ_ALL)).isTrue();
            assertThat(RolePermissions.roleHas(
                    "commercial", AppPermission.MANUFACTURING_TIME_REQUEST_PROCESS)).isFalse();
        }

        @Test
        @DisplayName("reach nothing on the shop floor")
        void reachNothingOnTheFloor() {
            assertThat(RolePermissions.forRole("commercial")).doesNotContain(
                    AppPermission.WORK_RECORD_VIEW,
                    AppPermission.PAYROLL_VIEW,
                    AppPermission.ANALYTICS_VIEW,
                    AppPermission.EMPLOYEE_VIEW,
                    // Reading the catalogue is open to them; changing it is not.
                    AppPermission.PRODUCT_MANAGE,
                    AppPermission.OPERATION_MANAGE);
        }
    }

    @Nested
    @DisplayName("the production coordinator")
    class ProductionCoordinator {

        @Test
        @DisplayName("plans against the analytics without reading anybody's card")
        void analyticsWithoutTheRecords() {
            assertThat(RolePermissions.roleHas("production_coordinator", AppPermission.ANALYTICS_VIEW)).isTrue();
            assertThat(RolePermissions.forRole("production_coordinator")).doesNotContain(
                    AppPermission.WORK_RECORD_VIEW,
                    AppPermission.EMPLOYEE_VIEW,
                    AppPermission.PAYROLL_VIEW,
                    AppPermission.CUSTOMER_VIEW,
                    AppPermission.PRODUCTION_ORDER_VIEW,
                    AppPermission.PRODUCT_MANAGE,
                    AppPermission.OPERATION_MANAGE);
        }
    }

    @Nested
    @DisplayName("the accountant")
    class Accountant {

        @Test
        @DisplayName("holds nothing beyond what every signed-in account holds")
        void holdsNothingYet() {
            // No rule has been decided for this role. Until one is, it must get
            // the screens everybody gets and not one screen more — today that is
            // raising the two kinds of request, which the requests screen offers
            // to every signed-in account that is not the one deciding them.
            assertThat(RolePermissions.forRole("accountant"))
                    .containsExactlyInAnyOrder(
                            AppPermission.MANUFACTURING_TIME_REQUEST_CREATE,
                            AppPermission.ORDER_SCOPE_REQUEST_CREATE);
        }
    }

    @Nested
    @DisplayName("a role the application does not know")
    class Unknown {

        @Test
        @DisplayName("holds nothing, and neither does a null one")
        void failsClosed() {
            assertThat(RolePermissions.forRole("janitor")).isEmpty();
            assertThat(RolePermissions.forRole(null)).isEmpty();
            assertThat(RolePermissions.roleHas("janitor", AppPermission.PAYROLL_VIEW)).isFalse();
        }
    }

    @Test
    @DisplayName("role names are matched however they are cased")
    void caseInsensitive() {
        assertThat(RolePermissions.roleHas("SUPERVISOR", AppPermission.WORK_RECORD_VIEW)).isTrue();
        assertThat(RolePermissions.roleHas("Production_Coordinator", AppPermission.ANALYTICS_VIEW)).isTrue();
    }

    @Test
    @DisplayName("admin and developer hold everything")
    void adminAndDeveloperHoldEverything() {
        for (AppPermission permission : AppPermission.values()) {
            assertThat(RolePermissions.roleHas("admin", permission)).isTrue();
            assertThat(RolePermissions.roleHas("developer", permission)).isTrue();
        }
    }
}
