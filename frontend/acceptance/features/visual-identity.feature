Feature: Vibestore visual identity
  The storefront carries one warm editorial identity. Transaction surfaces keep their behaviour and names.

  Scenario: An empty cart points the way back
    Given the seeded catalogue
    When the shopper views the cart
    Then the cart has no lines
    And the empty cart offers a single way back to the products

  Scenario: A missing page offers a way home
    When I open the page "/this-page-does-not-exist"
    Then I see "404"
    And the not-found page offers a way back to the store
