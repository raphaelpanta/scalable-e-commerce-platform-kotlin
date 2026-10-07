@us4
Feature: Track orders and cancel while it is allowed
  A signed-in shopper sees their order history and the status of each order, with the payment status
  alongside, opens an order to see its lines, amounts, address and history, and may cancel it while it is
  still placed. Once an operator has started preparing it, the cancel action is gone.

  Background:
    Given the seeded catalogue
    And a signed-in shopper with a saved delivery address
    And a product "Board game" priced at 35.00 with 20 units in stock

  Scenario: The history shows the shopper's own orders, newest first
    Given the shopper has placed 3 paid orders for "Board game"
    And another shopper has placed a paid order for "Board game"
    When the shopper views their order history
    Then the history lists the shopper's 3 orders, newest first
    And each order shows its order status, payment status, total and date
    And the other shopper's order is neither listed nor viewable by the shopper

  Scenario: An order shows its lines, amounts, address and history
    Given the shopper has placed a paid order for 2 "Board game"
    When the shopper opens the order
    Then the order shows 2 "Board game" at 35.00 and the total 70.00
    And the order shows the delivery address in "Lisboa"
    And the order history starts with the order placed by you

  Scenario: Cancelling a placed order asks for confirmation and cancels it at the shopper's request
    Given the shopper has placed a paid order for 2 "Board game"
    When the shopper opens the order
    And the shopper starts to cancel the order but keeps it
    Then the order is still placed
    When the shopper cancels the order after confirming
    Then the order is cancelled at the shopper's request
    And no cancel action is offered

  Scenario: Cancelling is no longer offered once an operator has started preparing the order
    Given the shopper has placed a paid order for "Board game"
    And an operator has moved the order to preparing
    When the shopper opens the order
    Then the order shows the status "Being prepared"
    And no cancel action is offered

  Scenario: A cancellation refused because the order moved on is explained and leaves the page as it was
    Given the shopper has placed a paid order for "Board game"
    And the shopper is looking at the order
    And an operator has moved the order to preparing
    When the shopper cancels the order after confirming
    Then the cancellation is refused with the reason
    And the order is still placed

  Scenario: A shopper cancels a placed order using only the keyboard
    Given the shopper has placed a paid order for "Board game"
    When the shopper opens the order
    And I tab to "Cancel order"
    And I activate the focused element
    Then the focused element is "Keep the order"
    When I tab to "Yes, cancel the order"
    And I activate the focused element
    Then the order is cancelled at the shopper's request
