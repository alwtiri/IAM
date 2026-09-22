// PostgreSQL provider: database roles over JDBC (TLS by default). Agentless (G6).
plugins { `java-library` }

dependencies {
    implementation(project(":provider-spi"))
    implementation(project(":shared-kernel"))
    runtimeOnly(libs.postgresql.jdbc)
    testImplementation(project(":provider-spi-testkit"))
}
