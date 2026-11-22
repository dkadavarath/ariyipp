// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// ---- Kotlin lint: ktlint (formatting) and detekt (code smells, likely bugs) ----
// Run as their command-line tools rather than Gradle plugins: both only parse the sources, so they
// don't depend on how AGP builds Kotlin. ktlint runs hygiene rules only (see .editorconfig) with
// nothing baselined; detekt's existing findings are in config/detekt-baseline.xml, to be worked
// down. Anything new fails `./gradlew kotlinLint`.
val ktlintCli: Configuration by configurations.creating
val detektCli: Configuration by configurations.creating

dependencies {
    ktlintCli("com.pinterest.ktlint:ktlint-cli:1.8.0") {
        attributes { attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL)) }
    }
    detektCli("io.gitlab.arturbosch.detekt:detekt-cli:1.23.8")
}

val kotlinSources = listOf("app/src", "sender/src", "shared/src")

val ktlintCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks Kotlin formatting with ktlint."
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    args(kotlinSources.map { "$it/**/*.kt" } + listOf("--relative"))
}

val ktlintFormat by tasks.registering(JavaExec::class) {
    group = "formatting"
    description = "Fixes Kotlin formatting with ktlint (what it can fix by itself)."
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    args(kotlinSources.map { "$it/**/*.kt" } + listOf("--relative", "-F"))
}

val detektCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Finds code smells and likely bugs with detekt."
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    args(
        "--input", kotlinSources.joinToString(","),
        "--config", "config/detekt.yml",
        "--build-upon-default-config",
        "--baseline", "config/detekt-baseline.xml",
        "--report", "html:build/reports/detekt.html",
    )
}

val detektBaseline by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Rewrites config/detekt-baseline.xml to accept every current detekt finding."
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    args(
        "--input", kotlinSources.joinToString(","),
        "--config", "config/detekt.yml",
        "--build-upon-default-config",
        "--baseline", "config/detekt-baseline.xml",
        "--create-baseline",
    )
}

tasks.register("kotlinLint") {
    group = "verification"
    description = "ktlint and detekt."
    dependsOn(ktlintCheck, detektCheck)
}
