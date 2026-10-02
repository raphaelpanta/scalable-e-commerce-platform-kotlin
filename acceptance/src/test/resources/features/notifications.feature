@us6
Feature: Receive notifications for account and order events
  Shoppers receive messages for order events by email, and by SMS when they opted in with a verified number.
  Failed deliveries are retried and visible to operators; a duplicate event never produces a second message.

  Background:
    Given a signed-in shopper with a saved delivery address
    And a product "Puzzle" priced at 20.00 with 10 units in stock

  @slow
  Scenario: A paid order sends a confirmation email with the order details
    When the shopper places a paid order for 2 "Puzzle"
    Then the shopper receives an order confirmation email within 30 seconds
    And the email states the order number, the "Puzzle" line, the total of 40.00 and the delivery address

  @slow @sms
  Scenario: A shopper opted in to SMS receives both an email and an SMS when the order ships
    Given the shopper has verified a phone number and opted in to SMS
    And the shopper has placed a paid order for "Puzzle"
    When an operator ships the order
    Then an email and an SMS are produced for the shipment of the order

  @slow @chaos
  Scenario: A failing email channel is retried with increasing delay and then reported to operators
    Given the email channel refuses every message
    When the shopper places a paid order for "Puzzle"
    Then the order confirmation email is retried with increasing delay
    And it is eventually recorded as failed and visible to operators
    When the email channel recovers
    And an operator retries the failed notification
    Then the shopper receives an order confirmation email within 30 seconds

  @slow
  Scenario: The same order event never produces a second message
    Given the shopper has placed a paid order for "Puzzle"
    When the shopper submits the same checkout again because no answer arrived
    Then exactly one order confirmation email is sent for the order

  @slow @sms
  Scenario: Changed notification preferences are honoured for the next event
    Given the shopper has verified a phone number and opted in to SMS
    And the shopper has placed a paid order for "Puzzle"
    When the shopper changes their notification preferences to email only
    And an operator ships the order
    Then only an email is produced for the shipment of the order
