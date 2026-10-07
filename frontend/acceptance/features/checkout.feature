@us2
Feature: Place an order and pay from the storefront
  A signed-in shopper confirms the cart with a saved delivery address and one of the payment methods the
  platform offers locally, and sees the confirmation with the order number and both statuses. The cart is
  emptied only when the payment is approved; refusals are explained and leave the cart intact.

  Background:
    Given the seeded catalogue
    And a signed-in shopper with a saved delivery address

  Scenario: An approved payment confirms the order and empties the cart
    Given a product "Espresso machine" priced at 149.00 with 5 units in stock
    And a product "Coffee beans" priced at 24.50 with 10 units in stock
    And the shopper has 1 "Espresso machine" and 2 "Coffee beans" in the cart
    When the shopper checks out paying with the simulated card that is approved
    Then the order is placed with its payment approved
    And the confirmation shows the order number, 1 "Espresso machine" at 149.00 and 2 "Coffee beans" at 24.50
    And the order total is 198.00
    And the shopper's cart is empty

  Scenario: A price change since the cart was viewed must be acknowledged before ordering
    Given a product "Mug" priced at 15.00 with 5 units in stock
    And the shopper has 2 "Mug" in the cart
    And the shopper has reviewed the checkout paying with the simulated card that is approved
    And an operator changes the price of "Mug" to 17.00
    When the shopper confirms the order
    Then the checkout is refused because the price of "Mug" changed from 15.00 to 17.00
    When the shopper accepts the new prices and confirms again
    Then the order is placed with its payment approved
    And the order total is 34.00

  Scenario: An item that went out of stock since it was added stops the checkout
    Given a product "Vase" priced at 30.00 with 2 units in stock
    And the shopper has 2 "Vase" in the cart
    And an operator removes all stock of "Vase"
    When the shopper checks out paying with the simulated card that is approved
    Then the checkout is refused because the stock is insufficient for "Vase"
    And the shopper can adjust the cart

  Scenario: A declined payment cancels the order, explains why and keeps the cart
    Given a product "Kettle" priced at 60.00 with 5 units in stock
    And the shopper has 2 "Kettle" in the cart
    When the shopper checks out paying with the simulated card that is declined
    Then the checkout is refused because the payment was declined
    And the cart still holds 2 "Kettle"

  Scenario: An unreachable payment provider leaves the order awaiting payment with the time left
    Given a product "Toaster" priced at 45.00 with 5 units in stock
    And the shopper has 1 "Toaster" in the cart
    When the shopper checks out paying with the simulated card with an unreachable provider
    Then the order is placed with its payment pending
    And the confirmation shows the time left before the payment window ends

  Scenario: Confirming twice places exactly one order
    Given a product "Grinder" priced at 90.00 with 5 units in stock
    And the shopper has 1 "Grinder" in the cart
    And the shopper has reviewed the checkout paying with the simulated card that is approved
    When the shopper presses "Confirm order" twice
    Then the order is placed with its payment approved
    And the shopper has exactly 1 order

  Scenario: A session that ends during checkout returns the shopper to the same step after signing in again
    Given a product "Notebook" priced at 12.00 with 5 units in stock
    And the shopper has 1 "Notebook" in the cart
    And the shopper has reviewed the checkout paying with the simulated card that is approved
    And the shopper's session has ended
    When the shopper confirms the order
    Then the shopper is asked to sign in first
    When the shopper signs in again
    Then the shopper is back on the review step with the chosen address and payment method

  Scenario: A shopper completes the checkout using only the keyboard
    Given a product "Pen" priced at 5.00 with 5 units in stock
    And the shopper has 1 "Pen" in the cart
    When the shopper opens the checkout
    And I tab to the option "Home"
    And I press "Space"
    And I tab to "Continue to payment"
    And I activate the focused element
    And I tab to the option "Simulated card that is approved"
    And I press "Space"
    And I tab to "Continue to review"
    And I activate the focused element
    And I tab to "Confirm order"
    Then the focused element is visible
    When I activate the focused element
    Then the order is placed with its payment approved
