@visual-identity
Feature: A recognisable storefront identity
  The store presents itself as Vibestore: its name in the header and the tab, an invitation to browse by category on
  the home page, a deliberate look for products that have no photograph, and friendly ways back when there is nothing
  to show.

  Background:
    Given the seeded catalogue

  Scenario: A visitor recognises the store
    When I open the storefront
    Then the header shows the Vibestore name linking to the home page
    And the tab title names Vibestore

  Scenario: The home page invites browsing by category
    Given a category "Garden tools" with these products in stock:
      | Rake |
    When I open the storefront
    Then I see "Good things, good vibes"
    When I choose a featured category
    Then I see the products of the chosen category

  Scenario: A product without a photograph still looks deliberate
    Given a category "Garden tools" with a product "Hoe" listed without an image
    When an anonymous shopper opens the category "Garden tools"
    Then the product "Hoe" shows the "no image available" placeholder

  Scenario: An empty cart points the way back
    When the shopper views the cart
    Then the cart has no lines
    And the empty cart offers a single way back to the products

  Scenario: A missing page offers a way home
    When I open the page "/this-page-does-not-exist"
    Then I see "404"
    And the not-found page offers a way back to the store

  Scenario: The store is usable in a dark appearance
    Given a category "Garden tools" with these products in stock:
      | Rake |
    And the shopper prefers a dark appearance
    When I open the storefront
    Then the page passes the accessibility audit
    When an anonymous shopper opens the category "Garden tools"
    Then the page passes the accessibility audit
    When the shopper views the cart
    Then the page passes the accessibility audit

  Scenario: Nothing scrolls sideways on a small phone
    Given a category "Garden tools" with these products in stock:
      | Rake |
    And the shopper uses a narrow phone screen
    When I open the storefront
    Then the page does not scroll sideways
    When an anonymous shopper opens the category "Garden tools"
    Then the page does not scroll sideways
    When the shopper views the cart
    Then the page does not scroll sideways

  Scenario: Operators work under the same brand
    When I sign in as the operator
    And I open the page "/console/orders"
    Then the console shows the Vibestore name beside "Console"
    And the console offers the orders list
