package com.enterprise.iam.core.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.shared.api.jdbc.ScopeSql;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.paging.PageResult;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import com.enterprise.iam.core.shared.api.security.StepUpPolicy;
import com.enterprise.iam.kernel.IamException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScopeTest {

    private static ScopeFilter.Grant grant(Set<String> exact, Set<String> tree, Set<String> env) {
        return new ScopeFilter.Grant(exact, tree, env, Set.of(), Set.of(), Set.of());
    }

    @Test
    void orgTreeMatchesDescendantsOnly() {
        ScopeFilter f = new ScopeFilter(false, List.of(grant(Set.of(), Set.of("/a/b/"), Set.of())));
        assertTrue(f.matches(ResourceScope.orgUnit("/a/b/")));
        assertTrue(f.matches(ResourceScope.orgUnit("/a/b/c/")));
        assertFalse(f.matches(ResourceScope.orgUnit("/a/bc/")));
        assertFalse(f.matches(ResourceScope.orgUnit("/a/")));
        assertFalse(f.matches(ResourceScope.PLATFORM), "platform objects need GLOBAL");
    }

    @Test
    void dimensionsAreAndedAndMissingAttributesFailClosed() {
        ScopeFilter f = new ScopeFilter(false, List.of(grant(Set.of(), Set.of("/a/"), Set.of("PRODUCTION"))));
        assertTrue(f.matches(new ResourceScope("/a/x/", "PRODUCTION", null, null, null)));
        assertFalse(f.matches(new ResourceScope("/a/x/", "TEST", null, null, null)));
        assertFalse(f.matches(ResourceScope.orgUnit("/a/x/")), "no environment attribute → no match");
    }

    @Test
    void sqlTranslationUsesParametersAndSameSemantics() {
        Map<String, Object> params = new HashMap<>();
        ScopeFilter f = new ScopeFilter(false, List.of(grant(Set.of("/e/"), Set.of("/a_b/"), Set.of("TEST"))));
        String sql = ScopeSql.predicate(f, new ScopeSql.Columns("p.org_path", "p.env", null, null, null), params, "x_");
        assertTrue(sql.contains("p.org_path IN (:x_g0_orgExact)"));
        assertTrue(sql.contains("p.org_path LIKE :x_g0_orgTree0"));
        assertTrue(sql.contains("p.env IN (:x_g0_env)"));
        assertEquals("/a\\_b/%", params.get("x_g0_orgTree0"), "LIKE wildcards in paths are escaped");
        assertEquals("(TRUE)", ScopeSql.predicate(ScopeFilter.GLOBAL, new ScopeSql.Columns(null, null, null, null, null), params, "y_"));
        assertEquals("(FALSE)", ScopeSql.predicate(ScopeFilter.NONE, new ScopeSql.Columns(null, null, null, null, null), params, "z_"));
        String noEnvColumn = ScopeSql.predicate(f, new ScopeSql.Columns("p.org_path", null, null, null, null), params, "w_");
        assertTrue(noEnvColumn.contains("FALSE"), "constraint on a missing column must not match");
    }

    @Test
    void stepUpRequiresRecentMfa() {
        Instant now = Instant.parse("2026-09-21T10:00:00Z");
        StepUpPolicy p = StepUpPolicy.DEFAULT;
        UUID id = UUID.randomUUID();
        assertTrue(p.isSatisfied(new CurrentActor(id, "s", "mfa", List.of(), now.minusSeconds(60), CurrentActor.Channel.BROWSER_SESSION, null), now));
        assertFalse(p.isSatisfied(new CurrentActor(id, "s", "mfa", List.of(), now.minusSeconds(600), CurrentActor.Channel.BROWSER_SESSION, null), now));
        assertFalse(p.isSatisfied(new CurrentActor(id, "s", "1", List.of("pwd"), now, CurrentActor.Channel.BROWSER_SESSION, null), now));
        assertTrue(p.isSatisfied(new CurrentActor(id, "s", "1", List.of("pwd", "otp"), now, CurrentActor.Channel.BROWSER_SESSION, null), now),
                "amr=otp evidences MFA even when acr is a plain level");
        assertFalse(p.isSatisfied(new CurrentActor(id, "s", "mfa", List.of(), null, CurrentActor.Channel.BEARER_TOKEN, null), now));
        assertFalse(p.isSatisfied(CurrentActor.system(id), now));
    }

    @Test
    void cursorPagingRoundTrips() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        PageResult<UUID> page = PageResult.fromOverfetch(List.of(a, b, c), 2, x -> x);
        assertEquals(List.of(a, b), page.items());
        assertEquals(b, PageRequest.of(2, page.nextCursor()).after());
        assertEquals(null, PageResult.fromOverfetch(List.of(a), 2, x -> x).nextCursor());
        assertThrows(IamException.class, () -> PageRequest.of(500, null));
        assertThrows(IamException.class, () -> PageRequest.of(10, "not-a-cursor!!"));
    }
}
