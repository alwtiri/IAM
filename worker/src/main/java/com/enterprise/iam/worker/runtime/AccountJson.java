package com.enterprise.iam.worker.runtime;

import com.enterprise.iam.provider.spi.model.AccountState;
import com.enterprise.iam.provider.spi.model.GroupRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serializes SPI account states to the result payload shape documented in contracts/README.md. */
public final class AccountJson {

    private AccountJson() {
    }

    public static Map<String, Object> state(AccountState s) {
        Map<String, Object> ref = new LinkedHashMap<>();
        if (s.account().nativeId() != null) {
            ref.put("nativeId", s.account().nativeId());
        }
        ref.put("name", s.account().name());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("account", ref);
        m.put("status", s.status().name());
        m.put("privileged", s.privileged());
        List<Map<String, Object>> groups = new ArrayList<>();
        for (GroupRef g : s.groups()) {
            Map<String, Object> gm = new LinkedHashMap<>();
            if (g.nativeId() != null) {
                gm.put("nativeId", g.nativeId());
            }
            gm.put("name", g.name());
            groups.add(gm);
        }
        m.put("groups", groups);
        put(m, "lastLogin", s.lastLogin());
        put(m, "passwordLastSet", s.passwordLastSet());
        put(m, "passwordExpires", s.passwordExpires());
        m.put("attributes", new LinkedHashMap<>(s.attributes()));
        return m;
    }

    public static List<Map<String, Object>> states(List<AccountState> list) {
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        list.forEach(s -> out.add(state(s)));
        return out;
    }

    private static void put(Map<String, Object> m, String key, Instant value) {
        if (value != null) {
            m.put(key, value.toString());
        }
    }
}
