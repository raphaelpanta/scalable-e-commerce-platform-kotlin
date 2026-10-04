import { QueryClient } from '@tanstack/react-query';

/** Decides whether TanStack Query may retry: never for a 4xx, including 429 (FR-016). */
export function shouldRetry(failureCount: number, error: unknown, maxRetries = 2): boolean {
  if (failureCount >= maxRetries) return false;
  const status = statusOf(error);
  if (status !== undefined && status >= 400 && status < 500) return false;
  return true;
}

function statusOf(error: unknown): number | undefined {
  if (typeof error !== 'object' || error === null) return undefined;
  const problem = (error as { problem?: { status?: unknown } }).problem;
  return typeof problem?.status === 'number' ? problem.status : undefined;
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: shouldRetry,
        refetchOnWindowFocus: false,
        staleTime: 30_000,
      },
      mutations: {
        retry: false,
      },
    },
  });
}
