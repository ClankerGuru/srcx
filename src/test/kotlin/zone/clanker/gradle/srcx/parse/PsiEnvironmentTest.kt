package zone.clanker.gradle.srcx.parse

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldNotBe

class PsiEnvironmentTest :
    BehaviorSpec({

        given("PsiEnvironment") {

            `when`("created and closed") {
                then("it initializes and disposes without error") {
                    val env = PsiEnvironment()
                    env.environment shouldNotBe null
                    env.psiManager shouldNotBe null
                    env.close()
                }
            }
        }
    })
