import { type JSX, type ReactNode, useState } from 'react';
import { useSearchParams } from 'react-router';

import { type FieldError, ProblemError } from '@api/problem';
import { listingFromSearch, pageCount, withPage } from '@app/catalog/browseParams';
import { correlation } from '@app/correlation';
import type { Address } from '@app/identity/identityPort';
import { useAddressMutations, useOwnAddresses } from '@app/identity/useAddresses';

import styles from './pages.module.css';
import { ActionError } from '../components/ActionError.tsx';
import { AddressForm } from '../components/AddressForm.tsx';
import buttons from '../components/buttons.module.css';
import { ConfirmDialog } from '../components/ConfirmDialog.tsx';
import { Empty } from '../components/Empty.tsx';
import forms from '../components/forms.module.css';
import orders from '../components/orders.module.css';
import { Pager } from '../components/Pager.tsx';
import { QueryBoundary } from '../components/QueryBoundary.tsx';
import { cx } from '../cx.ts';
import { savedAddressText } from '../format.ts';

type Mode =
  | { readonly kind: 'idle' }
  | { readonly kind: 'add' }
  | { readonly kind: 'edit'; readonly address: Address };

type Failure = { readonly error: unknown };

function fieldErrorsOf(failure: Failure | undefined): readonly FieldError[] {
  return failure?.error instanceof ProblemError ? failure.error.problem.errors : [];
}

function nameOf(address: Address): string {
  return address.label ?? address.recipientName;
}

/**
 * `/account/addresses` (FR-010): the saved addresses with add, edit and remove (removal after a
 * confirmation); the platform's per-field refusals sit next to the fields, anything else is an
 * error with a retry; `?page=` appears in the address when there is more than one page.
 */
export function AddressesPage(): JSX.Element {
  const [searchParams] = useSearchParams();
  const listing = listingFromSearch(searchParams);
  const query = useOwnAddresses(listing);
  const { add, update, remove } = useAddressMutations();
  const [mode, setMode] = useState<Mode>({ kind: 'idle' });
  const [deleting, setDeleting] = useState<Address | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [failure, setFailure] = useState<Failure | undefined>(undefined);

  const busy = add.isPending || update.isPending || remove.isPending;
  const saving = mode.kind === 'add' || mode.kind === 'edit';
  const fieldErrors = fieldErrorsOf(failure);

  const run = async (
    work: () => Promise<unknown>,
    done: string,
    after?: () => void,
  ): Promise<void> => {
    correlation.next();
    setNotice(undefined);
    setFailure(undefined);
    try {
      await work();
      setNotice(done);
      after?.();
    } catch (error: unknown) {
      setFailure({ error });
    }
  };

  const form = (): ReactNode => {
    if (mode.kind === 'idle') return null;
    const close = (): void => {
      setMode({ kind: 'idle' });
      setFailure(undefined);
    };
    return mode.kind === 'add' ? (
      <AddressForm
        busy={busy}
        serverErrors={fieldErrors}
        onCancel={close}
        onSubmit={(address) => {
          void run(() => add.mutateAsync(address), 'Address saved.', close);
        }}
      />
    ) : (
      <AddressForm
        key={mode.address.id}
        label="Edit delivery address"
        initial={mode.address}
        busy={busy}
        serverErrors={fieldErrors}
        onCancel={close}
        onSubmit={(address) => {
          void run(
            () => update.mutateAsync({ id: mode.address.id, address }),
            'Address saved.',
            close,
          );
        }}
      />
    );
  };

  const addButton = (
    <button
      className={buttons.button}
      type="button"
      disabled={saving}
      onClick={() => {
        setNotice(undefined);
        setFailure(undefined);
        setMode({ kind: 'add' });
      }}
    >
      Add an address
    </button>
  );

  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        Your addresses
      </h1>
      {notice === undefined ? null : (
        <p className={forms.status} role="status">
          {notice}
        </p>
      )}
      {failure !== undefined && fieldErrors.length === 0 ? (
        <ActionError
          error={failure.error}
          title="The change was not saved"
          onDismiss={() => {
            setFailure(undefined);
          }}
        />
      ) : null}
      {form()}
      <QueryBoundary query={query} loadingLabel="Loading your addresses…" loading="lines">
        {(page): ReactNode => {
          if (page.items.length === 0) {
            if (page.page > 0) {
              const first = withPage(searchParams, 0).toString();
              return (
                <Empty
                  title="No more addresses"
                  message="This page is past the end of the list."
                  action={{ label: 'Back to the first page', to: first === '' ? '.' : `?${first}` }}
                />
              );
            }
            return mode.kind === 'add' ? null : (
              <Empty
                title="No saved addresses"
                message="Save an address to use it at checkout."
                action={{
                  label: 'Add an address',
                  onClick: () => {
                    setNotice(undefined);
                    setMode({ kind: 'add' });
                  },
                }}
              />
            );
          }
          return (
            <>
              <ul className={orders.list} aria-label="Saved addresses">
                {page.items.map((address) => (
                  <li key={address.id} className={orders.row}>
                    <div>
                      <p className={orders.rowTitle}>
                        {nameOf(address)}
                        {address.isDefault ? (
                          <>
                            {' '}
                            <span className={orders.badge}>Default</span>
                          </>
                        ) : null}
                      </p>
                      <p className={orders.addressText}>{savedAddressText(address)}</p>
                    </div>
                    <div className={styles.actions}>
                      <button
                        className={cx(buttons.button, buttons.secondary)}
                        type="button"
                        aria-label={`Edit address ${nameOf(address)}`}
                        disabled={busy}
                        onClick={() => {
                          setNotice(undefined);
                          setFailure(undefined);
                          setMode({ kind: 'edit', address });
                        }}
                      >
                        Edit
                      </button>
                      <button
                        className={cx(buttons.button, buttons.secondary)}
                        type="button"
                        aria-label={`Delete address ${nameOf(address)}`}
                        disabled={busy}
                        onClick={() => {
                          setDeleting(address);
                        }}
                      >
                        Delete
                      </button>
                    </div>
                  </li>
                ))}
              </ul>
              {pageCount(page.totalItems, page.size) > 1 ? (
                <Pager
                  page={page.page}
                  size={page.size}
                  totalItems={page.totalItems}
                  shown={page.items.length}
                  noun={{ singular: 'address', plural: 'addresses' }}
                />
              ) : null}
              {saving ? null : <p className={styles.actions}>{addButton}</p>}
            </>
          );
        }}
      </QueryBoundary>
      <ConfirmDialog
        open={deleting !== undefined}
        title="Delete this address?"
        description="Orders you placed keep their own copy of the address."
        confirmLabel="Delete address"
        cancelLabel="Keep the address"
        destructive
        busy={remove.isPending}
        onConfirm={() => {
          const target = deleting;
          if (target === undefined) return;
          void run(() => remove.mutateAsync(target.id), 'Address removed.').finally(() => {
            setDeleting(undefined);
          });
        }}
        onCancel={() => {
          setDeleting(undefined);
        }}
      />
    </section>
  );
}
