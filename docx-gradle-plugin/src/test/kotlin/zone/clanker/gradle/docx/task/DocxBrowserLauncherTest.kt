package zone.clanker.gradle.docx.task

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.net.URI

class DocxBrowserLauncherTest :
    BehaviorSpec({
        given("a DOCX preview URL") {
            val url = URI("http://127.0.0.1:43210/")

            `when`("the host is macOS") {
                then("the browser is opened without initializing Java desktop integration") {
                    DocxBrowserLauncher.command("Mac OS X", url) shouldBe
                        listOf("/usr/bin/open", url.toASCIIString())
                }
            }

            `when`("the host is Windows") {
                then("the browser is opened through the native URL handler") {
                    DocxBrowserLauncher.command("Windows 11", url) shouldBe
                        listOf("rundll32", "url.dll,FileProtocolHandler", url.toASCIIString())
                }
            }

            `when`("the host follows the freedesktop convention") {
                then("the browser is opened through xdg-open") {
                    DocxBrowserLauncher.command("Linux", url) shouldBe
                        listOf("xdg-open", url.toASCIIString())
                }
            }
        }
    })
