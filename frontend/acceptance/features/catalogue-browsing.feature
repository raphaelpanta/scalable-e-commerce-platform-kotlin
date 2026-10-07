@us1
Feature: Browse the catalogue without an account
  A shopper arriving at the store lists products, browses by category, searches by name and sees each product's
  price, images and availability, all without registering.

  Background:
    Given the seeded catalogue

  Scenario: Listing a category shows only its active products, page by page
    Given a category "Garden tools" with these products in stock:
      | Rake  |
      | Spade |
      | Hoe   |
    When an anonymous shopper opens the category "Garden tools" two per page
    Then the page shows 2 products out of 3 in total
    And every listed product belongs to category "Garden tools" and shows its name, price, primary image and availability
    And the address carries the category "Garden tools" and two per page
    When I follow the link "Next page"
    Then the page shows 1 product out of 3 in total
    And the address carries the second page

  Scenario: Opening a product shows its description, price, image and availability
    Given a category "Garden tools" with these products in stock:
      | Rake |
    When an anonymous shopper views the product "Rake"
    Then the product page shows the name, description, price, primary image and availability of "Rake"
    And it is shown as in stock
    And the product can be added to the cart

  Scenario: A product without stock is shown as out of stock and cannot be added to a cart
    Given a product "Sold-out lamp" with no stock
    When an anonymous shopper views the product "Sold-out lamp"
    Then it is shown as out of stock
    And the product cannot be added to the cart
    And the shopper is told why the product cannot be added

  Scenario: Searching returns matching products by relevance and never withdrawn ones
    Given a product "Lantern" named after a new search term
    And a product "Desk" that mentions the search term only in its description
    And a product "Old lantern" named after the search term that an operator has withdrawn
    When an anonymous shopper searches for the term
    Then the products "Lantern" and "Desk" are found in that order
    And the product "Old lantern" is not found
    And the address carries the search term

  Scenario: Searching for something nothing matches shows a clear empty result
    When an anonymous shopper searches for a term nothing matches
    Then the shopper is told nothing was found
    And the shopper is offered to browse all products

  Scenario: Following a link to a product that does not exist gives a clear not-found page
    When an anonymous shopper follows a link to a product that does not exist
    Then I see the not-found page
    And the shopper is offered a way back to browsing

  Scenario: A shared category link reopens the same page after a reload
    Given a category "Garden tools" with these products in stock:
      | Rake  |
      | Spade |
      | Hoe   |
    When an anonymous shopper opens the second page of the category "Garden tools" two per page
    And I reload the page
    Then the page shows 1 product out of 3 in total
    And the address carries the second page
    And the category "Garden tools" is marked as the current one

  Scenario: A shopper reaches a product page using only the keyboard
    Given a category "Garden tools" with these products in stock:
      | Rake |
    When an anonymous shopper opens the category "Garden tools"
    And I tab to the product "Rake"
    Then the focused element is visible
    When I activate the focused element
    Then the product page shows the name, description, price, primary image and availability of "Rake"
    When I tab to "Add to cart"
    Then the focused element is visible
