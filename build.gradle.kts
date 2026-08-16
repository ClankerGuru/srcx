plugins {
    base
}

tasks.named("build") {
    dependsOn(
        gradle.includedBuild("srcx-build-logic").task(":build"),
        ":docx-gradle-plugin:build",
        ":docx-index:build",
        ":docx-live-contract:build",
        ":docx-service:build",
        ":docx-web:build",
        ":srcx-gradle-plugin:build",
        ":workspace-report-model:build",
    )
}
