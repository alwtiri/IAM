// Contract test kit every provider module uses in its test sources (ADR-0003).
plugins { `java-library` }

dependencies {
    api(project(":provider-spi"))
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)
}
