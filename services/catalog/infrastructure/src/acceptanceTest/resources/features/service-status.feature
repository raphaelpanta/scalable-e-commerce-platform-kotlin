Feature: Service status
  Operators need to know whether the catalogue service can do its work.

  Scenario: Operator checks that the catalogue service is available
    Given the catalogue service is running
    When an operator asks whether the service is healthy
    Then the service reports that it is healthy

  Scenario: Operator sees that the catalogue service is unavailable when its storage is down
    Given the catalogue service is running
    And its storage becomes unreachable
    When an operator asks whether the service is healthy
    Then the service reports that it is unhealthy
