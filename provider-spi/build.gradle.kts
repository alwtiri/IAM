// Provider SPI v1 — plain Java, depends only on shared-kernel (ADR-0003).
plugins { `java-library` }

dependencies {
    api(project(":shared-kernel"))
}
