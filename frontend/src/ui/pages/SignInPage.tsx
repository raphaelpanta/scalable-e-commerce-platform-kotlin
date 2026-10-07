import { type JSX, type SubmitEvent, useEffect, useId, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';

import { ProblemError, ThrottledError, UnauthorizedError } from '@api/problem';
import { useCart } from '@app/cart/useCart';
import { correlation } from '@app/correlation';
import { safeNext } from '@app/navigation/safeNext';
import { signInAndMerge } from '@app/session/signIn';
import { useSession, useSessionStore } from '@app/session/useSession';
import { Email } from '@domain/email';
import { Password } from '@domain/password';

import styles from './pages.module.css';
import buttons from '../components/buttons.module.css';
import { describeError, ErrorState } from '../components/ErrorState.tsx';
import forms from '../components/forms.module.css';
import { Throttled } from '../components/Throttled.tsx';

type Outcome =
  | { readonly kind: 'idle' }
  | { readonly kind: 'invalidCredentials' }
  | { readonly kind: 'unverified' }
  | { readonly kind: 'throttled'; readonly retryAfterSeconds: number }
  | { readonly kind: 'failed'; readonly error: unknown };

export const INVALID_CREDENTIALS_MESSAGE = 'The email or password is incorrect.';
export const UNVERIFIED_MESSAGE =
  'Your email is not verified yet. Open the link in the message we sent you, then sign in.';

/**
 * `/sign-in?next=` (FR-005, FR-014): the gateway sign-in in cookie mode, then the merge of the
 * anonymous cart, then the validated `next` (or `/`). A signed-in visitor is sent on at once. The
 * refusals keep their generic wording: invalid credentials, unverified email, throttled.
 */
export function SignInPage(): JSX.Element {
  const emailId = useId();
  const passwordId = useId();
  const [searchParams] = useSearchParams();
  const next = safeNext(searchParams.get('next'));
  const navigate = useNavigate();
  const { summary, resolving } = useSession();
  const sessionStore = useSessionStore();
  const { actions: cartActions } = useCart();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [clientError, setClientError] = useState<string | undefined>(undefined);
  const [outcome, setOutcome] = useState<Outcome>({ kind: 'idle' });
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!resolving && !busy && summary.state === 'signedIn') void navigate(next, { replace: true });
  }, [resolving, busy, summary.state, next, navigate]);

  const submit = async (event: SubmitEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    const parsedEmail = Email.parse(email);
    const parsedPassword = Password.forSignIn(password);
    if (!parsedEmail.ok || !parsedPassword.ok) {
      setClientError('Enter your email address and your password.');
      return;
    }
    setClientError(undefined);
    correlation.next();
    setBusy(true);
    setOutcome({ kind: 'idle' });
    try {
      await signInAndMerge(
        { sessionStore, cart: cartActions },
        parsedEmail.value,
        parsedPassword.value,
      );
      await navigate(next, { replace: true });
    } catch (error: unknown) {
      if (error instanceof UnauthorizedError) setOutcome({ kind: 'invalidCredentials' });
      else if (error instanceof ThrottledError) {
        setOutcome({ kind: 'throttled', retryAfterSeconds: error.retryAfterSeconds });
      } else if (error instanceof ProblemError && error.problem.status === 403) {
        setOutcome({ kind: 'unverified' });
      } else setOutcome({ kind: 'failed', error });
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        Sign in
      </h1>
      {outcome.kind === 'invalidCredentials' ? (
        <p className={forms.error} role="alert">
          {INVALID_CREDENTIALS_MESSAGE}
        </p>
      ) : null}
      {outcome.kind === 'unverified' ? (
        <p className={forms.status} role="alert">
          {UNVERIFIED_MESSAGE}
        </p>
      ) : null}
      {outcome.kind === 'throttled' ? (
        <Throttled
          retryAfterSeconds={outcome.retryAfterSeconds}
          onRetry={() => {
            setOutcome({ kind: 'idle' });
          }}
        />
      ) : null}
      {outcome.kind === 'failed' ? (
        <ErrorState title="Sign-in did not go through" {...describeError(outcome.error)} />
      ) : null}
      {clientError === undefined ? null : (
        <p className={forms.error} role="alert">
          {clientError}
        </p>
      )}
      <form className={forms.form} onSubmit={(event) => void submit(event)} aria-label="Sign in">
        <div className={forms.field}>
          <label className={forms.label} htmlFor={emailId}>
            Email
          </label>
          <input
            id={emailId}
            className={forms.input}
            type="email"
            autoComplete="username"
            value={email}
            disabled={busy}
            onChange={(event) => {
              setEmail(event.target.value);
            }}
          />
        </div>
        <div className={forms.field}>
          <label className={forms.label} htmlFor={passwordId}>
            Password
          </label>
          <input
            id={passwordId}
            className={forms.input}
            type="password"
            autoComplete="current-password"
            value={password}
            disabled={busy}
            onChange={(event) => {
              setPassword(event.target.value);
            }}
          />
        </div>
        <div className={forms.row}>
          <button
            className={buttons.button}
            type="submit"
            disabled={busy || outcome.kind === 'throttled'}
          >
            Sign in
          </button>
          <Link className={buttons.link} to="/register">
            Create an account
          </Link>
        </div>
      </form>
    </section>
  );
}
