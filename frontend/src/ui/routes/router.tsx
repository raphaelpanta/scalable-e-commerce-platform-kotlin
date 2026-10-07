import type { QueryClient } from '@tanstack/react-query';
import type { JSX } from 'react';
import {
  createBrowserRouter,
  createMemoryRouter,
  type LoaderFunctionArgs,
  Outlet,
  replace,
  type RouteObject,
} from 'react-router';

import { signInLocationFor } from '@app/navigation/safeNext';
import { hasRole, type SessionStore } from '@app/session/sessionStore';
import { useSession } from '@app/session/useSession';
import { type KnownRouteTemplate, ROUTE_TEMPLATES } from '@domain/routeTemplate';

import { Layout } from '../components/Layout.tsx';
import { Loading } from '../components/Loading.tsx';
import { CartPage } from '../pages/CartPage.tsx';
import { CategoryPage } from '../pages/CategoryPage.tsx';
import { CheckoutPage } from '../pages/CheckoutPage.tsx';
import { ConfirmationPage } from '../pages/ConfirmationPage.tsx';
import { HomePage } from '../pages/HomePage.tsx';
import { NotAllowedPage } from '../pages/NotAllowedPage.tsx';
import { NotFoundPage } from '../pages/NotFoundPage.tsx';
import { PlaceholderPage } from '../pages/PlaceholderPage.tsx';
import { ProductPage } from '../pages/ProductPage.tsx';
import { RegisterPage } from '../pages/RegisterPage.tsx';
import { SearchPage } from '../pages/SearchPage.tsx';
import { SignInPage } from '../pages/SignInPage.tsx';
import { VerifyEmailPage } from '../pages/VerifyEmailPage.tsx';

export type RouterDependencies = {
  readonly queryClient: QueryClient;
  readonly sessionStore: SessionStore;
};

type Access = 'anonymous' | 'shopper' | 'operator';

/** Access level of every route of contracts/storefront-routes.md. */
export const ROUTE_ACCESS: Readonly<Record<KnownRouteTemplate, Access>> = {
  '/': 'anonymous',
  '/categories/:id': 'anonymous',
  '/search': 'anonymous',
  '/products/:id': 'anonymous',
  '/cart': 'anonymous',
  '/sign-in': 'anonymous',
  '/register': 'anonymous',
  '/verify-email': 'anonymous',
  '/verify': 'anonymous',
  '/reset-password': 'anonymous',
  '/forgot-password': 'anonymous',
  '/checkout': 'shopper',
  '/orders': 'shopper',
  '/orders/:id': 'shopper',
  '/orders/:id/confirmation': 'shopper',
  '/account': 'shopper',
  '/account/addresses': 'shopper',
  '/account/notifications': 'shopper',
  '/console/orders': 'operator',
  '/console/orders/:id': 'operator',
  '/console/stock': 'operator',
};

/** The pages implemented so far; every other template renders its placeholder. */
const PAGES: Partial<Readonly<Record<KnownRouteTemplate, JSX.Element>>> = {
  '/': <HomePage />,
  // `/categories/:slugOrId` of the route table: the `id` segment accepts a slug prefix (app/catalog).
  '/categories/:id': <CategoryPage />,
  '/search': <SearchPage />,
  '/products/:id': <ProductPage />,
  '/cart': <CartPage />,
  '/sign-in': <SignInPage />,
  '/register': <RegisterPage />,
  // The route table's path and the path of the emailed link render the same page.
  '/verify-email': <VerifyEmailPage />,
  '/verify': <VerifyEmailPage />,
  '/checkout': <CheckoutPage />,
  '/orders/:id/confirmation': <ConfirmationPage />,
};

const PLACEHOLDER_TITLES: Readonly<Record<KnownRouteTemplate, string>> = {
  '/': 'Products',
  '/categories/:id': 'Category',
  '/search': 'Search',
  '/products/:id': 'Product',
  '/cart': 'Your cart',
  '/sign-in': 'Sign in',
  '/register': 'Create an account',
  '/verify-email': 'Verify your email',
  '/verify': 'Verify your email',
  '/reset-password': 'Choose a new password',
  '/forgot-password': 'Forgot your password?',
  '/checkout': 'Checkout',
  '/orders': 'Your orders',
  '/orders/:id': 'Order',
  '/orders/:id/confirmation': 'Order confirmation',
  '/account': 'Your account',
  '/account/addresses': 'Your addresses',
  '/account/notifications': 'Notification preferences',
  '/console/orders': 'Console: orders',
  '/console/orders/:id': 'Console: order',
  '/console/stock': 'Console: stock',
};

/**
 * Loader of every protected route: resolves the page-load probe once (cached afterwards) and
 * sends an anonymous visitor to `/sign-in?next=<path>` with a replace navigation (redirect rule).
 */
function requireSignedIn({ queryClient, sessionStore }: RouterDependencies) {
  return async ({ request }: LoaderFunctionArgs): Promise<null | Response> => {
    const summary = await queryClient.query({ ...sessionStore.probeQuery(), staleTime: 'static' });
    if (summary.state === 'signedIn') return null;
    const url = new URL(request.url);
    return replace(signInLocationFor(`${url.pathname}${url.search}`));
  };
}

/** Console pages render only for the operator role; the platform's 403 is what refuses (FR-012). */
function OperatorOnly(): JSX.Element {
  const { summary, resolving } = useSession();
  if (resolving) return <Loading />;
  return hasRole(summary, 'operator') ? <Outlet /> : <NotAllowedPage />;
}

function toRouterPath(template: KnownRouteTemplate): string {
  // React Router uses `:param` as well; the parameter name is `id` for every dynamic segment.
  return template;
}

export function buildRoutes(dependencies: RouterDependencies): RouteObject[] {
  const protectedLoader = requireSignedIn(dependencies);
  const children: RouteObject[] = [];
  const consoleChildren: RouteObject[] = [];
  for (const template of ROUTE_TEMPLATES) {
    const access = ROUTE_ACCESS[template];
    const element = PAGES[template] ?? <PlaceholderPage title={PLACEHOLDER_TITLES[template]} />;
    const route: RouteObject =
      template === '/'
        ? { index: true, element }
        : {
            path: toRouterPath(template),
            element,
            ...(access === 'anonymous' ? {} : { loader: protectedLoader }),
          };
    if (access === 'operator') consoleChildren.push(route);
    else children.push(route);
  }
  children.push({ element: <OperatorOnly />, children: consoleChildren });
  children.push({ path: '*', element: <NotFoundPage /> });
  return [
    {
      path: '/',
      element: <Layout />,
      children,
    },
  ];
}

export function createStorefrontRouter(
  dependencies: RouterDependencies,
): ReturnType<typeof createBrowserRouter> {
  return createBrowserRouter(buildRoutes(dependencies));
}

/** The same route tree on an in-memory history (component tests). */
export function createTestRouter(
  dependencies: RouterDependencies,
  initialEntries: string[],
): ReturnType<typeof createMemoryRouter> {
  return createMemoryRouter(buildRoutes(dependencies), { initialEntries });
}
