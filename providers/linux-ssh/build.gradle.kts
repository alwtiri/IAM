// Linux provider: SSH (Apache MINA SSHD client, ADR-0018). Agentless (G6).
plugins { `java-library` }

dependencies {
    implementation(project(":provider-spi"))
    implementation(project(":shared-kernel"))
    implementation(libs.mina.sshd.core)
    runtimeOnly(libs.eddsa) // enables ssh-ed25519 host and user keys in MINA SSHD
    testImplementation(project(":provider-spi-testkit"))
}
