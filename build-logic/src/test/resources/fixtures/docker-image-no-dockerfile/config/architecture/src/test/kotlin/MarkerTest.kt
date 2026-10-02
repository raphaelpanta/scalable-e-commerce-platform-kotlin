import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith

class MarkerTest :
    StringSpec({
        "shared architecture sources run inside the infrastructure unit tests" {
            System.getProperty("architecture.basePackage") shouldBe "com.ecommerce.demo"
            System.getProperty("architecture.serviceRoot") shouldEndWith "services/demo"
        }
    })
