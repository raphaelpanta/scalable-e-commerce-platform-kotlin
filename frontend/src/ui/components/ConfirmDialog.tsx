import { type JSX, type ReactNode, useEffect, useId, useRef } from 'react';

import { cx } from '../cx.ts';
import buttons from './buttons.module.css';
import styles from './ConfirmDialog.module.css';

export type ConfirmDialogProps = {
  readonly open: boolean;
  readonly title: string;
  readonly description?: string;
  readonly confirmLabel?: string;
  readonly cancelLabel?: string;
  readonly destructive?: boolean;
  readonly busy?: boolean;
  /** Extra content between the description and the actions (a field the action needs). */
  readonly children?: ReactNode;
  readonly onConfirm: () => void;
  readonly onCancel: () => void;
};

/**
 * Confirmation before an irreversible action (FR-010, FR-011). Focus moves to the dialog on open
 * and back to the opening control on close, Escape cancels; both controls are disabled while the
 * action is pending.
 */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  destructive = false,
  busy = false,
  children,
  onConfirm,
  onCancel,
}: ConfirmDialogProps): JSX.Element | null {
  const titleId = useId();
  const descriptionId = useId();
  const cancelRef = useRef<HTMLButtonElement>(null);

  // The latest `onCancel` without re-running the effect on every render of the parent: the focus
  // moves into the dialog once when it opens and returns to the control that opened it on close.
  const onCancelRef = useRef(onCancel);
  useEffect(() => {
    onCancelRef.current = onCancel;
  });

  useEffect(() => {
    if (!open) return undefined;
    const opener = document.activeElement;
    cancelRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') onCancelRef.current();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
    };
  }, [open]);

  if (!open) return null;

  return (
    <div className={styles.backdrop}>
      <div
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description === undefined ? undefined : descriptionId}
      >
        <h2 id={titleId} className={styles.title}>
          {title}
        </h2>
        {description === undefined ? null : <p id={descriptionId}>{description}</p>}
        {children}
        <div className={styles.actions}>
          <button
            ref={cancelRef}
            className={cx(buttons.button, buttons.secondary)}
            type="button"
            onClick={onCancel}
            disabled={busy}
          >
            {cancelLabel}
          </button>
          <button
            className={cx(buttons.button, destructive && buttons.danger)}
            type="button"
            onClick={onConfirm}
            disabled={busy}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
