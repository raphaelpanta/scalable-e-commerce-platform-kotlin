import type { NotificationChannel, NotificationPreferences } from './identityPort.ts';

// The notification preferences form as pure rules (FR-010): the two channels a shopper can switch,
// the order they are sent in, `sms` offered only once a phone number is verified, and at least one
// channel kept (identity answers 422 for an empty list). Identity still decides every save.
export type ChannelChoice = { readonly email: boolean; readonly sms: boolean };

export function choiceOf(preferences: NotificationPreferences): ChannelChoice {
  return {
    email: preferences.channels.includes('email'),
    sms: preferences.channels.includes('sms'),
  };
}

/** The channels to send, in the fixed order email then sms. */
export function channelsOf(choice: ChannelChoice): readonly NotificationChannel[] {
  return [...(choice.email ? (['email'] as const) : []), ...(choice.sms ? (['sms'] as const) : [])];
}

/** At least one channel must stay on. */
export function canSave(choice: ChannelChoice): boolean {
  return choice.email || choice.sms;
}

/** `sms` can be switched on only with a verified phone number. */
export function smsAvailable(preferences: NotificationPreferences): boolean {
  return preferences.phoneVerified;
}

/** True when the choice differs from what the platform has stored. */
export function changed(preferences: NotificationPreferences, choice: ChannelChoice): boolean {
  const stored = choiceOf(preferences);
  return stored.email !== choice.email || stored.sms !== choice.sms;
}
