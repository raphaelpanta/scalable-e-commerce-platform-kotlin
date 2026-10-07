import { type JSX, type SubmitEvent, useId, useState } from 'react';
import { Link } from 'react-router';

import { type FieldError, ProblemError, ThrottledError } from '@api/problem';
import { correlation } from '@app/correlation';
import { usePorts } from '@app/ports';
import { Email } from '@domain/email';
import { Password, PASSWORD_MIN_LENGTH } from '@domain/password';

import styles from './pages.module.css';
import buttons from '../components/buttons.module.css';
import { describeError, ErrorState } from '../components/ErrorState.tsx';
import forms from '../components/forms.module.css';
import { Throttled } from '../components/Throttled.tsx';

type Outcome =
  | { readonly kind: 'idle' }
  | { readonly kind: 'accepted'; readonly message: string }
  | { readonly kind: 'throttled'; readonly retryAfterSeconds: number }
  | { readonly kind: 'failed'; readonly error: unknown };

/**
 * `/register` (FR-005): email and password (the storefront pre-checks only the minimum length;
 * the platform's `errors[]` are shown next to the fields), one generic acknowledgement whether or
 * not the address was already registered, a countdown when throttled.
 */
export function RegisterPage(): JSX.Element {
  const emailId = useId();
  const passwordId = useId();
  const { identity } = usePorts();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fieldErrors, setFieldErrors] = useState<readonly FieldError[]>([]);
  const [outcome, setOutcome] = useState<Outcome>({ kind: 'idle' });
  const [busy, setBusy] = useState(false);

  const errorFor = (field: string): string | undefined =>
    fieldErrors.find((error) => error.field === field)?.message;

  const submit = async (event: SubmitEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    const parsedEmail = Email.parse(email);
    const parsedPassword = Password.create(password);
    const clientErrors: FieldError[] = [];
    if (!parsedEmail.ok)
      clientErrors.push({ field: 'email', message: 'Enter a valid email address.' });
    if (!parsedPassword.ok) {
      clientErrors.push({
        field: 'password',
        message: `Use at least ${PASSWORD_MIN_LENGTH} characters.`,
      });
    }
    setFieldErrors(clientErrors);
    if (!parsedEmail.ok || !parsedPassword.ok) return;
    correlation.next();
    setBusy(true);
    setOutcome({ kind: 'idle' });
    try {
      const message = await identity.register(parsedEmail.value, parsedPassword.value);
      setOutcome({ kind: 'accepted', message });
      setPassword('');
    } catch (error: unknown) {
      if (error instanceof ThrottledError) {
        setOutcome({ kind: 'throttled', retryAfterSeconds: error.retryAfterSeconds });
      } else if (error instanceof ProblemError && error.problem.errors.length > 0) {
        setFieldErrors(error.problem.errors);
      } else {
        setOutcome({ kind: 'failed', error });
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        Create an account
      </h1>
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
        <ErrorState title="Registration did not go through" {...describeError(outcome.error)} />
      ) : null}
      <form className={forms.form} onSubmit={(event) => void submit(event)} aria-label="Register">
        <div className={forms.field}>
          <label className={forms.label} htmlFor={emailId}>
            Email
          </label>
          <input
            id={emailId}
            className={forms.input}
            type="email"
            autoComplete="email"
            value={email}
            disabled={busy}
            aria-invalid={errorFor('email') === undefined ? undefined : true}
            aria-describedby={errorFor('email') === undefined ? undefined : `${emailId}-error`}
            onChange={(event) => {
              setEmail(event.target.value);
            }}
          />
          {errorFor('email') === undefined ? null : (
            <p id={`${emailId}-error`} className={forms.error} role="alert">
              {errorFor('email')}
            </p>
          )}
        </div>
        <div className={forms.field}>
          <label className={forms.label} htmlFor={passwordId}>
            Password
          </label>
          <p className={forms.hint} id={`${passwordId}-hint`}>
            At least {PASSWORD_MIN_LENGTH} characters.
          </p>
          <input
            id={passwordId}
            className={forms.input}
            type="password"
            autoComplete="new-password"
            value={password}
            disabled={busy}
            aria-invalid={errorFor('password') === undefined ? undefined : true}
            aria-describedby={
              errorFor('password') === undefined
                ? `${passwordId}-hint`
                : `${passwordId}-hint ${passwordId}-error`
            }
            onChange={(event) => {
              setPassword(event.target.value);
            }}
          />
          {errorFor('password') === undefined ? null : (
            <p id={`${passwordId}-error`} className={forms.error} role="alert">
              {errorFor('password')}
            </p>
          )}
        </div>
        <div className={forms.row}>
          <button
            className={buttons.button}
            type="submit"
            disabled={busy || outcome.kind === 'throttled'}
          >
            Create account
          </button>
          <Link className={buttons.link} to="/sign-in">
            I already have an account
          </Link>
        </div>
      </form>
    </section>
  );
}
