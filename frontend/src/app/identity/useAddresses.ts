import {
  useMutation,
  type UseMutationResult,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';

import type { Address as AddressValue } from '@domain/address';

import type { Listing } from '../catalog/browseParams.ts';
import { usePorts } from '../ports.ts';
import type { Address, AddressPage } from './identityPort.ts';

export const ADDRESSES_KEY = ['identity', 'addresses'] as const;

/** The saved delivery addresses of the signed-in shopper (checkout address step, addresses page). */
export function useOwnAddresses(params: Listing = {}): UseQueryResult<AddressPage> {
  const { identity } = usePorts();
  return useQuery({
    queryKey: [...ADDRESSES_KEY, params.page ?? null, params.size ?? null] as const,
    queryFn: (): Promise<AddressPage> => identity.listOwnAddresses(params),
  });
}

export type AddressMutations = {
  readonly add: UseMutationResult<Address, Error, AddressValue>;
  readonly update: UseMutationResult<Address, Error, { id: string; address: AddressValue }>;
  readonly remove: UseMutationResult<undefined, Error, string>;
};

/** Add, replace and remove saved addresses; each refreshes every cached address list. */
export function useAddressMutations(): AddressMutations {
  const { identity } = usePorts();
  const queryClient = useQueryClient();
  const refresh = (): Promise<void> => queryClient.invalidateQueries({ queryKey: ADDRESSES_KEY });
  return {
    add: useMutation({
      mutationFn: (address: AddressValue) => identity.addOwnAddress(address),
      onSuccess: refresh,
    }),
    update: useMutation({
      mutationFn: ({ id, address }: { id: string; address: AddressValue }) =>
        identity.updateOwnAddress(id, address),
      onSuccess: refresh,
    }),
    remove: useMutation({
      mutationFn: async (id: string): Promise<undefined> => {
        await identity.deleteOwnAddress(id);
        return undefined;
      },
      onSuccess: refresh,
    }),
  };
}
