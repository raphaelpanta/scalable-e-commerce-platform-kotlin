Feature: Service status
  Operators need to know whether the notification service can do its work.

  Scenario: Operator checks that the notification service is available
    Given the notification service is running
    When an operator asks whether the service is healthy
    Then the service reports that it is healthy
