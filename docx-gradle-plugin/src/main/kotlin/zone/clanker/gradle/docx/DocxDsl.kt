package zone.clanker.gradle.docx

import org.gradle.api.initialization.Settings

/** Configures the root-level DOCX documentation and analysis plan. */
public fun Settings.docx(action: DocxSettingsExtension.() -> Unit) {
    extensions.getByType(DocxSettingsExtension::class.java).action()
}
