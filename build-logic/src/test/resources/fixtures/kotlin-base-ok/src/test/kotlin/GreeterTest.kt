import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class GreeterTest :
    StringSpec({
        "greets by name" {
            greet("a") shouldBe "Hello, a"
        }
    })
