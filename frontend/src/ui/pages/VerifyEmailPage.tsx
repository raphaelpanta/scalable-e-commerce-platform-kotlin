import { type JSX, useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router';

import { usePorts } from '@app/ports';

import styles from './pages.module.css';
import buttons from '../components/buttons.module.css';
import { Loading } from '../components/Loading.tsx';

type Verification = 'verifying' | 'verified' | 'invalid';

/**
 * `/verify-email?token=` (and `/verify`, the path of the emailed link): the token is read once on
 * load and removed from the address with a history replace before the request is sent; the page
 * renders success or one generic "invalid or expired" message (FR-005).
 */
export function VerifyEmailPage(): JSX.Element {
  const [searchParams] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const { identity } = usePorts();
  const started = useRef(false);
  const [verification, setVerification] = useState<Verification>(() =>
    searchParams.get('token') === null ? 'invalid' : 'verifying',
  );

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    const token = searchParams.get('token');
    if (token === null || token === '') return;
    void navigate(location.pathname, { replace: true });
    identity.verifyEmail(token).then(
      () => {
        setVerification('verified');
      },
      () => {
        setVerification('invalid');
      },
    );
  }, [identity, location.pathname, navigate, searchParams]);

  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        Verify your email
      </h1>
      {verification === 'verifying' ? <Loading label="Verifying your email…" /> : null}
      {verification === 'verified' ? (
        <>
          <p role="status">Your email is verified. You can sign in now.</p>
          <p className={styles.actions}>
            <Link className={buttons.button} to="/sign-in">
              Sign in
            </Link>
          </p>
        </>
      ) : null}
      {verification === 'invalid' ? (
        <>
          <p role="alert">This verification link is invalid or has expired.</p>
          <p className={styles.actions}>
            <Link className={buttons.button} to="/register">
              Register again
            </Link>
            <Link className={buttons.link} to="/sign-in">
              Sign in
            </Link>
          </p>
        </>
      ) : null}
    </section>
  );
}
