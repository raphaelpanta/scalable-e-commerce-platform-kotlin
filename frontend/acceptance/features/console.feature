@us6
Feature: Fulfil orders and adjust stock from a minimal console
  An operator signs in to a restricted console, advances and cancels orders and adjusts the stock of existing
  products. Creating, editing and withdrawing products and categories are not offered: the console says that
  catalogue editing is done through the API for now. Accounts without the operator role are refused.

  Background:
    Given the seeded catalogue

  Scenario: An operator advances a paid order to shipped and the shopper's order shows it
    Given a product "Mug" priced at 15.00 with 5 units in stock
    And a shopper has a paid order for "Mug"
    When I sign in as the operator
    And the operator opens the order in the console
    Then the order is shown as "Placed" with its payment "Approved"
    And the console offers only "Start preparing" and "Cancel order"
    When the operator chooses "Start preparing" and confirms
    Then the order is shown as "Being prepared" with its payment "Approved"
    When the operator chooses "Mark as shipped" and confirms
    Then the order is shown as "Shipped" with its payment "Approved"
    And the shopper's own view of the order is "shipped"

  Scenario: An operator cancels a placed order after confirming
    Given a product "Teapot" priced at 40.00 with 5 units in stock
    And a shopper has a paid order for "Teapot"
    When I sign in as the operator
    And the operator opens the order in the console
    And the operator chooses "Cancel order" and confirms
    Then the order is shown as "Cancelled (cancelled by the store)" with its payment "Approved"
    And the shopper's own view of the order is "cancelled"

  Scenario: The orders list shows every shopper's orders and can be narrowed by status
    Given a product "Vase" priced at 30.00 with 5 units in stock
    And a shopper has a paid order for "Vase"
    When I sign in as the operator
    And the operator filters the orders by "Placed"
    Then the order list shows the order with status "Placed" and payment "Approved"
    When the operator filters the orders by "Cancelled"
    Then the order list does not show the order

  Scenario: Adjusting the stock of a sold-out product makes it addable to carts again
    Given a product "Lamp" with no stock
    When I sign in as the operator
    And the operator adjusts the stock of "Lamp" by 5 because "Restock from the supplier"
    Then the console reports the stock of "Lamp" as 5
    When I sign out
    And an anonymous shopper views the product "Lamp"
    Then it is shown as in stock
    And the product can be added to the cart

  Scenario: An adjustment that would make the stock negative is explained next to the delta
    Given a product "Chair" priced at 55.00 with 2 units in stock
    When I sign in as the operator
    And the operator adjusts the stock of "Chair" by -9 because "Recount"
    Then the delta field explains "would make available quantity negative"

  Scenario: A shopper is refused the console
    Given a signed-in shopper with a saved delivery address
    When I open the page "/console/orders"
    Then I see the heading "Not allowed"
    And I do not see "Filter by status"
    And the primary navigation has no link "Console"
    When I open the page "/console/stock"
    Then I see the heading "Not allowed"

  Scenario: The console offers no catalogue editing and says so
    Given a product "Plate" priced at 8.00 with 5 units in stock
    When I sign in as the operator
    And I open the page "/console/stock"
    Then I see "done through the API for now"
    And the console offers no way to create, edit, withdraw or reinstate a product

  Scenario: An operator adjusts stock using only the keyboard
    Given a product "Bowl" priced at 12.00 with 3 units in stock
    When I sign in as the operator
    And the operator opens the stock page searching for "Bowl"
    And I tab to the adjust button of "Bowl"
    And I activate the focused element
    Then the focus is in the field "Change in units"
    When I type "2"
    And I press "Tab"
    And I type "Keyboard only recount"
    And I press "Tab"
    And I activate the focused element
    Then the console reports the stock of "Bowl" as 5
