import type { ModelDescription } from '../model/description';

// The view is in the URL's hash: shape/<Shape>, record/<Shape>/<key>, act/<Act>, or tick/<Schedule>.

export type Route =
  | { view: 'shape'; shapeName: string }
  | { view: 'record'; shapeName: string; recordKey: string }
  | { view: 'act'; actName: string }
  | { view: 'tick'; schedule: string }
  | { view: 'none' };

export function shapeTarget(shapeName: string): string {
  return 'shape/' + encodeURIComponent(shapeName);
}

export function actTarget(actName: string): string {
  return 'act/' + encodeURIComponent(actName);
}

export function tickTarget(schedule: string): string {
  return 'tick/' + encodeURIComponent(schedule);
}

export function recordHash(shapeName: string, key: string | number): string {
  return `#record/${encodeURIComponent(shapeName)}/${encodeURIComponent(key)}`;
}

/** The route a hash names; an empty hash is the model's first shape. */
export function routeOf(hash: string, model: ModelDescription): Route {
  const target = hash.length > 1 ? hash.slice(1) : (model.shapes[0] ? shapeTarget(model.shapes[0].name) : '');
  const [view, first, second] = target.split('/').map(decodeURIComponent);
  if (view === 'shape' && first) return { view: 'shape', shapeName: first };
  if (view === 'record' && first && second) return { view: 'record', shapeName: first, recordKey: second };
  if (view === 'act' && first) return { view: 'act', actName: first };
  if (view === 'tick' && first) return { view: 'tick', schedule: first };
  return { view: 'none' };
}

/** The sidebar link a route lights up: a record lights up its shape, every other view its own link. */
export function activeTarget(route: Route): string {
  if (route.view === 'shape' || route.view === 'record') return shapeTarget(route.shapeName);
  if (route.view === 'act') return actTarget(route.actName);
  if (route.view === 'tick') return tickTarget(route.schedule);
  return '';
}
