import {
  useMutation,
  type UseMutationResult,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';

import { usePorts } from '../ports.ts';
import type { NotificationChannel, NotificationPreferences } from './identityPort.ts';

export const PREFERENCES_KEY = ['identity', 'notification-preferences'] as const;

export function useNotificationPreferences(): UseQueryResult<NotificationPreferences> {
  const { identity } = usePorts();
  return useQuery({
    queryKey: PREFERENCES_KEY,
    queryFn: (): Promise<NotificationPreferences> => identity.getOwnNotificationPreferences(),
    staleTime: 0,
  });
}

/** Saves the channels; the answer replaces the cached preferences, a refusal changes nothing. */
export function useSavePreferences(): UseMutationResult<
  NotificationPreferences,
  Error,
  readonly NotificationChannel[]
> {
  const { identity } = usePorts();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (channels: readonly NotificationChannel[]) =>
      identity.updateOwnNotificationPreferences(channels),
    onSuccess: (preferences) => {
      queryClient.setQueryData(PREFERENCES_KEY, preferences);
    },
  });
}

export type PhoneVerification = {
  readonly request: UseMutationResult<undefined, Error, string>;
  readonly confirm: UseMutationResult<undefined, Error, string>;
};

/** Sends the code to a phone number and confirms it; a confirmed number refreshes the preferences. */
export function usePhoneVerification(): PhoneVerification {
  const { identity } = usePorts();
  const queryClient = useQueryClient();
  return {
    request: useMutation({
      mutationFn: async (phone: string): Promise<undefined> => {
        await identity.requestPhoneVerification(phone);
        return undefined;
      },
    }),
    confirm: useMutation({
      mutationFn: async (code: string): Promise<undefined> => {
        await identity.confirmPhoneVerification(code);
        return undefined;
      },
      onSuccess: () => queryClient.invalidateQueries({ queryKey: PREFERENCES_KEY }),
    }),
  };
}
