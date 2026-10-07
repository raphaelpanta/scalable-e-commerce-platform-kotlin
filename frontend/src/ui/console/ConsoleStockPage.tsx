import { type JSX, type SubmitEvent, useId, useState } from 'react';
import { useSearchParams } from 'react-router';

import {
  DEFAULT_PAGE_SIZE,
  listingFromSearch,
  normalizeSearchTerm,
} from '@app/catalog/browseParams';
import type { Product } from '@app/catalog/catalogPort';
import { useProducts } from '@app/catalog/useProducts';

import styles from './console.module.css';
import { ConsolePager } from './ConsolePager.tsx';
import { platformRefusal } from './refusal.ts';
import { StockAdjustmentForm } from './StockAdjustmentForm.tsx';
import buttons from '../components/buttons.module.css';
import { Empty } from '../components/Empty.tsx';
import forms from '../components/forms.module.css';
import { QueryBoundary } from '../components/QueryBoundary.tsx';
import { cx } from '../cx.ts';
import { NotAllowedPage } from '../pages/NotAllowedPage.tsx';
import pages from '../pages/pages.module.css';

function availability(product: Product): string {
  const units = product.availability.availableQuantity;
  if (units !== undefined) return String(units);
  return product.availability.inStock ? 'In stock' : 'Out of stock';
}

function ProductSearch({ term }: { readonly term: string }): JSX.Element {
  const inputId = useId();
  const [searchParams, setSearchParams] = useSearchParams();
  const [typed, setTyped] = useState(term);
  const submit = (event: SubmitEvent<HTMLFormElement>): void => {
    event.preventDefault();
    const next = new URLSearchParams(searchParams);
    next.delete('page');
    const normalized = normalizeSearchTerm(typed);
    if (normalized === '') next.delete('q');
    else next.set('q', normalized);
    setSearchParams(next);
  };
  return (
    <form role="search" aria-label="Find a product" className={styles.controlRow} onSubmit={submit}>
      <div className={styles.control}>
        <label className={forms.label} htmlFor={inputId}>
          Search products
        </label>
        <input
          id={inputId}
          className={forms.input}
          type="search"
          value={typed}
          onChange={(event) => {
            setTyped(event.target.value);
          }}
        />
      </div>
      <button className={cx(buttons.button)} type="submit">
        Search
      </button>
    </form>
  );
}

/**
 * `/console/stock`: the products, withdrawn ones included (`includeWithdrawn` is for visibility
 * only), with the units on hand, a search by name and one stock adjustment at a time (FR-011).
 * There is nothing to create, edit, withdraw or reinstate here: the console layout says so.
 */
export function ConsoleStockPage(): JSX.Element {
  const [searchParams] = useSearchParams();
  const term = normalizeSearchTerm(searchParams.get('q'));
  const listing = listingFromSearch(searchParams);
  const products = useProducts({
    ...listing,
    ...(term === '' ? {} : { q: term }),
    includeWithdrawn: true,
  });
  const [selected, setSelected] = useState<string | undefined>(undefined);
  const refusal = platformRefusal(products.error);
  if (refusal !== undefined) {
    return <NotAllowedPage {...(refusal.detail === undefined ? {} : { detail: refusal.detail })} />;
  }

  return (
    <section className={pages.page} aria-labelledby="page-title">
      <h1 id="page-title" className={pages.title}>
        Stock
      </h1>
      <ProductSearch key={term} term={term} />
      <QueryBoundary query={products} loadingLabel="Loading products…">
        {(page) => {
          const chosen = page.items.find((product) => product.id === selected);
          return page.items.length === 0 ? (
            <Empty
              title={term === '' ? 'No products' : `Nothing found for “${term}”`}
              message="Try another word."
              action={{ label: 'Show all products', to: '/console/stock' }}
            />
          ) : (
            <>
              <div className={styles.scroller}>
                <table className={styles.table}>
                  <caption>Products</caption>
                  <thead>
                    <tr>
                      <th scope="col">Product</th>
                      <th scope="col">Status</th>
                      <th scope="col" className={styles.numeric}>
                        Available
                      </th>
                      <th scope="col">
                        <span className={styles.visuallyHidden}>Actions</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.items.map((product) => (
                      <tr key={product.id}>
                        <th scope="row">{product.name}</th>
                        <td>{product.status === 'withdrawn' ? 'Withdrawn' : 'Active'}</td>
                        <td className={styles.numeric}>{availability(product)}</td>
                        <td>
                          <button
                            className={cx(buttons.button, buttons.secondary)}
                            type="button"
                            aria-label={`Adjust stock of ${product.name}`}
                            onClick={() => {
                              setSelected(product.id);
                            }}
                          >
                            Adjust stock
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {chosen === undefined ? null : (
                <StockAdjustmentForm
                  key={chosen.id}
                  productId={chosen.id}
                  productName={chosen.name}
                  available={chosen.availability.availableQuantity}
                />
              )}
              <ConsolePager
                page={page.page}
                size={page.size > 0 ? page.size : (listing.size ?? DEFAULT_PAGE_SIZE)}
                totalItems={page.totalItems}
                shown={page.items.length}
                noun="products"
              />
            </>
          );
        }}
      </QueryBoundary>
    </section>
  );
}
