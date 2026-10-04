@security
Feature: Authorisation sweep
  Zero unauthorised accesses succeed (SC-010): every operator capability is refused to a shopper, and every
  protected capability is refused to an anonymous caller. Access is denied by default (FR-005).

  Scenario Outline: A shopper is refused every operator capability
    Given a signed-in shopper
    When the shopper attempts the operator capability "<capability>"
    Then the shopper is refused for lacking the operator role

    Examples:
      | capability                       |
      | create a product                 |
      | update a product                 |
      | withdraw a product               |
      | reinstate a product              |
      | adjust stock                     |
      | add a product image              |
      | create a category                |
      | update a category                |
      | withdraw a category              |
      | reinstate a category             |
      | change an order status           |
      | list failed notifications        |
      | retry a failed notification      |
      | read the payment simulator rules |

  Scenario Outline: An anonymous caller is refused every protected capability
    When an anonymous caller attempts the capability "<capability>"
    Then the caller is asked to authenticate

    Examples: Identity
      | capability                      |
      | sign out                        |
      | view the profile                |
      | update the profile              |
      | delete the account              |
      | list addresses                  |
      | add an address                  |
      | update an address               |
      | delete an address               |
      | view notification preferences   |
      | update notification preferences |
      | request a phone verification    |
      | confirm a phone verification    |

    Examples: Catalogue changes
      | capability           |
      | create a product     |
      | update a product     |
      | withdraw a product   |
      | reinstate a product  |
      | adjust stock         |
      | add a product image  |
      | create a category    |
      | update a category    |
      | withdraw a category  |
      | reinstate a category |

    Examples: Cart, orders and payments
      | capability                       |
      | merge a cart                     |
      | place an order                   |
      | list orders                      |
      | view an order                    |
      | cancel an order                  |
      | change an order status           |
      | view a payment attempt           |
      | list payment attempts            |
      | list refunds                     |
      | view a refund                    |
      | read the payment simulator rules |

    Examples: Notifications
      | capability                  |
      | list notifications          |
      | list failed notifications   |
      | retry a failed notification |

  Scenario: A forged session is refused rather than treated as anonymous
    Given a caller presenting a forged session
    When the caller browses the catalogue
    Then the caller is asked to authenticate
