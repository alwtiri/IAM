package com.enterprise.iam.core.secrets.infrastructure.web;

import com.enterprise.iam.core.secrets.application.CredentialHandleService;
import com.enterprise.iam.core.shared.api.security.InternalEndpoint;
import com.enterprise.iam.core.shared.api.security.WorkerPrincipal;
import com.enterprise.iam.kernel.Secret;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal API: credential-handle redemption for workers (mTLS listener only). The handle travels in the body, not the
 * URL, so it never appears in access logs.
 */
@RestController
@RequestMapping("/internal/v1")
class InternalCredentialController {

    record RedeemRequest(@NotBlank @Size(max = 128) String handle) {
    }

    private final CredentialHandleService handles;

    InternalCredentialController(CredentialHandleService handles) {
        this.handles = handles;
    }

    @PostMapping("/credential-handles:redeem")
    @InternalEndpoint
    ResponseEntity<Map<String, String>> redeem(@Valid @RequestBody RedeemRequest r,
                                               @RequestAttribute(WorkerPrincipal.REQUEST_ATTRIBUTE) WorkerPrincipal worker) {
        Secret secret = handles.redeem(r.handle(), worker);
        char[] value = secret.reveal(); // semgrep-justified: released to the authenticated worker over mTLS, by design
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("value", new String(value)));
        } finally {
            Arrays.fill(value, '\0');
            secret.destroy();
        }
    }
}
