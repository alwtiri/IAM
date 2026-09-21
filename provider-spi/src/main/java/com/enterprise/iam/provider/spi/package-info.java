/**
 * Provider Service Provider Interface (SPI) v1 — ADR-0003, ADR-0010, gate clarifications G5 and G8.
 *
 * <p>Rules every provider implementation must follow:
 * <ol>
 *   <li>Declare capabilities honestly in {@link com.enterprise.iam.provider.spi.ProviderDescriptor}.
 *       Anything not implemented stays at its default, which returns
 *       {@link com.enterprise.iam.provider.spi.result.OperationOutcome#UNSUPPORTED} — never a fake success.</li>
 *   <li>Report {@link com.enterprise.iam.provider.spi.result.OperationOutcome#SUCCEEDED} only with a
 *       {@link com.enterprise.iam.provider.spi.result.Verification}; the result type enforces this.</li>
 *   <li>Be idempotent for the same {@link com.enterprise.iam.provider.spi.OperationContext#idempotencyKey()}.</li>
 *   <li>Respect {@link com.enterprise.iam.provider.spi.OperationContext#deadline()}; never block beyond it.</li>
 *   <li>Obtain credentials only through {@link com.enterprise.iam.provider.spi.CredentialResolver}; never persist
 *       or log them.</li>
 *   <li>Execute target-side operations only. Governance state (ownership, lifecycle, policy, risk) is owned by the
 *       IAM Core account module; providers never write it.</li>
 *   <li>Discovery operations are read-only and must not modify targets.</li>
 * </ol>
 *
 * <p>This package is plain Java: no Spring or other framework dependency is allowed (ArchUnit rule).
 */
package com.enterprise.iam.provider.spi;
