import type { UseQueryResult } from '@tanstack/react-query';
import type { ReactNode } from 'react';

import { ThrottledError } from '@api/problem';

import { describeError, ErrorState } from './ErrorState.tsx';
import { Loading, type LoadingVariant } from './Loading.tsx';
import { Throttled } from './Throttled.tsx';

export type QueryBoundaryProps<T> = {
  readonly query: UseQueryResult<T>;
  readonly loadingLabel?: string;
  /** The shape of the loading state: a skeleton that matches what is arriving (default spinner). */
  readonly loading?: LoadingVariant;
  /** Renders the loaded data; the empty state is the caller's (it knows the next action). */
  readonly children: (data: T) => ReactNode;
};

/**
 * The loading, error and throttled states of one query (FR-016, storefront-routes.md "States
 * every route must render"): a status while in flight (also while a retry is in flight), the
 * countdown for a 429 and a readable error with retry otherwise. Success is left to the caller.
 */
export function QueryBoundary<T>({
  query,
  loadingLabel = 'Loading…',
  loading = 'spinner',
  children,
}: QueryBoundaryProps<T>): ReactNode {
  if (query.isPending || (query.isError && query.isFetching)) {
    return <Loading label={loadingLabel} variant={loading} />;
  }
  if (query.isError) {
    const retry = (): void => {
      void query.refetch();
    };
    if (query.error instanceof ThrottledError) {
      return <Throttled retryAfterSeconds={query.error.retryAfterSeconds} onRetry={retry} />;
    }
    return <ErrorState {...describeError(query.error)} onRetry={retry} />;
  }
  return children(query.data);
}
