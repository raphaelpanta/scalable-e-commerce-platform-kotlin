@us5
Feature: Track orders and history
  A signed-in shopper sees their order history and the status of each order as it moves through its lifecycle,
  with the payment status alongside. Operators advance order statuses; shoppers may cancel before preparation.

  Background:
    Given a signed-in shopper with a saved delivery address
    And a product "Board game" priced at 35.00 with 20 units in stock

  Scenario: The history shows the shopper's own orders, newest first
    Given the shopper has placed 3 paid orders for "Board game"
    And another shopper has placed a paid order for "Board game"
    When the shopper views their order history
    Then the history lists the shopper's 3 orders, newest first
    And each order shows its order status, payment status, total and date
    And the other shopper's order is neither listed nor viewable by the shopper

  @slow
  Scenario: An operator ships a preparing order and the shopper sees the time-stamped change
    Given the shopper has placed a paid order for "Board game"
    And an operator has moved the order to preparing
    When an operator marks the order as shipped
    Then the shopper sees the order as shipped with the time of the change
    And a shipping notification is produced for the order

  Scenario: Cancelling a placed, paid order returns the stock and records a refund
    Given the shopper has placed a paid order for 2 "Board game"
    When the shopper cancels the order
    Then the order is cancelled at the shopper's request
    And "Board game" has 20 units left in stock
    And a refund of the order total is recorded with the payment provider

  Scenario: A shipped order can no longer be cancelled by the shopper
    Given the shopper has placed a paid order for "Board game"
    And an operator has moved the order to preparing
    And an operator has marked the order as shipped
    When the shopper tries to cancel the order
    Then the cancellation is refused with the reason
    And the order is still shipped

  Scenario: A status change outside the lifecycle is refused and leaves the order unchanged
    Given the shopper has placed a paid order for "Board game"
    When an operator tries to mark the order as delivered
    Then the status change is refused as not allowed by the lifecycle
    And the order is still placed

  Scenario: Preparation cannot start while the payment is not approved
    Given the shopper has placed an order for "Board game" while the payment provider is unreachable
    When an operator tries to move the order to preparing
    Then the status change is refused as not allowed by the lifecycle
    And the order is still placed with its payment pending
