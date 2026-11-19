import { useEffect, useRef, useState } from 'react';
import { errorText, type VelleClient } from '../api/VelleClient';
import { ActForm, RecordView, ScheduleView, ShapeTable, type TableQuery } from '../components';
import type { ModelDescription } from '../model/description';
import { Navigation } from './Navigation';
import { RequestHeaderBox } from './RequestHeaderBox';
import { activeTarget, recordHash, routeOf, type Route } from './routes';

export interface ConsoleProps {
  client: VelleClient;
  /** Whether the application lets a caller fire a tick. */
  ticksExposed: boolean;
}

interface RoutedViewProps {
  model: ModelDescription;
  client: VelleClient;
  ticksExposed: boolean;
  route: Route;
  /** What the developer last asked of each shape's table, kept while the console is open. */
  tableQueries: Map<string, TableQuery>;
}

function RoutedView({ model, client, ticksExposed, route, tableQueries }: RoutedViewProps) {
  if (route.view === 'shape') {
    return <ShapeTable key={route.shapeName} model={model} client={client} shapeName={route.shapeName} recordHref={recordHash}
      initialQuery={tableQueries.get(route.shapeName)} onQuery={query => tableQueries.set(route.shapeName, query)} />;
  }
  if (route.view === 'record') {
    return <RecordView model={model} client={client} shapeName={route.shapeName} recordKey={route.recordKey} recordHref={recordHash} />;
  }
  if (route.view === 'act') return <ActForm model={model} client={client} actName={route.actName} recordHref={recordHash} />;
  if (route.view === 'tick') return <ScheduleView client={client} schedule={route.schedule} ticksExposed={ticksExposed} />;
  return null;
}

function useHash(): string {
  const [hash, setHash] = useState(location.hash);
  useEffect(() => {
    const onHashChange = () => setHash(location.hash);
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);
  return hash;
}

/**
 * The console: the sidebar, the request header, and one view at a time chosen by the URL's hash.
 * It reads the model description once and builds every view from it.
 */
export function Console({ client, ticksExposed }: ConsoleProps) {
  const [model, setModel] = useState<ModelDescription | null>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const tableQueries = useRef(new Map<string, TableQuery>());
  const hash = useHash();

  useEffect(() => {
    client.describeModel().then(setModel).catch(thrown => setRefusal(errorText(thrown)));
  }, [client]);

  const route: Route = model ? routeOf(hash, model) : { view: 'none' };
  return (
    <>
      <nav>
        <h1>Velle</h1>
        {model && <Navigation model={model} active={activeTarget(route)} />}
        <RequestHeaderBox />
      </nav>
      <main>
        {refusal !== null && <pre className="outcome bad">{refusal}</pre>}
        {refusal === null && model === null && <div className="empty">Loading the model…</div>}
        {model && <RoutedView model={model} client={client} ticksExposed={ticksExposed} route={route} tableQueries={tableQueries.current} />}
      </main>
    </>
  );
}
