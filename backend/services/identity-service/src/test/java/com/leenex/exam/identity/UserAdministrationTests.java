package com.leenex.exam.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Admin bootstrap, roles, suspension, profiles and account deletion. */
class UserAdministrationTests extends IdentityTestSupport {

    @Test
    void theBootstrapEmailBecomesAdministratorExactlyOnce() throws Exception {
        Session admin = signInAdmin();
        mvc.perform(withBearer(get("/api/users/me"), admin.accessToken()))
                .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.containsInAnyOrder("ADMIN", "STUDENT")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_bootstrap", Integer.class)).isEqualTo(1);
        assertThat(auditCount("ADMIN_BOOTSTRAPPED", admin.userId())).isEqualTo(1);

        signInAdmin(); // signing in again does not bootstrap again
        assertThat(auditCount("ADMIN_BOOTSTRAPPED", admin.userId())).isEqualTo(1);
    }

    @Test
    void studentsCannotUseAdminEndpoints() throws Exception {
        Session student = signInStudent();
        mvc.perform(withBearer(get("/api/admin/users"), student.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(withBearer(put("/api/admin/users/" + student.userId() + "/roles/ADMIN"), student.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminsCanLookUpDisplayNamesById() throws Exception {
        Session admin = signInAdmin();
        Session other = signIn("g-name-lookup", "name.lookup@example.com");
        mvc.perform(withBearer(get("/api/admin/users/names").param("ids", other.userId() + "," + admin.userId()),
                        admin.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.id == '" + other.userId() + "')].displayName").value("Test g-name-lookup"))
                .andExpect(jsonPath("$[0].email").doesNotExist());
        mvc.perform(withBearer(get("/api/admin/users/names").param("ids", other.userId().toString()), other.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminsCanListAndSearchUsers() throws Exception {
        Session admin = signInAdmin();
        signIn("g-search-target", "findable.person@example.com");

        mvc.perform(withBearer(get("/api/admin/users").param("q", "FINDABLE"), admin.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].email").value("findable.person@example.com"))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.size").value(25));

        mvc.perform(withBearer(get("/api/admin/users").param("role", "ADMIN"), admin.accessToken()))
                .andExpect(jsonPath("$.items[*].roles[*]", org.hamcrest.Matchers.hasItem("ADMIN")));
        mvc.perform(withBearer(get("/api/admin/users").param("size", "7"), admin.accessToken()))
                .andExpect(status().isBadRequest());
        // Wildcards in the search text are literal.
        mvc.perform(withBearer(get("/api/admin/users").param("q", "%"), admin.accessToken()))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void grantingAndRevokingAdminIsAuditedAndTakesEffectOnNextToken() throws Exception {
        Session admin = signInAdmin();
        Session other = signInStudent();

        mvc.perform(withBearer(put("/api/admin/users/" + other.userId() + "/roles/ADMIN"), admin.accessToken()))
                .andExpect(status().isNoContent());
        String promoted = read(refresh(other.refreshToken()), "$.accessToken");
        mvc.perform(withBearer(get("/api/admin/users"), promoted)).andExpect(status().isOk());
        assertThat(auditCount("ADMIN_ROLE_GRANTED", other.userId())).isEqualTo(1);

        mvc.perform(withBearer(delete("/api/admin/users/" + other.userId() + "/roles/ADMIN"), admin.accessToken()))
                .andExpect(status().isNoContent());
        assertThat(auditCount("ADMIN_ROLE_REVOKED", other.userId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_id::text FROM audit_event WHERE action = 'ADMIN_ROLE_REVOKED' AND target_id = ?",
                String.class, other.userId().toString())).isEqualTo(admin.userId().toString());
    }

    @Test
    void administratorsCannotDemoteOrSuspendThemselves() throws Exception {
        Session admin = signInAdmin();
        mvc.perform(withBearer(delete("/api/admin/users/" + admin.userId() + "/roles/ADMIN"), admin.accessToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mvc.perform(withBearer(put("/api/admin/users/" + admin.userId() + "/status"), admin.accessToken())
                        .contentType("application/json").content("{\"status\":\"SUSPENDED\",\"reason\":\"testing\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void suspensionEndsSessionsAndBlocksSignInUntilReactivated() throws Exception {
        Session admin = signInAdmin();
        Session student = signIn("g-to-suspend", "suspend.me@example.com");

        mvc.perform(withBearer(put("/api/admin/users/" + student.userId() + "/status"), admin.accessToken())
                        .contentType("application/json").content("{\"status\":\"SUSPENDED\",\"reason\":\"Shared account\"}"))
                .andExpect(status().isNoContent());
        assertThat(refresh(student.refreshToken()).getStatus()).isEqualTo(401);

        Started started = start("/");
        assertThat(callback(started, "c-suspended", grant(started, "g-to-suspend", "suspend.me@example.com")).getRedirectedUrl())
                .isEqualTo(BASE_URL + "/signin?error=suspended");

        mvc.perform(withBearer(put("/api/admin/users/" + student.userId() + "/status"), admin.accessToken())
                        .contentType("application/json").content("{\"status\":\"ACTIVE\",\"reason\":\"Resolved\"}"))
                .andExpect(status().isNoContent());
        assertThat(signIn("g-to-suspend", "suspend.me@example.com").userId()).isEqualTo(student.userId());
        assertThat(auditCount("USER_SUSPENDED", student.userId())).isEqualTo(1);
        assertThat(auditCount("USER_REACTIVATED", student.userId())).isEqualTo(1);
    }

    @Test
    void statusChangesNeedAReason() throws Exception {
        Session admin = signInAdmin();
        Session student = signInStudent();
        mvc.perform(withBearer(put("/api/admin/users/" + student.userId() + "/status"), admin.accessToken())
                        .contentType("application/json").content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
    }

    @Test
    void profileUpdatesUseOptimisticLocking() throws Exception {
        Session student = signInStudent();
        MockHttpServletResponse me = mvc.perform(withBearer(get("/api/users/me"), student.accessToken()))
                .andExpect(header().exists("ETag")).andReturn().getResponse();
        String etag = me.getHeader("ETag");

        mvc.perform(withBearer(patch("/api/users/me"), student.accessToken()).header("If-Match", etag)
                        .contentType("application/json").content("{\"displayName\":\"  Amaya Perera \",\"preferences\":{\"theme\":\"light\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Amaya Perera"))
                .andExpect(jsonPath("$.preferences.theme").value("light"));

        mvc.perform(withBearer(patch("/api/users/me"), student.accessToken()).header("If-Match", etag)
                        .contentType("application/json").content("{\"displayName\":\"Stale edit\"}"))
                .andExpect(status().isConflict());
        mvc.perform(withBearer(patch("/api/users/me"), student.accessToken())
                        .contentType("application/json").content("{\"displayName\":\"No version\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(withBearer(patch("/api/users/me"), student.accessToken()).header("If-Match", "\"1\"")
                        .contentType("application/json").content("{\"displayName\":\"" + "x".repeat(121) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletingAnAccountRemovesPersonalDataAndEndsSessions() throws Exception {
        Session student = signIn("g-leaving", "leaving@example.com");
        mvc.perform(withBearer(delete("/api/users/me"), student.accessToken())).andExpect(status().isAccepted());

        assertThat(refresh(student.refreshToken()).getStatus()).isEqualTo(401);
        List<String> row = jdbc.queryForList("SELECT email::text FROM app_user WHERE id = ?", String.class, student.userId());
        assertThat(row).singleElement().asString().startsWith("deleted+").doesNotContain("leaving");
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, student.userId()))
                .isEqualTo("DELETED");
        // The same Google account can start again with a fresh account.
        assertThat(signIn("g-leaving", "leaving@example.com").userId()).isNotEqualTo(student.userId());
    }

    @Test
    void theOnlyAdministratorCannotDeleteTheirAccount() throws Exception {
        Session admin = signInAdmin();
        int admins = jdbc.queryForObject("""
                SELECT count(*) FROM user_role r JOIN app_user u ON u.id = r.user_id
                WHERE r.role = 'ADMIN' AND u.status = 'ACTIVE'""", Integer.class);
        if (admins == 1) {
            mvc.perform(withBearer(delete("/api/users/me"), admin.accessToken()))
                    .andExpect(status().isConflict());
        }
    }
}
