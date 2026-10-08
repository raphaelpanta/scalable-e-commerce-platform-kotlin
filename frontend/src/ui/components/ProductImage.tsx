import { type JSX, useState } from 'react';

import { BrandMark } from '../brand/BrandMark.tsx';
import { cx } from '../cx.ts';
import styles from './states.module.css';

export type ProductImageProps = {
  readonly src?: string | undefined;
  readonly alt: string;
  /** The frame's proportions: the listing card (default) or the product page. */
  readonly ratio?: 'card' | 'detail';
};

/** A product image in a fixed frame, with a branded placeholder when the URL is missing or fails to load. */
export function ProductImage({ src, alt, ratio = 'card' }: ProductImageProps): JSX.Element {
  const [failed, setFailed] = useState(false);
  const frame = ratio === 'detail' ? styles.ratioDetail : styles.ratioCard;
  if (src === undefined || src === '' || failed) {
    return (
      <span
        className={cx(styles.imagePlaceholder, frame)}
        role="img"
        aria-label={`${alt} (no image available)`}
      >
        <BrandMark />
        <span>No image</span>
      </span>
    );
  }
  return (
    <img
      className={cx(styles.image, frame)}
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
