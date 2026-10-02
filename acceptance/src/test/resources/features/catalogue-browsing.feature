@us1
Feature: Browse the catalogue without an account
  A shopper arriving at the store lists products, browses by category, searches by name and sees each product's
  price, images and availability, all without registering.

  Background:
    Given the seeded catalogue

  Scenario: Listing a category returns only its active products, page by page
    Given a category "Garden tools" with these products in stock:
      | Rake  |
      | Spade |
      | Hoe   |
    When an anonymous shopper lists the products of category "Garden tools" two per page
    Then the page shows 2 products out of 3 in total
    And every listed product belongs to category "Garden tools" and shows its name, price, primary image and availability

  Scenario: A product without stock is shown as out of stock and cannot be added to a cart
    Given a product "Sold-out lamp" with no stock
    When an anonymous shopper views the product "Sold-out lamp"
    Then it is shown as out of stock
    When an anonymous shopper tries to add 1 "Sold-out lamp" to the cart
    Then the cart refuses the product because it is not available

  Scenario: Searching returns matching products by relevance and never withdrawn ones
    Given a product "Lantern" named after a new search term
    And a product "Desk" that mentions the search term only in its description
    And a product "Old lantern" named after the search term that an operator has withdrawn
    When an anonymous shopper searches for the term
    Then the products "Lantern" and "Desk" are found in that order
    And the product "Old lantern" is not found

  Scenario: Requesting a product that does not exist gives a clear not-found answer
    When an anonymous shopper requests a product that does not exist
    Then the shopper is told the product was not found
