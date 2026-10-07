@us2
Feature: Build a cart anonymously and keep it after signing in
  A shopper adds products to a cart before signing in, changes quantities, removes items and sees the running
  total. The cart survives a reload and a browser restart on the same device. When the shopper registers,
  verifies the email through the local mail inbox and signs in, the anonymous cart is merged into the account
  cart and the result is shown.

  Background:
    Given the seeded catalogue
    And a product "Coffee beans" priced at 25.00 with 10 units in stock

  Scenario: Adding a product shows the line, the unit price at the time of adding and a correct total
    When an anonymous shopper adds 2 "Coffee beans" to the cart
    Then the cart has 1 line
    And the line for "Coffee beans" has quantity 2 at a unit price of 25.00
    And the cart total is 50.00

  Scenario: Changing the quantity recalculates the total and removing the line empties the cart
    Given an anonymous shopper has 2 "Coffee beans" in the cart
    When the shopper changes the quantity of "Coffee beans" to 3
    Then the line for "Coffee beans" has quantity 3 at a unit price of 25.00
    And the cart total is 75.00
    When the shopper removes the "Coffee beans" line
    Then the cart has no lines

  Scenario: The cart survives a page reload
    Given an anonymous shopper has 2 "Coffee beans" in the cart
    When I reload the page
    Then the cart has 1 line
    And the line for "Coffee beans" has quantity 2 at a unit price of 25.00

  Scenario: The cart survives a browser restart on the same device
    Given an anonymous shopper has 2 "Coffee beans" in the cart
    When the shopper closes and reopens the browser
    Then the cart has 1 line
    And the line for "Coffee beans" has quantity 2 at a unit price of 25.00

  Scenario: Adding more units than are in stock is refused stating the available quantity
    When an anonymous shopper tries to add 11 "Coffee beans" to the cart
    Then the quantity is refused because only 10 units are available

  Scenario: A price change after adding is shown with the current price and flagged
    Given an anonymous shopper has 1 "Coffee beans" in the cart
    And an operator changes the price of "Coffee beans" to 27.50
    When the shopper views the cart
    Then the line for "Coffee beans" shows the current unit price of 27.50
    And the line for "Coffee beans" is flagged as having changed price

  Scenario: Registering, verifying the email through the mailbox and signing in keeps the anonymous cart
    Given an anonymous shopper has 2 "Coffee beans" in the cart
    When the shopper registers with a new email address and a valid password
    Then the registration is acknowledged
    When the shopper verifies the email through the link in the message received
    And the shopper signs in
    Then the account cart holds 2 "Coffee beans"

  Scenario: Signing in merges the anonymous cart into the account cart, capped at the available stock
    Given a product "Teapot" priced at 40.00 with 3 units in stock
    And a registered shopper whose account cart holds 2 "Teapot"
    And the shopper, signed out, has an anonymous cart with 2 "Teapot" and 1 "Coffee beans"
    When the shopper signs in
    Then the account cart holds 3 "Teapot" and 1 "Coffee beans"
    And the shopper is told that the "Teapot" quantity was capped from 4 to 3
