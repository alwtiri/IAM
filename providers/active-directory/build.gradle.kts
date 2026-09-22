// Active Directory provider: LDAPS / StartTLS (UnboundID LDAP SDK, ADR-0018). Agentless (G6).
plugins { `java-library` }

dependencies {
    implementation(project(":provider-spi"))
    implementation(project(":shared-kernel"))
    implementation(libs.unboundid.ldapsdk)
    testImplementation(project(":provider-spi-testkit"))
}
