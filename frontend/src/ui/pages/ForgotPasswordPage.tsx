import { type JSX, type SubmitEvent, useState } from 'react';
import { Link } from 'react-router';

import { ThrottledError } from '@api/problem';
import { correlation } from '@app/correlation';
import { usePorts } from '@app/ports';
import { Email } from '@domain/email';

import styles from './pages.module.css';
import buttons from '../components/buttons.module.css';
import { describeError, ErrorState } from '../components/ErrorState.tsx';
import forms from '../components/forms.module.css';
import { TextField } from '../components/TextField.tsx';
import { Throttled } from '../components/Throttled.tsx';

type Outcome =
  | { readonly kind: 'idle' }
  | { readonly kind: 'accepted'; readonly message: string }
  | { readonly kind: 'throttled'; readonly retryAfterSeconds: number }
  | { readonly kind: 'failed'; readonly error: unknown };

/**
 * `/forgot-password` (FR-005): asks for a reset link and shows the platform's one generic
 * acknowledgement, which is the same whether or not the address is registered; a countdown when
 * throttled.
 */
export function ForgotPasswordPage(): JSX.Element {
  const { identity } = usePorts();
  const [email, setEmail] = useState('');
  const [emailError, setEmailError] = useState<string | undefined>(undefined);
  const [outcome, setOutcome] = useState<Outcome>({ kind: 'idle' });
  const [busy, setBusy] = useState(false);

  const submit = async (event: SubmitEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    const parsed = Email.parse(email);
    if (!parsed.ok) {
      setEmailError('Enter a valid email address.');
      return;
    }
    setEmailError(undefined);
    correlation.next();
    setBusy(true);
    setOutcome({ kind: 'idle' });
    try {
      setOutcome({ kind: 'accepted', message: await identity.requestPasswordReset(parsed.value) });
    } catch (error: unknown) {
      setOutcome(
        error instanceof ThrottledError
          ? { kind: 'throttled', retryAfterSeconds: error.retryAfterSeconds }
          : { kind: 'failed', error },
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        Forgot your password?
      </h1>
      <p>Enter your email address and we will send you a link to choose a new password.</p>
      {outcome.kind === 'accepted' ? (
        <p className={forms.status} role="status">
          {outcome.message}
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
        <ErrorState title="The request did not go through" {...describeError(outcome.error)} />
      ) : null}
      <form
        className={forms.form}
        onSubmit={(event) => void submit(event)}
        aria-label="Forgot password"
        noValidate
      >
        <TextField
          label="Email"
          type="email"
          autoComplete="email"
          value={email}
          disabled={busy}
          error={emailError}
          onChange={setEmail}
        />
        <div className={forms.row}>
          <button
            className={buttons.button}
            type="submit"
            disabled={busy || outcome.kind === 'throttled'}
          >
            Send reset link
          </button>
          <Link className={buttons.link} to="/sign-in">
            Back to sign in
          </Link>
        </div>
      </form>
    </section>
  );
}
