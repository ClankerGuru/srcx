plugins {
    `java-gradle-plugin`
}

tasks.withType<ValidatePlugins>().configureEach {
    enableStricterValidation.set(false)
    failOnWarning.set(false)
}
