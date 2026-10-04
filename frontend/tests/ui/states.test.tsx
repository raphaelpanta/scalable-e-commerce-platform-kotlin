import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ProblemError, UnavailableError } from '@api/problem';
import { ConfirmDialog } from '@ui/components/ConfirmDialog';
import { Empty } from '@ui/components/Empty';
import { describeError, ErrorState } from '@ui/components/ErrorState';
import { Loading } from '@ui/components/Loading';
import { Money } from '@ui/components/Money';
import { ProductImage } from '@ui/components/ProductImage';
import { Throttled } from '@ui/components/Throttled';
import { NotFoundPage } from '@ui/pages/NotFoundPage';

function withRouter(element: React.ReactNode) {
  const router = createMemoryRouter([{ path: '*', element }], { initialEntries: ['/x'] });
  return render(<RouterProvider router={router} />);
}

describe('Loading', () => {
  it('is announced as a status', () => {
    render(<Loading />);
    expect(screen.getByRole('status')).toHaveTextContent('Loading…');
  });
});

describe('Empty', () => {
  it('shows the explanation and exactly one next action as a link', () => {
    withRouter(
      <Empty
        title="Your cart is empty"
        message="Add a product to get started."
        action={{ label: 'Browse products', to: '/' }}
      />,
    );
    expect(screen.getByRole('heading', { name: 'Your cart is empty' })).toBeInTheDocument();
    expect(screen.getByText('Add a product to get started.')).toBeInTheDocument();
    const actions = [...screen.queryAllByRole('link'), ...screen.queryAllByRole('button')];
    expect(actions).toHaveLength(1);
    expect(actions[0]).toHaveAttribute('href', '/');
  });

  it('supports a button action', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    render(<Empty title="No orders yet" action={{ label: 'Place a first order', onClick }} />);
    await user.click(screen.getByRole('button', { name: 'Place a first order' }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});

describe('ErrorState', () => {
  it('offers retry and hides the correlation id in a collapsible support section', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    render(
      <ErrorState
        message="The catalogue could not be loaded."
        correlationId="0b4e6d1c-aaaa-4bbb-8ccc-000000000001"
        onRetry={onRetry}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('The catalogue could not be loaded.');
    const details = screen.getByText('Support details').closest('details');
    expect(details).not.toBeNull();
    expect(details).not.toHaveAttribute('open');
    await user.click(screen.getByText('Support details'));
    expect(details).toHaveAttribute('open');
    expect(screen.getByText('0b4e6d1c-aaaa-4bbb-8ccc-000000000001')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('describes typed API errors from the problem and never leaks raw JSON', () => {
    const problem = new ProblemError({
      type: 'validation',
      title: 'Validation failed',
      detail: 'The quantity must be between 1 and 99.',
      status: 422,
      correlationId: 'c-1',
      errors: [{ field: 'quantity', message: 'between 1 and 99' }],
    });
    expect(describeError(problem)).toEqual({
      message: 'The quantity must be between 1 and 99.',
      correlationId: 'c-1',
    });
    expect(describeError(new UnavailableError(undefined, new TypeError('x')))).toEqual({
      message: 'The store is temporarily unavailable',
    });
    expect(describeError(new Error('secret stack'))).toEqual({
      message: 'Something went wrong. Please try again.',
    });
    render(<ErrorState {...describeError(problem)} />);
    expect(screen.queryByText(/"type"/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});

describe('Throttled', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('counts down live and enables retry only when the wait is over', async () => {
    const onRetry = vi.fn();
    render(<Throttled retryAfterSeconds={3} onRetry={onRetry} />);
    const retry = screen.getByRole('button', { name: 'Try again' });
    expect(retry).toBeDisabled();
    expect(screen.getByRole('status')).toHaveTextContent('Try again in 3 seconds.');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(screen.getByRole('status')).toHaveTextContent('Try again in 2 seconds.');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(screen.getByRole('status')).toHaveTextContent('Try again in 1 second.');
    expect(retry).toBeDisabled();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(screen.getByRole('status')).toHaveTextContent('You can try again now.');
    expect(retry).toBeEnabled();
    retry.click();
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});

describe('ConfirmDialog', () => {
  it('renders nothing when closed and a modal dialog with focus and Escape handling when open', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    const { rerender } = render(
      <ConfirmDialog
        open={false}
        title="Cancel this order?"
        onConfirm={onConfirm}
        onCancel={onCancel}
      />,
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    rerender(
      <ConfirmDialog
        open
        title="Cancel this order?"
        description="This cannot be undone."
        destructive
        onConfirm={onConfirm}
        onCancel={onCancel}
      />,
    );
    const dialog = screen.getByRole('dialog', { name: 'Cancel this order?' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAccessibleDescription('This cannot be undone.');
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(onCancel).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });
});

describe('Money and ProductImage', () => {
  it('formats through the domain value object', () => {
    render(<Money value={{ amountMinor: 123456, currency: 'BRL' }} locale="en-US" />);
    expect(screen.getByText('R$1,234.56')).toBeInTheDocument();
  });

  it('shows a neutral placeholder when the image is missing or fails to load', () => {
    const { rerender } = render(<ProductImage alt="Ceramic mug" />);
    expect(
      screen.getByRole('img', { name: 'Ceramic mug (no image available)' }),
    ).toBeInTheDocument();
    rerender(<ProductImage alt="Ceramic mug" src="https://images.example/mug.jpg" />);
    const image = screen.getByRole('img', { name: 'Ceramic mug' });
    expect(image).toHaveAttribute('src', 'https://images.example/mug.jpg');
    act(() => {
      image.dispatchEvent(new Event('error'));
    });
    expect(
      screen.getByRole('img', { name: 'Ceramic mug (no image available)' }),
    ).toBeInTheDocument();
  });
});

describe('NotFoundPage', () => {
  it('links to the home page and the search page', () => {
    withRouter(<NotFoundPage />);
    expect(screen.getByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Browse products' })).toHaveAttribute('href', '/');
    expect(screen.getByRole('link', { name: 'Search the store' })).toHaveAttribute(
      'href',
      '/search',
    );
  });
});
