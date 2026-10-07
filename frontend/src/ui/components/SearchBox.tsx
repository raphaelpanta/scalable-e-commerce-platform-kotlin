import { type JSX, type SubmitEvent, useId, useState } from 'react';
import { useNavigate } from 'react-router';

import { MAX_SEARCH_LENGTH, normalizeSearchTerm } from '@app/catalog/browseParams';
import { correlation } from '@app/correlation';

import buttons from './buttons.module.css';
import styles from './SearchBox.module.css';

export type SearchBoxProps = {
  /** The term currently in the address; remount with `key` when it changes. */
  readonly initialTerm?: string;
};

/**
 * Search by name (FR-001). Submitting puts the trimmed term in the address (`/search?q=`) and
 * resets the page; the request itself follows from the URL (FR-003).
 */
export function SearchBox({ initialTerm = '' }: SearchBoxProps): JSX.Element {
  const inputId = useId();
  const navigate = useNavigate();
  const [term, setTerm] = useState(initialTerm);

  const onSubmit = (event: SubmitEvent<HTMLFormElement>): void => {
    event.preventDefault();
    correlation.next();
    const next = normalizeSearchTerm(term);
    const search = next === '' ? '' : `?${new URLSearchParams({ q: next }).toString()}`;
    void navigate({ pathname: '/search', search });
  };

  return (
    <form className={styles.form} role="search" onSubmit={onSubmit}>
      <label className={styles.label} htmlFor={inputId}>
        Search products
      </label>
      <div className={styles.row}>
        <input
          id={inputId}
          className={styles.input}
          type="search"
          name="q"
          value={term}
          maxLength={MAX_SEARCH_LENGTH}
          autoComplete="off"
          onChange={(event) => {
            setTerm(event.target.value);
          }}
        />
        <button className={buttons.button} type="submit">
          Search
        </button>
      </div>
    </form>
  );
}
