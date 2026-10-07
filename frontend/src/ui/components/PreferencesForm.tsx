import { type JSX, type SubmitEvent, useId, useState } from 'react';

import type { NotificationChannel, NotificationPreferences } from '@app/identity/identityPort';
import { canSave, changed, channelsOf, choiceOf, smsAvailable } from '@app/identity/preferences';

import buttons from './buttons.module.css';
import forms from './forms.module.css';
import orders from './orders.module.css';

export type PreferencesFormProps = {
  /** The preferences as the platform holds them; the form starts from them. */
  readonly preferences: NotificationPreferences;
  readonly busy?: boolean;
  readonly onSave: (channels: readonly NotificationChannel[]) => void;
};

/**
 * The notification channels (FR-010): email, and sms only once a phone number is verified (the
 * switch is disabled with its reason otherwise). At least one channel stays on. The page remounts
 * the form (a new `key`) when the platform's preferences change, so it always starts from them.
 */
export function PreferencesForm({
  preferences,
  busy = false,
  onSave,
}: PreferencesFormProps): JSX.Element {
  const prefix = useId();
  const [choice, setChoice] = useState(() => choiceOf(preferences));
  const smsEnabled = smsAvailable(preferences);
  const savable = canSave(choice);

  const submit = (event: SubmitEvent<HTMLFormElement>): void => {
    event.preventDefault();
    if (savable) onSave(channelsOf(choice));
  };

  return (
    <form className={forms.form} onSubmit={submit} aria-label="Notification channels">
      <fieldset className={orders.channels}>
        <legend className={forms.legend}>Send me notifications by</legend>
        <div className={forms.row}>
          <input
            id={`${prefix}-email`}
            type="checkbox"
            checked={choice.email}
            disabled={busy}
            onChange={(event) => {
              setChoice({ ...choice, email: event.target.checked });
            }}
          />
          <label htmlFor={`${prefix}-email`}>Email</label>
        </div>
        <div className={forms.row}>
          <input
            id={`${prefix}-sms`}
            type="checkbox"
            checked={choice.sms}
            disabled={busy || !smsEnabled}
            aria-describedby={`${prefix}-sms-hint`}
            onChange={(event) => {
              setChoice({ ...choice, sms: event.target.checked });
            }}
          />
          <label htmlFor={`${prefix}-sms`}>SMS</label>
          <p id={`${prefix}-sms-hint`} className={orders.hint}>
            {smsEnabled
              ? `Messages go to ${preferences.phoneNumber ?? 'your verified number'}.`
              : 'Verify a phone number below to turn this on.'}
          </p>
        </div>
      </fieldset>
      {savable ? null : (
        <p className={forms.error} role="alert">
          Keep at least one channel on.
        </p>
      )}
      <div className={forms.row}>
        <button
          className={buttons.button}
          type="submit"
          disabled={busy || !savable || !changed(preferences, choice)}
        >
          Save preferences
        </button>
      </div>
    </form>
  );
}
