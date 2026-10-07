import { type JSX, type SubmitEvent, useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router';

import { type FieldError, ProblemError, ThrottledError } from '@api/problem';
import { correlation } from '@app/correlation';
import { usePorts } from '@app/ports';
import { Password, PASSWORD_MIN_LENGTH } from '@domain/password';

import styles from './pages.module.css';
import buttons from '../components/buttons.module.css';
import { describeError, ErrorState } from '../components/ErrorState.tsx';
import forms from '../components/forms.module.css';
import { TextField } from '../components/TextField.tsx';
import { Throttled } from '../components/Throttled.tsx';

type Stage = 'form' | 'done' | 'invalid';
type Failure =
  | { readonly kind: 'throttled'; readonly retryAfterSeconds: number }
  | { readonly kind: 'failed'; readonly error: unknown };

const NEW_PASSWORD_FIELD = 'newPassword';

/**
 * `/reset-password?token=` (FR-005): the token is read once on load and removed from the address
 * with a history replace; it is kept in memory only, so the shopper can try another password. A
 * missing, invalid, used or expired token shows one generic message; the platform's field errors
 * for the password sit next to the field.
 */
export function ResetPasswordPage(): JSX.Element {
  const [searchParams] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const { identity } = usePorts();
  const read = useRef(false);
  const [token] = useState(() => searchParams.get('token') ?? '');
  const [stage, setStage] = useState<Stage>(token === '' ? 'invalid' : 'form');
  const [password, setPassword] = useState('');
  const [fieldErrors, setFieldErrors] = useState<readonly FieldError[]>([]);
  const [failure, setFailure] = useState<Failure | undefined>(undefined);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (read.current) return;
    read.current = true;
    if (searchParams.has('token')) void navigate(location.pathname, { replace: true });
  }, [location.pathname, navigate, searchParams]);

  const passwordError = fieldErrors.find((error) => error.field === NEW_PASSWORD_FIELD)?.message;

  const submit = async (event: SubmitEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    const parsed = Password.create(password);
    if (!parsed.ok) {
      setFieldErrors([
        {
          field: NEW_PASSWORD_FIELD,
          message: `Use at least ${String(PASSWORD_MIN_LENGTH)} characters.`,
        },
      ]);
      return;
    }
    setFieldErrors([]);
    setFailure(undefined);
    correlation.next();
    setBusy(true);
    try {
      await identity.completePasswordReset(token, parsed.value);
      setPassword('');
      setStage('done');
    } catch (error: unknown) {
      if (error instanceof ThrottledError) {
        setFailure({ kind: 'throttled', retryAfterSeconds: error.retryAfterSeconds });
      } else if (
        error instanceof ProblemError &&
        error.problem.errors.some((fieldError) => fieldError.field === NEW_PASSWORD_FIELD)
      ) {
        setFieldErrors(error.problem.errors);
      } else if (error instanceof ProblemError && [400, 422].includes(error.problem.status)) {
        setStage('invalid');
      } else {
        setFailure({ kind: 'failed', error });
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        Choose a new password
      </h1>
      {stage === 'done' ? (
        <>
          <p className={forms.status} role="status">
            Your password was changed. Sign in with the new password.
          </p>
          <p className={styles.actions}>
            <Link className={buttons.button} to="/sign-in">
              Sign in
            </Link>
          </p>
        </>
      ) : null}
      {stage === 'invalid' ? (
        <>
          <p role="alert">This reset link is invalid or has expired.</p>
          <p className={styles.actions}>
            <Link className={buttons.button} to="/forgot-password">
              Ask for a new link
            </Link>
            <Link className={buttons.link} to="/sign-in">
              Sign in
            </Link>
          </p>
        </>
      ) : null}
      {stage === 'form' ? (
        <>
          {failure?.kind === 'throttled' ? (
            <Throttled
              retryAfterSeconds={failure.retryAfterSeconds}
              onRetry={() => {
                setFailure(undefined);
              }}
            />
          ) : null}
          {failure?.kind === 'failed' ? (
            <ErrorState title="The password was not changed" {...describeError(failure.error)} />
          ) : null}
          <form
            className={forms.form}
            onSubmit={(event) => void submit(event)}
            aria-label="Choose a new password"
          >
            <TextField
              label="New password"
              type="password"
              autoComplete="new-password"
              hint={`At least ${String(PASSWORD_MIN_LENGTH)} characters.`}
              value={password}
              disabled={busy}
              error={passwordError}
              onChange={setPassword}
            />
            <div className={forms.row}>
              <button
                className={buttons.button}
                type="submit"
                disabled={busy || failure?.kind === 'throttled'}
              >
                Set new password
              </button>
            </div>
          </form>
        </>
      ) : null}
    </section>
  );
}
