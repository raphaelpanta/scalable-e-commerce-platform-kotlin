@us4
Feature: Place an order and pay
  A signed-in shopper confirms the cart with a saved delivery address and pays through the simulated provider.
  Stock is reserved before the payment, committed when it is approved and released when it fails. The cart is
  emptied only on success.

  Background:
    Given a signed-in shopper with a saved delivery address

  Scenario: An approved payment confirms the order at frozen prices and empties the cart
    Given a product "Espresso machine" priced at 149.00 with 5 units in stock
    And a product "Coffee beans" priced at 24.50 with 10 units in stock
    And the shopper has 1 "Espresso machine" and 2 "Coffee beans" in the cart
    When the shopper checks out paying with a card the simulator approves
    Then the order is placed with its payment approved
    And the order has 1 "Espresso machine" at 149.00
    And the order has 2 "Coffee beans" at 24.50
    And the order total is 198.00
    And "Espresso machine" has 4 units left in stock
    And "Coffee beans" has 8 units left in stock
    And the shopper's cart is empty

  Scenario: A declined payment cancels the order, releases the stock and keeps the cart
    Given a product "Kettle" priced at 60.00 with 5 units in stock
    And the shopper has 2 "Kettle" in the cart
    When the shopper checks out paying with a card the simulator declines
    Then the checkout is refused because the payment was declined as "card_rejected"
    And the order is recorded as cancelled because the payment failed
    And "Kettle" has 5 units left in stock
    And the cart still holds 2 "Kettle"

  @slow
  Scenario: Two shoppers race for the last unit and exactly one of them gets it
    Given a product "Limited print" priced at 80.00 with 1 unit in stock
    And another signed-in shopper with a saved delivery address
    And both shoppers have 1 "Limited print" in their carts
    When both shoppers check out at the same time paying with a card the simulator approves
    Then exactly one order is placed
    And the other checkout is refused because the stock is insufficient
    And "Limited print" has 0 units left in stock

  Scenario: Retrying a checkout with the same idempotency key yields one order and one charge
    Given a product "Grinder" priced at 90.00 with 5 units in stock
    And the shopper has 1 "Grinder" in the cart
    When the shopper checks out paying with a card the simulator approves
    And the shopper submits the same checkout again because no answer arrived
    Then both answers describe the same order
    And the shopper has exactly 1 order
    And the order has exactly 1 payment charge

  Scenario: An item that went out of stock since it was added stops the checkout
    Given a product "Vase" priced at 30.00 with 2 units in stock
    And the shopper has 2 "Vase" in the cart
    And an operator removes all stock of "Vase"
    When the shopper checks out paying with a card the simulator approves
    Then the checkout is refused because the stock is insufficient for "Vase"
    And the shopper has no orders

  Scenario: A price change since the cart was viewed must be acknowledged before ordering
    Given a product "Mug" priced at 15.00 with 5 units in stock
    And the shopper has 2 "Mug" in the cart
    And the shopper has viewed the cart
    And an operator changes the price of "Mug" to 17.00
    When the shopper checks out with the cart as last viewed paying with a card the simulator approves
    Then the checkout is refused because the price of "Mug" changed from 15.00 to 17.00
    And the shopper has no orders
    When the shopper accepts the new prices and checks out again
    Then the order is placed with its payment approved
    And the order has 2 "Mug" at 17.00

  Scenario: An unreachable payment provider leaves the order placed with its payment pending
    Given a product "Toaster" priced at 45.00 with 5 units in stock
    And the shopper has 1 "Toaster" in the cart
    When the shopper checks out paying with a card while the payment provider is unreachable
    Then the order is placed with its payment pending
    And the shopper is told to retry later
    When the shopper submits the same checkout again because no answer arrived
    Then both answers describe the same order
    And the shopper has exactly 1 order

  @slow
  Scenario: A pending payment is resolved by a later retry of the payment
    Given a product "Milk frother" priced at 35.00 with 5 units in stock
    And the shopper has 1 "Milk frother" in the cart
    When the shopper checks out paying with a card while the payment provider is unreachable
    Then the order is placed with its payment pending
    And a later retry of the payment approves the order
    And the order has 2 payment attempts, the latest approved and the earlier ones voided
    And "Milk frother" has 4 units left in stock
    And the shopper has exactly 1 order
