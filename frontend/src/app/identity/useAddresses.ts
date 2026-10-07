import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import { usePorts } from '../ports.ts';
import type { AddressPage } from './identityPort.ts';

export const ADDRESSES_KEY = ['identity', 'addresses'] as const;

/** The saved delivery addresses of the signed-in shopper (checkout address step). */
export function useOwnAddresses(): UseQueryResult<AddressPage> {
  const { identity } = usePorts();
  return useQuery({
    queryKey: ADDRESSES_KEY,
    queryFn: (): Promise<AddressPage> => identity.listOwnAddresses(),
  });
}
