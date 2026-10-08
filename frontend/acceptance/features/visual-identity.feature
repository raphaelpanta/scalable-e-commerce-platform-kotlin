@visual-identity
Feature: A recognisable storefront identity
  The store presents itself as Vibestore: its name in the header and the tab, an invitation to browse by category on
  the home page, and a deliberate look for products that have no photograph.

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
