import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class AcceptanceSmokeTest :
    StringSpec({
        "acceptance layer runs" {
            1 shouldBe 1
        }
    })
