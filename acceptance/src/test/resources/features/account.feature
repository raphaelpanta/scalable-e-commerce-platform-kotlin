@us3
Feature: Register, sign in and manage the account
  A shopper creates an account with email and password, verifies the email, signs in, signs out, resets a
  forgotten password and maintains delivery addresses. Signing in is required before placing an order.

  Scenario: Registering creates an unverified account and sends a verification message
    When a visitor registers with a new email address and a valid password
    Then the registration is acknowledged
    And a verification message arrives for that address
    And signing in before verifying the email is refused

  @slow
  Scenario: Registering an email that is already registered reveals nothing about it
    Given a registered shopper
    When a visitor registers again with the shopper's email address
    Then the registration is acknowledged with the same generic message
    And no second verification message is sent

  Scenario: A verified shopper signs in and can use protected capabilities
    Given a registered shopper
    When the shopper signs in
    Then the shopper can see their own profile

  Scenario: Five wrong passwords in a row throttle further sign-in attempts
    Given a registered shopper
    When the shopper signs in with a wrong password 5 times in a row
    Then the next sign-in attempt is throttled even with the correct password

  Scenario: Delivery address changes persist and are visible on the next sign-in
    Given a signed-in shopper
    When the shopper adds a delivery address in "Lisboa"
    And the shopper adds a delivery address in "Porto"
    And the shopper changes the "Porto" address to "Braga"
    And the shopper removes the "Lisboa" address
    And the shopper signs out and signs in again
    Then the shopper's delivery addresses are exactly "Braga"

  Scenario: A forgotten password is reset once and the old password stops working
    Given a registered shopper
    When the shopper requests a password reset
    Then a reset message arrives for the shopper
    When the shopper sets a new password with the reset
    Then signing in with the old password is refused
    And signing in with the new password succeeds
    And the same reset cannot be used a second time

  Scenario: Placing an order while signed out asks the shopper to sign in and keeps the cart
    Given a product "Notebook" priced at 12.00 with 5 units in stock
    And an anonymous shopper has 1 "Notebook" in the cart
    When the anonymous shopper tries to place the order
    Then the shopper is asked to sign in first
    And the cart still holds 1 "Notebook"
