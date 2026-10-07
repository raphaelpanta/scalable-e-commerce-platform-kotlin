@us8
Feature: Run and observe the whole platform
  A platform engineer reaches every capability through a single public entry point, finds the logs of all
  services in one place correlated by a single request identifier, and sees every service's health and metrics.
  Scenarios tagged @observability need the Compose `observability` profile.

  Scenario: Only capabilities of the public entry point answer, and unknown ones are not found
    When a client requests a capability that does not exist
    Then the client receives a clear not-found answer
    When a client requests a service's health endpoint through the public entry point
    Then the client receives a clear not-found answer

  Scenario: A malformed correlation identifier is replaced by a new one
    When a client sends a request with a malformed correlation identifier
    Then the answer carries a different, well-formed correlation identifier

  @observability @slow
  Scenario: A request that crosses several services is traceable by its correlation identifier
    Given a signed-in shopper with a saved delivery address
    And a product "Desk lamp" priced at 50.00 with 5 units in stock
    And the shopper has 1 "Desk lamp" in the cart
    When the shopper checks out paying with a card the simulator approves
    Then the central log holds entries with the checkout's correlation identifier from at least 3 services
    And the central log holds entries with the checkout's correlation identifier from the order, payment and notification services

  @observability @slow
  Scenario: Every service reports that it is up, with request, error and latency metrics
    Given traffic has reached every service
    Then every service of the platform is up and reporting request, error and latency metrics

  @observability @slow @us7
  Scenario: A refused catalogue change is recorded in the central log
    Given a signed-in shopper
    When the shopper attempts the operator capability "create a product"
    Then the shopper is refused for lacking the operator role
    And the central log records the refused attempt with its correlation identifier

  @observability @slow
  Scenario: A shopper's page view and the services it reaches share one correlation id in the logs and one trace
    When a shopper opens the storefront and it loads the catalogue
    And the storefront reports the page view and a failed action
    Then the central log holds entries with the page view's correlation identifier from the storefront, gateway and catalog services
    And the page view is one trace holding the spans of the storefront, gateway and catalog services

  @observability @slow
  Scenario: No storefront telemetry contains the shopper's email, address or search term
    Given a signed-in shopper with a saved delivery address
    When the shopper searches the storefront for "walnut standing desk"
    And the storefront reports the page view and a failed action
    And an unfiltered storefront reports the page with the shopper's email, address and search term
    Then the storefront telemetry has reached the central log and the traces
    And no storefront telemetry contains the shopper's email, address or search term
