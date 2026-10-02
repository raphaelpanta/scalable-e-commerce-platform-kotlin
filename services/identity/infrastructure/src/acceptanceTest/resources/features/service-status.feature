Feature: Service status
  Operators need to know whether the identity service can do its work.

  Scenario: Operator checks that the identity service is available
    Given the identity service is running
    When an operator asks whether the service is healthy
    Then the service reports that it is healthy
