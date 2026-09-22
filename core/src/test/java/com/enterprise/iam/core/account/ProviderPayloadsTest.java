package com.enterprise.iam.core.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.account.application.ProviderPayloads;
import com.enterprise.iam.core.account.domain.Account;
import com.enterprise.iam.core.account.domain.DiscoveredAccount;
import com.enterprise.iam.kernel.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderPayloadsTest {

    @Test
    void mapsSerializedAccountStates() {
        Map<String, Object> payload = Json.parseObject("""
                {"accounts":[
                  {"account":{"nativeId":"uid:1001","name":"alice"},"status":"LOCKED","privileged":true,
                   "groups":[{"nativeId":"gid:10","name":"wheel"}],"lastLogin":"2026-09-01T08:00:00Z",
                   "attributes":{"displayName":"Alice A","privilegeReason":"member of wheel","shell":"/bin/bash","password":"x"}},
                  {"account":{"name":"svc"},"status":"WEIRD"},
                  {"status":"ENABLED"}
                ]}""");
        List<DiscoveredAccount> list = ProviderPayloads.accounts(payload);
        assertEquals(2, list.size());
        DiscoveredAccount a = list.get(0);
        assertEquals("uid:1001", a.nativeId());
        assertEquals("Alice A", a.displayName());
        assertEquals("member of wheel", a.privilegeReason());
        assertEquals(Account.NativeStatus.LOCKED, a.nativeStatus());
        assertEquals(List.of("gid:10"), a.entitlementNativeIds());
        assertEquals(Map.of("shell", "/bin/bash"), a.attributes());
        assertTrue(a.privileged());
        DiscoveredAccount svc = list.get(1);
        assertEquals("svc", svc.nativeId(), "name is the native id when the provider has none");
        assertEquals(Account.NativeStatus.UNKNOWN, svc.nativeStatus());
        assertFalse(svc.privileged());
    }

    @Test
    void observedStateIsOptional() {
        assertNull(ProviderPayloads.observed(Map.of()));
        assertEquals("bob", ProviderPayloads.observed(Json.parseObject("""
                {"state":{"account":{"nativeId":"b","name":"bob"},"status":"DISABLED"}}""")).name());
    }
}
