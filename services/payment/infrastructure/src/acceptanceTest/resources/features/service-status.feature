Feature: Service status
  Operators need to know whether the payment service can do its work.

  Scenario: Operator checks that the payment service is available
    Given the payment service is running
    When an operator asks whether the service is healthy
    Then the service reports that it is healthy
