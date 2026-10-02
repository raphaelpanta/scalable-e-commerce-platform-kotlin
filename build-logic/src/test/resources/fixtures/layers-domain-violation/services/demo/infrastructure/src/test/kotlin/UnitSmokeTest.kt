import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class UnitSmokeTest :
    StringSpec({
        "unit layer runs" {
            1 shouldBe 1
        }
    })
