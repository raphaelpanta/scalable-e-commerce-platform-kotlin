import { type JSX, useState } from 'react';

import { ProblemError } from '@api/problem';
import { correlation } from '@app/correlation';
import type { NotificationPreferences } from '@app/identity/identityPort';
import {
  useNotificationPreferences,
  usePhoneVerification,
  useSavePreferences,
} from '@app/identity/useNotificationPreferences';
import { PhoneNumber, VerificationCode } from '@domain/phone';

import styles from './pages.module.css';
import { ActionError } from '../components/ActionError.tsx';
import buttons from '../components/buttons.module.css';
import forms from '../components/forms.module.css';
import orders from '../components/orders.module.css';
import { PreferencesForm } from '../components/PreferencesForm.tsx';
import { QueryBoundary } from '../components/QueryBoundary.tsx';
import { TextField } from '../components/TextField.tsx';

const PHONE_FORMAT_MESSAGE = 'Enter the number in international format, for example +351912345678.';
const CODE_FORMAT_MESSAGE = 'Enter the six digits of the code.';

type Failure = { readonly error: unknown };

function fieldMessage(failure: Failure | undefined, field: string): string | undefined {
  return failure?.error instanceof ProblemError
    ? failure.error.problem.errors.find((error) => error.field === field)?.message
    : undefined;
}

function PhoneVerification({
  preferences,
}: {
  readonly preferences: NotificationPreferences;
}): JSX.Element {
  const { request, confirm } = usePhoneVerification();
  const [phone, setPhone] = useState('');
  const [sentTo, setSentTo] = useState<string | undefined>(undefined);
  const [code, setCode] = useState('');
  const [clientError, setClientError] = useState<{ field: 'phone' | 'code'; message: string }>();
  const [failure, setFailure] = useState<Failure | undefined>(undefined);
  const codeRefusal = fieldMessage(failure, 'code');

  const send = async (): Promise<void> => {
    const parsed = PhoneNumber.parse(phone);
    if (!parsed.ok) {
      setClientError({ field: 'phone', message: PHONE_FORMAT_MESSAGE });
      return;
    }
    correlation.next();
    setClientError(undefined);
    setFailure(undefined);
    try {
      await request.mutateAsync(parsed.value.value);
      setSentTo(parsed.value.value);
      setCode('');
    } catch (error: unknown) {
      setFailure({ error });
    }
  };

  const verify = async (): Promise<void> => {
    const parsed = VerificationCode.parse(code);
    if (!parsed.ok) {
      setClientError({ field: 'code', message: CODE_FORMAT_MESSAGE });
      return;
    }
    correlation.next();
    setClientError(undefined);
    setFailure(undefined);
    try {
      await confirm.mutateAsync(parsed.value.value);
      setSentTo(undefined);
      setPhone('');
      setCode('');
    } catch (error: unknown) {
      setFailure({ error });
    }
  };

  return (
    <div className={orders.section}>
      <h2 className={orders.heading}>Phone number</h2>
      {preferences.phoneVerified ? (
        <p className={forms.status} role="status">
          Your phone number {preferences.phoneNumber ?? ''} is verified.
        </p>
      ) : null}
      {failure !== undefined && codeRefusal === undefined ? (
        <ActionError
          error={failure.error}
          title="The phone number was not verified"
          onDismiss={() => {
            setFailure(undefined);
          }}
        />
      ) : null}
      <form
        className={forms.form}
        aria-label="Send a verification code"
        onSubmit={(event) => {
          event.preventDefault();
          void send();
        }}
      >
        <TextField
          label="Phone number"
          type="tel"
          autoComplete="tel"
          inputMode="tel"
          hint="International format, for example +351912345678."
          value={phone}
          disabled={request.isPending}
          error={clientError?.field === 'phone' ? clientError.message : undefined}
          onChange={setPhone}
        />
        <div className={forms.row}>
          <button className={buttons.button} type="submit" disabled={request.isPending}>
            Send code
          </button>
        </div>
      </form>
      {sentTo === undefined ? null : (
        <form
          className={forms.form}
          aria-label="Confirm the phone number"
          onSubmit={(event) => {
            event.preventDefault();
            void verify();
          }}
        >
          <p role="status">We sent a code to {sentTo}.</p>
          <TextField
            label="Verification code"
            autoComplete="one-time-code"
            inputMode="numeric"
            value={code}
            disabled={confirm.isPending}
            error={clientError?.field === 'code' ? clientError.message : codeRefusal}
            onChange={setCode}
          />
          <div className={forms.row}>
            <button className={buttons.button} type="submit" disabled={confirm.isPending}>
              Confirm number
            </button>
          </div>
        </form>
      )}
    </div>
  );
}

/**
 * `/account/notifications` (FR-010): the channels (sms only after a phone number is verified with
 * the code sent to it; the platform's 422 is shown when it still refuses) and the phone
 * verification, with every state of FR-016.
 */
export function NotificationsPage(): JSX.Element {
  const query = useNotificationPreferences();
  const save = useSavePreferences();
  const [saved, setSaved] = useState(false);
  const [failure, setFailure] = useState<Failure | undefined>(undefined);

  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        Notification preferences
      </h1>
      <QueryBoundary query={query} loadingLabel="Loading your preferences…">
        {(preferences) => (
          <>
            {saved ? (
              <p className={forms.status} role="status">
                Your preferences are saved.
              </p>
            ) : null}
            {failure === undefined ? null : (
              <ActionError
                error={failure.error}
                title="Your preferences were not saved"
                onDismiss={() => {
                  setFailure(undefined);
                }}
              />
            )}
            <PreferencesForm
              key={`${preferences.channels.join(',')}-${String(preferences.phoneVerified)}`}
              preferences={preferences}
              busy={save.isPending}
              onSave={(channels) => {
                correlation.next();
                setSaved(false);
                setFailure(undefined);
                save.mutateAsync(channels).then(
                  () => {
                    setSaved(true);
                  },
                  (error: unknown) => {
                    setFailure({ error });
                  },
                );
              }}
            />
            <PhoneVerification preferences={preferences} />
          </>
        )}
      </QueryBoundary>
    </section>
  );
}
