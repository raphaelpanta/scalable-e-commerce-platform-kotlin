import { type JSX, useEffect, useId, useRef } from 'react';

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
  readonly onConfirm: () => void;
  readonly onCancel: () => void;
};

/**
 * Confirmation before an irreversible action (FR-010, FR-011). Focus moves to the dialog on open,
 * Escape cancels; both controls are disabled while the action is pending.
 */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  destructive = false,
  busy = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps): JSX.Element | null {
  const titleId = useId();
  const descriptionId = useId();
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return undefined;
    cancelRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') onCancel();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open, onCancel]);

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
