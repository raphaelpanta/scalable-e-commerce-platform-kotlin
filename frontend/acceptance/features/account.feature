@us4
Feature: Manage the account
  A signed-in shopper maintains delivery addresses and notification preferences, signs out, resets a
  forgotten password from the link in the email and may delete the account with their password.

  Background:
    Given the seeded catalogue

  Scenario: Delivery address changes persist and are visible on the next sign-in
    Given a signed-in shopper
    When the shopper adds a delivery address in "Lisboa"
    And the shopper adds a delivery address in "Porto"
    And the shopper changes the "Porto" address to "Braga"
    And the shopper removes the "Lisboa" address
    And the shopper signs out and signs in again
    Then the shopper's delivery addresses are exactly "Braga"

  Scenario: Text messages can be chosen only after a phone number is verified
    Given a signed-in shopper
    When the shopper opens the notification preferences
    Then email notifications are on
    And text messages cannot be chosen yet
    When the shopper verifies a new phone number with the code received by text message
    And the shopper turns on text messages and saves
    Then the preferences are saved
    And email and text messages are both on after reloading the page

  Scenario: Signing out ends the session and protected pages ask to sign in again
    Given a signed-in shopper
    When I sign out
    Then I see the heading "Products"
    And I do not see "Sign out"
    When I open the page "/account"
    Then the shopper is asked to sign in first

  Scenario: A forgotten password is reset once with the emailed link and the old password stops working
    Given a registered shopper
    When the shopper asks for a password reset
    Then the confirmation is the same for an email nobody registered
    And a reset message arrives for the shopper
    When the shopper chooses a new password with the link in the message
    Then signing in with the old password is refused
    And signing in with the new password succeeds
    And the same reset link cannot be used a second time

  Scenario: Deleting the account asks for the password, signs out and refuses the next sign-in
    Given a signed-in shopper
    When the shopper starts to delete the account with a wrong password
    Then the account is not deleted and the password is reported as incorrect
    When the shopper deletes the account confirming with their password
    Then the shopper is told the account was deleted and is signed out
    And signing in with the deleted account is refused
