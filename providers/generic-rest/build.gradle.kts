// Generic application provider: SCIM 2.0 over HTTPS (RFC 7643/7644), JDK HTTP client only (ADR-0018).
plugins { `java-library` }

dependencies {
    implementation(project(":provider-spi"))
    implementation(project(":shared-kernel"))
    testImplementation(project(":provider-spi-testkit"))
}
