/** Flat route table for the scaffolded RN host. */
export type AppRoute = 'home' | 'chainedUpstream';

export const CHAINED_UPSTREAM_ROUTE: AppRoute = 'chainedUpstream';

export function isChainedUpstreamRoute(route: AppRoute): boolean {
  return route === CHAINED_UPSTREAM_ROUTE;
}
