import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class IntegrationSmokeTest :
    StringSpec({
        "integration layer runs" {
            1 shouldBe 1
        }
    })
