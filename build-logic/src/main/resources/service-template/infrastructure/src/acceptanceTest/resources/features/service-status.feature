Feature: Service status
  Operators need to know whether the __name__ service can do its work.

  Scenario: Operator checks that the __name__ service is available
    Given the __name__ service is running
    When an operator asks whether the service is healthy
    Then the service reports that it is healthy
