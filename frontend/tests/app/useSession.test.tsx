import { act, render, renderHook, screen } from '@testing-library/react';
import { type JSX, Profiler, type ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { SESSION_WARNING_LEAD_MS } from '@app/session/sessionStore';
import { useSession, useSessionStore } from '@app/session/useSession';
import { Email } from '@domain/email';
import { Password } from '@domain/password';

import { harness, Providers } from '../ui/render.tsx';

const email = (() => {
  const parsed = Email.parse('ana@example.com');
  if (!parsed.ok) throw new Error('fixture');
  return parsed.value;
})();
const password = (() => {
  const parsed = Password.forSignIn('S3cure-passphrase!');
  if (!parsed.ok) throw new Error('fixture');
  return parsed.value;
})();

const T0 = new Date('2026-10-04T10:00:00Z');
const CLOCK_TICK_MS = 15_000;

let renders = 0;

/** Counts commits of its subtree (the clock's re-renders) without side effects during render. */
function Counted({ children }: { children: ReactNode }): JSX.Element {
  return (
    <Profiler
      id="session"
      onRender={() => {
        renders += 1;
      }}
    >
      {children}
    </Profiler>
  );
}

function Probe(): JSX.Element {
  const { summary, endsSoon, resolving } = useSession();
  return (
    <p>
      {summary.state}:{endsSoon ? 'ending' : 'fine'}:{resolving ? 'resolving' : 'resolved'}
    </p>
  );
}

describe('useSession', () => {
  beforeEach(() => {
    renders = 0;
    vi.useFakeTimers();
    vi.setSystemTime(T0);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('useSessionStore throws a clear error outside the provider', () => {
    expect(() => renderHook(() => useSessionStore())).toThrow(
      'useSession requires a SessionStoreContext provider',
    );
  });

  it('turns the "about to end" warning on by the clock once signed in with an expiry', async () => {
    const h = harness({ kind: 'anonymous' });
    render(
      <Providers harness={h}>
        <Counted>
          <Probe />
        </Counted>
      </Providers>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(screen.getByText('anonymous:fine:resolved')).toBeInTheDocument();

    // Anonymous: the clock is off, nothing re-renders on its own.
    const rendersBefore = renders;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(CLOCK_TICK_MS * 2);
    });
    expect(renders).toBe(rendersBefore);

    // The warning is due exactly at sign-in time + 1 s: on the first tick, not before.
    const signInAt = Date.now();
    h.port.signInSummary = {
      expiresAt: new Date(signInAt + SESSION_WARNING_LEAD_MS + 1000).toISOString(),
      roles: ['shopper'],
    };
    await act(async () => {
      await h.sessionStore.signIn(email, password);
      // TanStack Query notifies its observers on a (faked) setTimeout(0).
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(screen.getByText('signedIn:fine:resolved')).toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(CLOCK_TICK_MS);
    });
    expect(screen.getByText('signedIn:ending:resolved')).toBeInTheDocument();
  });

  it('reads the clock when it is switched on, not the time the page was opened', async () => {
    const h = harness({ kind: 'anonymous' });
    render(
      <Providers harness={h}>
        <Counted>
          <Probe />
        </Counted>
      </Providers>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    // Ten seconds pass before the sign-in; the warning is due at the sign-in instant.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    const signInAt = Date.now();
    h.port.signInSummary = {
      expiresAt: new Date(signInAt + SESSION_WARNING_LEAD_MS).toISOString(),
      roles: ['shopper'],
    };
    await act(async () => {
      await h.sessionStore.signIn(email, password);
      // TanStack Query notifies its observers on a (faked) setTimeout(0).
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(screen.getByText('signedIn:ending:resolved')).toBeInTheDocument();
  });

  it('starts with a real clock value when the page opens already signed in with an expiry', async () => {
    const h = harness({ kind: 'anonymous' });
    h.port.signInSummary = {
      expiresAt: new Date(T0.getTime() + 3_600_000).toISOString(),
      roles: ['shopper'],
    };
    await h.sessionStore.signIn(email, password);
    render(
      <Providers harness={h}>
        <Counted>
          <Probe />
        </Counted>
      </Providers>,
    );
    expect(screen.getByText('signedIn:fine:resolved')).toBeInTheDocument();
  });

  it('keeps the clock off while signed in without a known expiry', async () => {
    const h = harness({ kind: 'signedIn', roles: ['shopper'] });
    render(
      <Providers harness={h}>
        <Counted>
          <Probe />
        </Counted>
      </Providers>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(screen.getByText('signedIn:fine:resolved')).toBeInTheDocument();
    const rendersBefore = renders;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(CLOCK_TICK_MS * 2);
    });
    expect(renders).toBe(rendersBefore);
  });
});
