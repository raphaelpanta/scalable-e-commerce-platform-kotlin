import { type JSX, type SubmitEvent, useState } from 'react';
import { Link } from 'react-router';

import { ProblemError, ThrottledError } from '@api/problem';
import { clearDraft } from '@app/checkout/draftStorage';
import { correlation } from '@app/correlation';
import { deleteAccount } from '@app/identity/deleteAccount';
import type { Account } from '@app/identity/identityPort';
import { useOwnProfile, useUpdateProfile } from '@app/identity/useProfile';
import { usePorts } from '@app/ports';
import { useSessionStore } from '@app/session/useSession';
import { Password } from '@domain/password';

import styles from './pages.module.css';
import { ActionError } from '../components/ActionError.tsx';
import buttons from '../components/buttons.module.css';
import { ConfirmDialog } from '../components/ConfirmDialog.tsx';
import { describeError } from '../components/ErrorState.tsx';
import forms from '../components/forms.module.css';
import orders from '../components/orders.module.css';
import { QueryBoundary } from '../components/QueryBoundary.tsx';
import { TextField } from '../components/TextField.tsx';
import { cx } from '../cx.ts';

function ProfileForm({ account }: { readonly account: Account }): JSX.Element {
  const update = useUpdateProfile();
  const [name, setName] = useState(account.displayName ?? '');
  const [saved, setSaved] = useState(false);
  const [failure, setFailure] = useState<{ readonly error: unknown } | undefined>(undefined);
  const fieldError =
    failure?.error instanceof ProblemError
      ? failure.error.problem.errors.find((error) => error.field === 'displayName')?.message
      : undefined;

  const submit = async (event: SubmitEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    correlation.next();
    setSaved(false);
    setFailure(undefined);
    try {
      await update.mutateAsync(name.trim());
      setSaved(true);
    } catch (error: unknown) {
      setFailure({ error });
    }
  };

  return (
    <div className={orders.section}>
      <h2 className={orders.heading}>Profile</h2>
      <p>
        Email <strong>{account.email}</strong>
      </p>
      {failure !== undefined && fieldError === undefined ? (
        <ActionError
          error={failure.error}
          title="Your name was not saved"
          onDismiss={() => {
            setFailure(undefined);
          }}
        />
      ) : null}
      {saved ? (
        <p className={forms.status} role="status">
          Your name was saved.
        </p>
      ) : null}
      <form className={forms.form} onSubmit={(event) => void submit(event)} aria-label="Profile">
        <TextField
          label="Display name"
          value={name}
          autoComplete="name"
          disabled={update.isPending}
          error={fieldError}
          onChange={(value) => {
            setName(value);
            setSaved(false);
          }}
        />
        <div className={forms.row}>
          <button className={buttons.button} type="submit" disabled={update.isPending}>
            Save name
          </button>
        </div>
      </form>
    </div>
  );
}

type DeletionState = {
  readonly password: string;
  readonly error: string | undefined;
  readonly failure: unknown;
};

const NO_DELETION_ERROR: DeletionState = { password: '', error: undefined, failure: undefined };

function DeleteAccount({
  account,
  onDeleted,
}: {
  readonly account: Account;
  readonly onDeleted: () => void;
}): JSX.Element {
  const { identity } = usePorts();
  const sessionStore = useSessionStore();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [state, setState] = useState<DeletionState>(NO_DELETION_ERROR);

  const close = (): void => {
    setOpen(false);
    setState(NO_DELETION_ERROR);
  };

  const confirm = async (): Promise<void> => {
    const password = Password.forSignIn(state.password);
    if (!password.ok) {
      setState({ ...state, error: 'Enter your password.', failure: undefined });
      return;
    }
    correlation.next();
    setBusy(true);
    setState({ ...state, error: undefined, failure: undefined });
    try {
      const outcome = await deleteAccount(
        { identity, sessionStore },
        account.email,
        password.value,
      );
      if (outcome === 'wrongPassword') {
        setState({ ...state, error: 'The password is incorrect.', failure: undefined });
        return;
      }
      clearDraft(window.sessionStorage);
      onDeleted();
    } catch (error: unknown) {
      setState({ ...state, error: undefined, failure: error });
    } finally {
      setBusy(false);
    }
  };

  const failureText =
    state.failure instanceof ThrottledError
      ? `Too many attempts. Try again in ${String(state.failure.retryAfterSeconds)} seconds.`
      : state.failure === undefined
        ? undefined
        : (describeError(state.failure).message ?? 'The account was not deleted.');

  return (
    <div className={orders.section}>
      <h2 className={orders.heading}>Delete your account</h2>
      <p>
        Your personal details are anonymised and you are signed out. Orders you already placed stay
        with the store, without your personal details.
      </p>
      <p className={styles.actions}>
        <button
          className={cx(buttons.button, buttons.danger)}
          type="button"
          onClick={() => {
            setOpen(true);
          }}
        >
          Delete my account
        </button>
      </p>
      <ConfirmDialog
        open={open}
        title="Delete your account?"
        description="This cannot be undone. Enter your password to confirm."
        confirmLabel="Delete my account"
        cancelLabel="Keep my account"
        destructive
        busy={busy}
        onConfirm={() => {
          void confirm();
        }}
        onCancel={close}
      >
        <TextField
          label="Password"
          type="password"
          autoComplete="current-password"
          value={state.password}
          disabled={busy}
          error={state.error}
          onChange={(value) => {
            setState({ ...state, password: value, error: undefined });
          }}
          onEnter={() => {
            void confirm();
          }}
        />
        {failureText === undefined ? null : <p role="alert">{failureText}</p>}
      </ConfirmDialog>
    </div>
  );
}

function AccountDetails({ onDeleted }: { readonly onDeleted: () => void }): JSX.Element {
  const profile = useOwnProfile();
  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        Your account
      </h1>
      <QueryBoundary query={profile} loadingLabel="Loading your account…">
        {(account) => (
          <>
            <ProfileForm account={account} />
            <p className={styles.actions}>
              <Link className={buttons.button} to="/account/addresses">
                Your addresses
              </Link>
              <Link className={buttons.button} to="/account/notifications">
                Notification preferences
              </Link>
            </p>
            <DeleteAccount account={account} onDeleted={onDeleted} />
          </>
        )}
      </QueryBoundary>
    </section>
  );
}

/**
 * `/account` (FR-005, FR-010): the profile (email, display name with the platform's field errors
 * next to it), the way to the addresses and the notification preferences, and the account
 * deletion: it asks for the password (proved by signing in with it), anonymises the account, ends
 * the session and shows the farewell state.
 */
export function AccountPage(): JSX.Element {
  const [deleted, setDeleted] = useState(false);
  if (deleted) {
    return (
      <section className={styles.page} aria-labelledby="page-title">
        <h1 id="page-title" className={styles.title}>
          Your account was deleted
        </h1>
        <p role="status">
          You are signed out. Orders you placed stay with the store, without your personal details.
        </p>
        <p className={styles.actions}>
          <Link className={buttons.button} to="/">
            Back to the store
          </Link>
        </p>
      </section>
    );
  }
  return (
    <AccountDetails
      onDeleted={() => {
        setDeleted(true);
      }}
    />
  );
}
