Feature: Service status
  Operators need to know whether the order service can do its work.

  Scenario: Operator checks that the order service is available
    Given the order service is running
    When an operator asks whether the service is healthy
    Then the service reports that it is healthy
