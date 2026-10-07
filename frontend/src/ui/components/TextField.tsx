import { type HTMLInputAutoCompleteAttribute, type JSX, useId } from 'react';

import forms from './forms.module.css';

export type TextFieldProps = {
  readonly label: string;
  readonly value: string;
  readonly onChange: (value: string) => void;
  readonly type?: 'text' | 'email' | 'password' | 'tel';
  readonly autoComplete?: HTMLInputAutoCompleteAttribute;
  readonly inputMode?: 'text' | 'tel' | 'numeric';
  readonly hint?: string;
  /** The refusal to show next to the field, from the browser-side check or the platform's `errors[]`. */
  readonly error?: string | undefined;
  readonly disabled?: boolean;
  readonly onEnter?: () => void;
};

/**
 * A labelled input with its optional hint and its error next to it: the error is announced
 * (`role="alert"`), the field is marked invalid and described by hint and error (FR-016, FR-017).
 * Text is rendered as text, never as markup.
 */
export function TextField({
  label,
  value,
  onChange,
  type = 'text',
  autoComplete,
  inputMode,
  hint,
  error,
  disabled = false,
  onEnter,
}: TextFieldProps): JSX.Element {
  const id = useId();
  const described = [
    hint === undefined ? undefined : `${id}-hint`,
    error === undefined ? undefined : `${id}-error`,
  ]
    .filter((part) => part !== undefined)
    .join(' ');
  return (
    <div className={forms.field}>
      <label className={forms.label} htmlFor={id}>
        {label}
      </label>
      {hint === undefined ? null : (
        <p className={forms.hint} id={`${id}-hint`}>
          {hint}
        </p>
      )}
      <input
        id={id}
        className={forms.input}
        type={type}
        value={value}
        disabled={disabled}
        aria-invalid={error === undefined ? undefined : true}
        aria-describedby={described === '' ? undefined : described}
        {...(autoComplete === undefined ? {} : { autoComplete })}
        {...(inputMode === undefined ? {} : { inputMode })}
        onChange={(event) => {
          onChange(event.target.value);
        }}
        onKeyDown={(event) => {
          if (onEnter !== undefined && event.key === 'Enter') {
            event.preventDefault();
            onEnter();
          }
        }}
      />
      {error === undefined ? null : (
        <p id={`${id}-error`} className={forms.error} role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
