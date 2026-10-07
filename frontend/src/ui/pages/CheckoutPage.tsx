import { useQueryClient } from '@tanstack/react-query';
import { type JSX, type ReactNode, useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';

import { type FieldError, ProblemError, ThrottledError } from '@api/problem';
import { CART_KEY } from '@app/cart/cartStore';
import { useCart } from '@app/cart/useCart';
import {
  CHECKOUT_STEPS,
  type CheckoutStep,
  type Refusal,
  requestOf,
} from '@app/checkout/checkoutDraft';
import { useCheckout } from '@app/checkout/useCheckout';
import { correlation } from '@app/correlation';
import { useOwnAddresses } from '@app/identity/useAddresses';
import { signInLocationFor } from '@app/navigation/safeNext';
import { usePorts } from '@app/ports';
import type { Address } from '@domain/address';
import { PaymentMethod } from '@domain/paymentMethods';

import styles from './pages.module.css';
import { AddressForm } from '../components/AddressForm.tsx';
import { AddressPicker } from '../components/AddressPicker.tsx';
import buttons from '../components/buttons.module.css';
import cart from '../components/cart.module.css';
import { Empty } from '../components/Empty.tsx';
import { describeError, ErrorState } from '../components/ErrorState.tsx';
import { OrderSummary } from '../components/OrderSummary.tsx';
import { PaymentMethodPicker } from '../components/PaymentMethodPicker.tsx';
import { PriceChangeNotice } from '../components/PriceChangeNotice.tsx';
import { QueryBoundary } from '../components/QueryBoundary.tsx';
import states from '../components/states.module.css';
import { Throttled } from '../components/Throttled.tsx';
import { cx } from '../cx.ts';
import { declineLabel } from '../labels.ts';

const STEP_TITLES: Readonly<Record<CheckoutStep, string>> = {
  address: 'Delivery address',
  payment: 'Payment method',
  review: 'Review and confirm',
};

function stepFromSearch(raw: string | null): CheckoutStep {
  return raw !== null && (CHECKOUT_STEPS as readonly string[]).includes(raw)
    ? (raw as CheckoutStep)
    : 'address';
}

function RefusalNotice({
  refusal,
  onAdjust,
  onChangeMethod,
}: {
  readonly refusal: Refusal;
  readonly onAdjust: () => void;
  readonly onChangeMethod: () => void;
}): JSX.Element {
  let title: string;
  let message: ReactNode;
  let action: JSX.Element;
  switch (refusal.kind) {
    case 'insufficientStock':
      title = 'Some items are no longer available';
      message = (
        <ul className={cart.changes}>
          {refusal.unavailableLines.map((line) => (
            <li key={line.productId}>
              {line.name}: requested {line.requestedQuantity}, available {line.availableQuantity}
            </li>
          ))}
        </ul>
      );
      action = (
        <Link className={buttons.button} to="/cart" onClick={onAdjust}>
          Adjust your cart
        </Link>
      );
      break;
    case 'paymentDeclined':
      title = 'Your payment was declined';
      message = `The payment was refused (${declineLabel(refusal.declineReason)}). The order was cancelled and your cart is kept: choose another payment method and try again.`;
      action = (
        <button className={buttons.button} type="button" onClick={onChangeMethod}>
          Choose another payment method
        </button>
      );
      break;
    case 'orderCancelled':
      title = 'The order was cancelled';
      message =
        'The order was cancelled while its payment was being processed; nothing was charged and your cart is kept.';
      action = (
        <button className={buttons.button} type="button" onClick={onAdjust}>
          Review your cart
        </button>
      );
      break;
    case 'idempotencyConflict':
      title = 'This confirmation was already sent with different details';
      message = 'Review the order and confirm it again.';
      action = (
        <button className={buttons.button} type="button" onClick={onAdjust}>
          Review again
        </button>
      );
      break;
    case 'rejected':
      title = 'The order could not be placed';
      message = refusal.message;
      action = (
        <button className={buttons.button} type="button" onClick={onAdjust}>
          Review again
        </button>
      );
      break;
  }
  return (
    <div className={cx(states.state, states.danger)} role="alert">
      <h2 className={states.title}>{title}</h2>
      {typeof message === 'string' ? <p className={states.message}>{message}</p> : message}
      {action}
    </div>
  );
}

/**
 * `/checkout?step=address|payment|review` (FR-006 to FR-008): a saved or new address (saved first
 * through identity, then referenced by id), one of the local payment methods, the review with the
 * server's amounts, and the one confirmation path of the checkout state machine. The draft lives
 * in sessionStorage (ids and the key only), so a reload or a sign-in round trip keeps it.
 */
export function CheckoutPage(): JSX.Element {
  const [searchParams, setSearchParams] = useSearchParams();
  const step = stepFromSearch(searchParams.get('step'));
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { identity } = usePorts();
  const { view, query: cartQuery, actions: cartActions } = useCart();
  const addresses = useOwnAddresses();
  const checkout = useCheckout();
  const { state, showStep, seeCart } = checkout;
  const [addingAddress, setAddingAddress] = useState(false);
  const [savingAddress, setSavingAddress] = useState(false);
  const [addressFieldErrors, setAddressFieldErrors] = useState<readonly FieldError[]>([]);
  const [addressFailure, setAddressFailure] = useState<unknown>(undefined);
  const [confirmFailure, setConfirmFailure] = useState<unknown>(undefined);

  useEffect(() => {
    showStep(step);
  }, [step, showStep]);

  const revision = view.revision;
  useEffect(() => {
    if (step === 'review' && revision !== undefined) seeCart(revision);
  }, [step, revision, seeCart]);

  const goTo = (next: CheckoutStep): void => {
    setSearchParams({ step: next });
  };

  const saveAddress = async (address: Address): Promise<void> => {
    correlation.next();
    setSavingAddress(true);
    setAddressFieldErrors([]);
    setAddressFailure(undefined);
    try {
      const saved = await identity.addOwnAddress(address);
      checkout.chooseAddress(saved.id);
      await addresses.refetch();
      setAddingAddress(false);
    } catch (error: unknown) {
      if (error instanceof ProblemError && error.problem.errors.length > 0) {
        setAddressFieldErrors(error.problem.errors);
      } else {
        setAddressFailure(error);
      }
    } finally {
      setSavingAddress(false);
    }
  };

  const confirm = async (): Promise<void> => {
    correlation.next();
    setConfirmFailure(undefined);
    const outcome = await checkout.submit();
    switch (outcome.kind) {
      case 'placed':
        await queryClient.invalidateQueries({ queryKey: CART_KEY });
        await navigate(`/orders/${outcome.order.id}/confirmation`, { replace: true });
        break;
      case 'unauthorized':
        await navigate(signInLocationFor('/checkout?step=review'), { replace: true });
        break;
      case 'refused':
        if (outcome.refusal.kind === 'insufficientStock') {
          cartActions.flagUnavailable(
            outcome.refusal.unavailableLines.map((line) => line.productId),
          );
        }
        break;
      case 'interrupted':
        setConfirmFailure(outcome.error);
        break;
      case 'priceChanged':
      case 'notSent':
        break;
    }
  };

  const acceptPrices = (): void => {
    correlation.next();
    checkout.acceptPrices();
    void cartQuery.refetch();
  };

  const chosenAddress = addresses.data?.items.find((item) => item.id === state.draft.addressId);
  const chosenMethod =
    state.draft.paymentMethodId === undefined
      ? undefined
      : PaymentMethod.byId(state.draft.paymentMethodId);
  const request = requestOf(state.draft);
  const canConfirm =
    request !== undefined &&
    view.canCheckout &&
    !state.inFlight &&
    state.status !== 'refusedPriceChange';

  const addressStep = (
    <QueryBoundary query={addresses} loadingLabel="Loading your addresses…">
      {(page) => (
        <>
          <AddressPicker
            addresses={page.items}
            selectedId={state.draft.addressId}
            onSelect={(id) => {
              checkout.chooseAddress(id);
            }}
          />
          {addingAddress ? (
            <>
              {addressFailure === undefined ? null : (
                <ErrorState title="The address was not saved" {...describeError(addressFailure)} />
              )}
              <AddressForm
                busy={savingAddress}
                serverErrors={addressFieldErrors}
                onSubmit={(address) => {
                  void saveAddress(address);
                }}
                onCancel={() => {
                  setAddingAddress(false);
                }}
              />
            </>
          ) : (
            <p>
              <button
                className={cx(buttons.button, buttons.secondary)}
                type="button"
                onClick={() => {
                  setAddingAddress(true);
                }}
              >
                Add a new address
              </button>
            </p>
          )}
          <div className={cart.actions}>
            <button
              className={buttons.button}
              type="button"
              disabled={state.draft.addressId === undefined}
              onClick={() => {
                goTo('payment');
              }}
            >
              Continue to payment
            </button>
          </div>
        </>
      )}
    </QueryBoundary>
  );

  const paymentStep = (
    <>
      <PaymentMethodPicker
        selectedId={state.draft.paymentMethodId}
        onSelect={(id) => {
          checkout.choosePaymentMethod(id);
        }}
      />
      <div className={cart.actions}>
        <button
          className={cx(buttons.button, buttons.secondary)}
          type="button"
          onClick={() => {
            goTo('address');
          }}
        >
          Back to the address
        </button>
        <button
          className={buttons.button}
          type="button"
          disabled={state.draft.paymentMethodId === undefined}
          onClick={() => {
            goTo('review');
          }}
        >
          Continue to review
        </button>
      </div>
    </>
  );

  const reviewStep = (
    <QueryBoundary query={cartQuery} loadingLabel="Loading your cart…">
      {() =>
        view.isEmpty ? (
          <Empty
            title="Your cart is empty"
            message="Add a product before checking out."
            action={{ label: 'Browse products', to: '/' }}
          />
        ) : (
          <>
            <OrderSummary
              caption="Items in your order"
              lines={view.lines.map((line) => ({
                key: line.id,
                name: line.productName,
                quantity: line.quantity,
                unitPrice: line.currentPrice,
                lineTotal: line.lineTotal,
              }))}
              total={view.total ?? { amountMinor: 0, currency: 'BRL' }}
            />
            <dl className={cart.statuses}>
              <dt>Deliver to</dt>
              <dd>
                {chosenAddress === undefined
                  ? 'No address chosen'
                  : `${chosenAddress.recipientName}, ${chosenAddress.line1}, ${chosenAddress.postalCode} ${chosenAddress.city}, ${chosenAddress.countryCode}`}{' '}
                <Link className={buttons.link} to="/checkout?step=address">
                  Change
                </Link>
              </dd>
              <dt>Pay with</dt>
              <dd>
                {chosenMethod?.label ?? 'No payment method chosen'}{' '}
                <Link className={buttons.link} to="/checkout?step=payment">
                  Change
                </Link>
              </dd>
            </dl>
            {!view.canCheckout ? (
              <p className={cart.unavailable} role="alert">
                Some items are no longer available. Remove them from the cart to continue.
              </p>
            ) : null}
            {state.status === 'refusedPriceChange' && state.priceChange !== undefined ? (
              <PriceChangeNotice
                changedLines={state.priceChange.changedLines}
                names={new Map(view.lines.map((line) => [line.productId, line.productName]))}
                onAccept={acceptPrices}
              />
            ) : null}
            {state.status === 'refused' && state.refusal !== undefined ? (
              <RefusalNotice
                refusal={state.refusal}
                onAdjust={() => {
                  checkout.adjust();
                }}
                onChangeMethod={() => {
                  checkout.adjust();
                  goTo('payment');
                }}
              />
            ) : null}
            {confirmFailure instanceof ThrottledError ? (
              <Throttled
                retryAfterSeconds={confirmFailure.retryAfterSeconds}
                onRetry={() => {
                  void confirm();
                }}
              />
            ) : confirmFailure !== undefined || state.interrupted ? (
              <ErrorState
                title="Your confirmation did not reach the store"
                {...describeError(confirmFailure)}
                onRetry={() => {
                  void confirm();
                }}
                retryLabel="Send it again"
              />
            ) : null}
            {state.inFlight ? (
              <p role="status" aria-live="polite">
                Placing your order…
              </p>
            ) : null}
            <div className={cart.actions}>
              <button
                className={buttons.button}
                type="button"
                disabled={!canConfirm}
                onClick={() => {
                  void confirm();
                }}
              >
                Confirm order
              </button>
            </div>
          </>
        )
      }
    </QueryBoundary>
  );

  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        Checkout
      </h1>
      <ol className={cart.steps} aria-label="Checkout steps">
        {CHECKOUT_STEPS.map((candidate) => (
          <li
            key={candidate}
            className={cart.step}
            aria-current={candidate === step ? 'step' : undefined}
          >
            {STEP_TITLES[candidate]}
          </li>
        ))}
      </ol>
      <h2>{STEP_TITLES[step]}</h2>
      {step === 'address' ? addressStep : step === 'payment' ? paymentStep : reviewStep}
    </section>
  );
}
