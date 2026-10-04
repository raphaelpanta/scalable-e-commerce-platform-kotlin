// The closed list of route templates (data-model.md §2.2 and contracts/storefront-routes.md). A
// pathname is reduced to a template by matching, never by regex-stripping a URL, so no id, token
// or query string can survive. The router registers exactly this list.
export const ROUTE_TEMPLATES = [
  '/',
  '/categories/:id',
  '/search',
  '/products/:id',
  '/cart',
  '/sign-in',
  '/register',
  '/verify-email',
  '/reset-password',
  '/forgot-password',
  '/checkout',
  '/orders',
  '/orders/:id',
  '/orders/:id/confirmation',
  '/account',
  '/account/addresses',
  '/account/notifications',
  '/console/orders',
  '/console/orders/:id',
  '/console/stock',
] as const;

export type KnownRouteTemplate = (typeof ROUTE_TEMPLATES)[number];
export const UNKNOWN_ROUTE = '/unknown';
export type RouteTemplate = KnownRouteTemplate | typeof UNKNOWN_ROUTE;

/** Routes whose purpose is authentication; never a redirect target (storefront-routes.md). */
export const AUTH_ROUTE_TEMPLATES: ReadonlySet<RouteTemplate> = new Set<RouteTemplate>([
  '/sign-in',
  '/register',
  '/verify-email',
  '/forgot-password',
  '/reset-password',
]);

function segmentsOf(path: string): readonly string[] {
  return path.split('/').filter((segment) => segment.length > 0);
}

type TemplateSegments = { template: KnownRouteTemplate; segments: readonly string[] };

const TEMPLATE_SEGMENTS: readonly TemplateSegments[] = ROUTE_TEMPLATES.map((template) => ({
  template,
  segments: segmentsOf(template),
}));

function stripQueryAndHash(pathname: string): string {
  const cut = Math.min(
    ...['?', '#'].map((marker) => {
      const index = pathname.indexOf(marker);
      return index === -1 ? pathname.length : index;
    }),
  );
  return pathname.slice(0, cut);
}

function matches(templateSegments: readonly string[], pathSegments: readonly string[]): boolean {
  if (templateSegments.length !== pathSegments.length) return false;
  return templateSegments.every((segment, index) => {
    const actual = pathSegments[index];
    return actual !== undefined && (segment === ':id' || segment === actual);
  });
}

export const RouteTemplate = {
  /** Maps a pathname (query and hash ignored) to its template, `/unknown` when it matches none. */
  fromPathname(pathname: string): RouteTemplate {
    const path = stripQueryAndHash(pathname);
    if (!path.startsWith('/')) return UNKNOWN_ROUTE;
    const pathSegments = segmentsOf(path);
    const hit = TEMPLATE_SEGMENTS.find(({ segments }) => matches(segments, pathSegments));
    return hit?.template ?? UNKNOWN_ROUTE;
  },
  isKnown(template: RouteTemplate): template is KnownRouteTemplate {
    return template !== UNKNOWN_ROUTE;
  },
  isAuthRoute(template: RouteTemplate): boolean {
    return AUTH_ROUTE_TEMPLATES.has(template);
  },
} as const;
