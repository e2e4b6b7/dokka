import org.jetbrains.registerDokkaArtifactPublication

plugins {
    `maven-publish`
}

dependencies {
    api(project(":core"))
    implementation(project(":kotlin-analysis"))
    implementation("junit:junit:4.13.2") // TODO: remove dependency to junit
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
}

registerDokkaArtifactPublication("dokkaTestApi") {
    artifactId = "dokka-test-api"
}
