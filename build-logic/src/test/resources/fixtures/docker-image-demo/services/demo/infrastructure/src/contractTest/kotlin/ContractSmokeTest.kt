import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ContractSmokeTest :
    StringSpec({
        "contract layer runs" {
            1 shouldBe 1
        }
    })
