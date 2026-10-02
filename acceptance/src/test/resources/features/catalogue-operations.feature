@us7
Feature: Operate the catalogue and inventory
  A store operator creates products and categories, sets prices, adjusts stock levels and withdraws products from
  sale. Only accounts with the operator role can do this, and every change is attributed.

  Background:
    Given an operator is signed in

  Scenario: A new product is visible to shoppers immediately with its availability
    Given a new category "Outdoor"
    When the operator creates a product "Camping stove" in "Outdoor" priced at 120.00 with 5 units in stock
    Then an anonymous shopper finds "Camping stove" in the "Outdoor" category at 120.00 in stock
    And a shopper can add 5 "Camping stove" to a cart

  Scenario Outline: Stock adjustments are applied and recorded with who, when and why
    Given a product "Tent" priced at 300.00 with 10 units in stock
    When the operator adjusts the stock of "Tent" by <delta> because "<reason>"
    Then "Tent" has <level> units left in stock
    And the adjustment is recorded with the operator, the time and the reason "<reason>"

    Examples:
      | delta | level | reason                   |
      | 5     | 15    | Delivery received        |
      | -3    | 7     | Damaged during stocktake |

  Scenario: Withdrawing a product with open orders hides it while existing orders are unaffected
    Given a product "Hammock" priced at 75.00 with 5 units in stock
    And a signed-in shopper with a saved delivery address
    And the shopper has placed a paid order for "Hammock"
    When the operator withdraws "Hammock" from sale
    Then "Hammock" no longer appears when browsing or searching
    And "Hammock" can no longer be added to a cart
    And the shopper's order still lists 1 "Hammock" at 75.00

  Scenario Outline: A shopper cannot change the catalogue
    Given a product "Lamp" priced at 50.00 with 5 units in stock
    And a signed-in shopper
    When the shopper attempts the operator capability "<change>"
    Then the shopper is refused for lacking the operator role

    Examples:
      | change                        |
      | create a product              |
      | change the price of a product |
      | adjust stock                  |
      | withdraw a product            |
      | create a category             |
