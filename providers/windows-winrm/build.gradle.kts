// Windows provider: WinRM (WS-Management over HTTPS, java.net.http, ADR-0018). Agentless (G6).
plugins { `java-library` }

dependencies {
    implementation(project(":provider-spi"))
    implementation(project(":shared-kernel"))
    testImplementation(project(":provider-spi-testkit"))
}
