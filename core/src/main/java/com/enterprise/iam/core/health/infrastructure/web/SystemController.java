package com.enterprise.iam.core.health.infrastructure.web;

import com.enterprise.iam.core.health.api.SystemHealthView;
import com.enterprise.iam.core.health.api.SystemInfoView;
import com.enterprise.iam.core.health.application.SystemService;
import com.enterprise.iam.core.shared.api.security.CurrentActorProvider;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
class SystemController {

    private final SystemService system;
    private final CurrentActorProvider actors;
    private final org.springframework.core.env.Environment env;

    SystemController(SystemService system, CurrentActorProvider actors, org.springframework.core.env.Environment env) {
        this.system = system;
        this.actors = actors;
        this.env = env;
    }

    /** One effective setting: key, value (never a secret), and what it controls. */
    record Setting(String group, String key, String value, String description) {
    }

    /**
     * Effective, non-secret platform settings (Phase 7). Values come from the deployment configuration (environment /
     * application.yml); secrets are never listed, only whether they are configured.
     */
    @GetMapping("/settings")
    @RequiresPermission(Permissions.SYSTEM_READ)
    java.util.List<Setting> settings() {
        actors.require();
        String siem = env.getProperty("iam.siem.webhook-url", "");
        String smtp = env.getProperty("spring.mail.host", "");
        return java.util.List.of(
                new Setting("Platform", "iam.version", env.getProperty("iam.version", "?"), "Platform version"),
                new Setting("Platform", "iam.build-sha", env.getProperty("iam.build-sha", "?"), "Build"),
                new Setting("Security", "iam.auth.step-up.max-age", env.getProperty("iam.auth.step-up.max-age", "PT5M"),
                        "How long an MFA step-up stays valid for sensitive actions"),
                new Setting("Security", "iam.auth.secure-cookies", env.getProperty("iam.auth.secure-cookies", "false"),
                        "Secure session cookies (must be true behind HTTPS)"),
                new Setting("Discovery", "iam.discovery.scheduled", env.getProperty("iam.discovery.scheduled", "true"), "Scheduled re-discovery on"),
                new Setting("Discovery", "iam.discovery.interval", env.getProperty("iam.discovery.interval", "P1D"), "Re-discovery interval per server"),
                new Setting("Accounts", "iam.accounts.dormant-after-days", env.getProperty("iam.accounts.dormant-after-days", "90"),
                        "Days without login before an account is reported dormant"),
                new Setting("Vault", "iam.vault.rotation-interval", env.getProperty("iam.vault.rotation-interval", "P30D"),
                        "Automatic rotation interval of vaulted passwords"),
                new Setting("Vault", "break-glass duration", "PT4H", "Length of an emergency (break-glass) checkout"),
                new Setting("Vault", "iam.vault.address", env.getProperty("iam.vault.address", "?"), "Vault server"),
                new Setting("Integrations", "iam.siem.webhook-url", siem.isBlank() ? "not configured" : java.net.URI.create(siem).getHost(),
                        "Audit forwarding to a SIEM (HMAC-signed HTTPS webhook)"),
                new Setting("Integrations", "spring.mail.host", smtp.isBlank() ? "not configured" : smtp, "E-mail notifications (SMTP)"),
                new Setting("Workers", "iam.operations.provider-types", env.getProperty("iam.operations.provider-types", ""),
                        "Provider types served by the worker plane"));
    }

    @GetMapping("/health")
    @RequiresPermission(Permissions.SYSTEM_HEALTH_READ)
    SystemHealthView health() {
        return system.health(actors.require());
    }

    @GetMapping("/info")
    @RequiresPermission(Permissions.SYSTEM_READ)
    SystemInfoView info() {
        return system.info(actors.require());
    }
}
