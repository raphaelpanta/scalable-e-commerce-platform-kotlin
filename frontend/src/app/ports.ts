import { createContext, useContext } from 'react';

import type { CartPort } from './cart/cartPort.ts';
import type { IdentityPort } from './identity/identityPort.ts';
import type { OrderPort } from './order/orderPort.ts';
import type { PaymentPort } from './payment/paymentPort.ts';

// The platform ports of the shopping journey (US2), implemented over the generated contracts in
// src/api and injected by the composition root (main.tsx, the test harness), like the catalogue
// and session ports. The app layer never reaches the API edge directly.
export type Ports = {
  readonly cart: CartPort;
  readonly identity: IdentityPort;
  readonly order: OrderPort;
  readonly payment: PaymentPort;
};

export const PortsContext = createContext<Ports | undefined>(undefined);

export function usePorts(): Ports {
  const ports = useContext(PortsContext);
  if (ports === undefined) throw new Error('the shopping hooks require a PortsContext provider');
  return ports;
}
