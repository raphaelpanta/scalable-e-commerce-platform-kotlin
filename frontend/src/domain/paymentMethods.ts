// The payment methods the platform offers for local use (FR-006, pact-matrix.md P1): the seeded
// tokens of the payment simulator, as a build-time list. A shopper picks one by its label; the
// token travels as `paymentMethod: {type: card, token}`. No card number is ever typed, stored or
// sent: these are not cards, they are the simulator's named outcomes.
export const PAYMENT_METHOD_IDS = [
  'tok_sim_approve_4242',
  'tok_sim_decline_0001',
  'tok_sim_unreachable',
] as const;

export type PaymentMethodId = (typeof PAYMENT_METHOD_IDS)[number];

export type PaymentMethod = {
  readonly id: PaymentMethodId;
  readonly label: string;
  readonly description: string;
};

/** How the list is introduced to the shopper: these methods exist for local development only. */
export const PAYMENT_METHODS_SCOPE =
  'Local development payment methods: a simulated provider, no real card and no card number.';

export const PAYMENT_METHODS: readonly PaymentMethod[] = [
  {
    id: 'tok_sim_approve_4242',
    label: 'Simulated card that is approved',
    description: 'The simulated provider approves the payment and the order is confirmed.',
  },
  {
    id: 'tok_sim_decline_0001',
    label: 'Simulated card that is declined',
    description:
      'The simulated provider declines the payment; the order is cancelled and the cart kept.',
  },
  {
    id: 'tok_sim_unreachable',
    label: 'Simulated card with an unreachable provider',
    description:
      'The provider does not answer: the payment stays pending until it is retried or the payment window ends.',
  },
];

export type PaymentMethodRequest = { readonly type: 'card'; readonly token: PaymentMethodId };

export const PaymentMethod = {
  isId(candidate: string): candidate is PaymentMethodId {
    return (PAYMENT_METHOD_IDS as readonly string[]).includes(candidate);
  },
  byId(id: PaymentMethodId): PaymentMethod {
    const found = PAYMENT_METHODS.find((method) => method.id === id);
    if (found === undefined) throw new Error(`unknown payment method ${id}`);
    return found;
  },
  /** The `paymentMethod` member of a place-order request. */
  toRequest(id: PaymentMethodId): PaymentMethodRequest {
    return { type: 'card', token: id };
  },
} as const;
