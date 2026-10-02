Feature: Service status
  Operators need to know whether the cart service can do its work.

  Scenario: Operator checks that the cart service is available
    Given the cart service is running
    When an operator asks whether the service is healthy
    Then the service reports that it is healthy
