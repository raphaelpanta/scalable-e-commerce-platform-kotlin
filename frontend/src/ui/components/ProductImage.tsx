import { type JSX, useState } from 'react';

import styles from './states.module.css';

export type ProductImageProps = {
  readonly src?: string | undefined;
  readonly alt: string;
};

/** A product image with a neutral placeholder when the URL is missing or fails to load. */
export function ProductImage({ src, alt }: ProductImageProps): JSX.Element {
  const [failed, setFailed] = useState(false);
  if (src === undefined || src === '' || failed) {
    return (
      <span
        className={styles.imagePlaceholder}
        role="img"
        aria-label={`${alt} (no image available)`}
      >
        No image
      </span>
    );
  }
  return (
    <img
      className={styles.image}
      src={src}
      alt={alt}
      loading="lazy"
      decoding="async"
      onError={() => {
        setFailed(true);
      }}
    />
  );
}
