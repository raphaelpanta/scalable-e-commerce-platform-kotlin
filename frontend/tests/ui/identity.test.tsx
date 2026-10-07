import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { INVALID_CREDENTIALS_MESSAGE, UNVERIFIED_MESSAGE } from '@ui/pages/SignInPage';

import { renderApp } from './render.tsx';
import { cartServer } from '../msw/cart.ts';
import { rake } from '../msw/catalog.ts';
import {
  ANA,
  GENERIC_REGISTRATION_MESSAGE,
  identityServer,
  THROTTLED,
  UNVERIFIED,
  VALID_TOKEN,
} from '../msw/identity.ts';

async function fillCredentials(email: string, password: string): Promise<void> {
  const user = userEvent.setup();
  await user.clear(screen.getByLabelText('Email'));
  await user.type(screen.getByLabelText('Email'), email);
  await user.clear(screen.getByLabelText('Password'));
  await user.type(screen.getByLabelText('Password'), password);
}

describe('Register (FR-005)', () => {
  it('pre-checks the password length, then shows the one generic message for a new and for a known email', async () => {
    const user = userEvent.setup();
    renderApp('/register');
    await screen.findByRole('heading', { level: 1, name: 'Create an account' });
    await fillCredentials('new@example.com', 'short');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Use at least 12 characters.');
    expect(screen.getByLabelText('Password')).toHaveAccessibleDescription(/Use at least 12/);
    expect(identityServer.requests).toHaveLength(0);

    await fillCredentials('new@example.com', ANA.password);
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    const first = await screen.findByRole('status');
    expect(first).toHaveTextContent(GENERIC_REGISTRATION_MESSAGE);

    await fillCredentials(ANA.email, ANA.password);
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    await waitFor(() => {
      expect(identityServer.requests.filter((r) => r.method === 'POST')).toHaveLength(2);
    });
    expect(screen.getByRole('status')).toHaveTextContent(GENERIC_REGISTRATION_MESSAGE);
    expect(screen.getByRole('status').textContent).toBe(first.textContent);
  });

  it('shows the platform field errors next to the fields', async () => {
    const user = userEvent.setup();
    renderApp('/register');
    await screen.findByRole('heading', { level: 1, name: 'Create an account' });
    await fillCredentials('new@example.com', 'new@example.com');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('must not be equal to the email');
    expect(screen.getByLabelText('Password')).toHaveAccessibleDescription(
      /must not be equal to the email/,
    );
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});

describe('Verify email (FR-005, storefront-routes.md /verify-email)', () => {
  it('reads the token once, removes it from the address with a history replace and shows success', async () => {
    const { router } = renderApp(`/verify-email?token=${VALID_TOKEN}`);
    expect(
      await screen.findByText('Your email is verified. You can sign in now.'),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/verify-email');
    expect(router.state.location.search).toBe('');
    expect(router.state.historyAction).toBe('REPLACE');
    const verify = identityServer.requests.filter((r) => r.url.endsWith('/verify-email'));
    expect(verify).toHaveLength(1);
    expect(verify[0]?.body).toEqual({ token: VALID_TOKEN });
    expect(within(screen.getByRole('main')).getByRole('link', { name: 'Sign in' })).toHaveAttribute(
      'href',
      '/sign-in',
    );
  });

  it('serves the emailed /verify path and shows one generic message for an invalid or expired link', async () => {
    const { router } = renderApp('/verify?token=tok-expired');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This verification link is invalid or has expired.',
    );
    expect(router.state.location.search).toBe('');
    expect(screen.getByRole('link', { name: 'Register again' })).toHaveAttribute(
      'href',
      '/register',
    );
  });

  it('shows the same generic message without any request when the token is missing', async () => {
    renderApp('/verify-email');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This verification link is invalid or has expired.',
    );
    expect(identityServer.requests.filter((r) => r.url.endsWith('/verify-email'))).toHaveLength(0);
  });
});

describe('Sign in (FR-005, FR-014, redirect rule)', () => {
  it('keeps the refusals generic: invalid credentials, unverified email, throttled with a countdown', async () => {
    const user = userEvent.setup();
    renderApp('/sign-in', { kind: 'anonymous' }, { realSession: true });
    await screen.findByRole('heading', { level: 1, name: 'Sign in' });

    await fillCredentials(ANA.email, 'wrong-passphrase!');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(INVALID_CREDENTIALS_MESSAGE);

    await fillCredentials(UNVERIFIED.email, UNVERIFIED.password);
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(UNVERIFIED_MESSAGE);
    });

    await fillCredentials(THROTTLED.email, THROTTLED.password);
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('heading', { name: 'Too many requests' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Try again in 7 seconds.');
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeDisabled();
  });

  it('signs in, merges the anonymous cart, shows the capped lines once and lands on the validated next', async () => {
    const user = userEvent.setup();
    cartServer.seed([{ productId: rake.id, quantity: 5 }]);
    cartServer.nextMerge = {
      cappedLines: [{ productId: rake.id, requestedQuantity: 7, appliedQuantity: 5 }],
    };
    const { router } = renderApp(
      '/sign-in?next=%2Fcart',
      { kind: 'anonymous' },
      { realSession: true },
    );
    await screen.findByRole('heading', { level: 1, name: 'Sign in' });
    await fillCredentials(ANA.email, ANA.password);
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/cart');
    });
    expect(router.state.historyAction).toBe('REPLACE');
    const signIn = identityServer.requests.find((r) => r.url.endsWith('/sessions'));
    expect(signIn?.body).toEqual({ email: ANA.email, password: ANA.password });
    const merge = cartServer.requests.find((r) => r.url.endsWith('/merge'));
    expect(merge?.method).toBe('POST');
    expect(await screen.findByRole('button', { name: 'Sign out' })).toBeInTheDocument();

    const notice = screen.getByText(/Your cart was merged/).parentElement!;
    expect(notice).toHaveTextContent('Rake: requested 7, kept 5');
    await user.click(screen.getByRole('button', { name: 'Dismiss' }));
    expect(screen.queryByText(/Your cart was merged/)).not.toBeInTheDocument();
    expect(await screen.findByRole('heading', { level: 1, name: 'Your cart' })).toBeInTheDocument();
  });

  it('ignores an unsafe next and goes home', async () => {
    const user = userEvent.setup();
    const { router } = renderApp(
      '/sign-in?next=%2F%2Fevil.example',
      { kind: 'anonymous' },
      { realSession: true },
    );
    await screen.findByRole('heading', { level: 1, name: 'Sign in' });
    await fillCredentials(ANA.email, ANA.password);
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/');
    });
    expect(router.state.location.search).toBe('');
  });

  it('sends an already signed-in visitor straight to next', async () => {
    const { router } = renderApp('/sign-in?next=%2Forders', {
      kind: 'signedIn',
      roles: ['shopper'],
    });
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/orders');
    });
  });
});
