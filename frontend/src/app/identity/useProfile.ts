import {
  useMutation,
  type UseMutationResult,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';

import { usePorts } from '../ports.ts';
import type { Account } from './identityPort.ts';

export const PROFILE_KEY = ['identity', 'profile'] as const;

/** The signed-in account (email, display name). */
export function useOwnProfile(): UseQueryResult<Account> {
  const { identity } = usePorts();
  return useQuery({
    queryKey: PROFILE_KEY,
    queryFn: (): Promise<Account> => identity.getOwnProfile(),
    staleTime: 0,
  });
}

/** Changes the display name; the answer replaces the cached account. */
export function useUpdateProfile(): UseMutationResult<Account, Error, string> {
  const { identity } = usePorts();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (displayName: string) => identity.updateOwnProfile(displayName),
    onSuccess: (account) => {
      queryClient.setQueryData(PROFILE_KEY, account);
    },
  });
}
