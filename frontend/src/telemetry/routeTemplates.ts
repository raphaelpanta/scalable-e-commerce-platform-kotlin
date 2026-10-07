import { RouteTemplate, UNKNOWN_ROUTE } from '@domain/routeTemplate';

// The closed list of platform API route templates a storefront request can be reduced to, mirroring
// the paths of the OpenAPI contracts the storefront consumes (src/api/generated). A concrete URL is
// never exported (FR-032): it is matched segment by segment against this list and only the template
// leaves the page. Whatever matches nothing is `/unknown`.
export const API_ROUTE_TEMPLATES = [
  '/api/v1/cart',
  '/api/v1/cart/lines',
  '/api/v1/cart/lines/:id',
  '/api/v1/cart/merge',
  '/api/v1/catalog/categories',
  '/api/v1/catalog/categories/:id',
  '/api/v1/catalog/products',
  '/api/v1/catalog/products/:id',
  '/api/v1/identity/accounts',
  '/api/v1/identity/accounts/me',
  '/api/v1/identity/accounts/me/addresses',
  '/api/v1/identity/accounts/me/addresses/:id',
  '/api/v1/identity/accounts/me/notification-preferences',
  '/api/v1/identity/accounts/me/phone-verifications',
  '/api/v1/identity/accounts/me/phone-verifications/confirm',
  '/api/v1/identity/accounts/verify-email',
  '/api/v1/identity/password-resets',
  '/api/v1/identity/password-resets/complete',
  '/api/v1/identity/sessions',
  '/api/v1/identity/sessions/current',
  '/api/v1/identity/sessions/refresh',
  '/api/v1/orders',
  '/api/v1/orders/:id',
  '/api/v1/orders/:id/cancellation',
  '/api/v1/orders/:id/status',
  '/api/v1/payments/attempts',
  '/api/v1/payments/attempts/:id',
  '/api/v1/payments/refunds',
  '/api/v1/payments/refunds/:id',
  '/api/v1/telemetry/v1/logs',
  '/api/v1/telemetry/v1/traces',
] as const;

export type ApiRouteTemplate = (typeof API_ROUTE_TEMPLATES)[number];
export type ExportedRoute = RouteTemplate | ApiRouteTemplate;

const PLACEHOLDER = ':id';
const API_PREFIX = '/api/';
// A base for relative references; it is never exported and never matched against.
const RELATIVE_BASE = 'http://storefront.invalid';
const SCHEME_OR_NETWORK_PATH = /^([a-z][a-z0-9+.-]*:|\/\/)/i;

function segmentsOf(path: string): readonly string[] {
  return path.split('/').filter((segment) => segment.length > 0);
}

const API_TEMPLATE_SEGMENTS = API_ROUTE_TEMPLATES.map((template) => ({
  template,
  segments: segmentsOf(template),
}));

function matchApi(pathname: string): ApiRouteTemplate | undefined {
  const pathSegments = segmentsOf(pathname);
  return API_TEMPLATE_SEGMENTS.find(
    ({ segments }) =>
      segments.length === pathSegments.length &&
      segments.every(
        (segment, index) => segment === PLACEHOLDER || segment === pathSegments[index],
      ),
  )?.template;
}

function parse(url: string): URL | undefined {
  return URL.canParse(url, RELATIVE_BASE) ? new URL(url, RELATIVE_BASE) : undefined;
}

/**
 * Reduces a URL (absolute or relative, query and hash included) to a template: a storefront route
 * (`/products/:id`), a platform API route (`/api/v1/orders/:id`) or `/unknown`. A URL of another
 * origin is `/unknown` whatever its path.
 */
export function routeOf(url: string, origin: string): ExportedRoute {
  const parsed = parse(url);
  if (parsed === undefined) return UNKNOWN_ROUTE;
  if (SCHEME_OR_NETWORK_PATH.test(url) && parsed.origin !== origin) return UNKNOWN_ROUTE;
  if (parsed.pathname.startsWith(API_PREFIX)) return matchApi(parsed.pathname) ?? UNKNOWN_ROUTE;
  return RouteTemplate.fromPathname(parsed.pathname);
}
