package zone.clanker.gradle.srcx

import org.gradle.api.initialization.Settings

/**
 * Type-safe accessor for the `srcx { }` DSL block in `settings.gradle.kts`.
 *
 * ```kotlin
 * // settings.gradle.kts
 * plugins {
 *     id("zone.clanker.gradle.srcx") version "0.1.0"
 * }
 *
 * srcx {
 *     outputDir = ".srcx"
 * }
 * ```
 *
 * @param action configuration block applied to the [Srcx.SettingsExtension]
 * @see Srcx.SettingsExtension
 */
public fun Settings.srcx(action: Srcx.SettingsExtension.() -> Unit) {
    extensions.getByType(Srcx.SettingsExtension::class.java).action()
}
