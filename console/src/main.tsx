import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { httpClient } from './api/VelleClient';
import './console.css';
import { Console } from './frame/Console';
import { storedRequestHeaders } from './frame/requestHeader';

// What the starter wrote into the page (index.html, VelleConsole.kt). Under `npm run dev` nothing
// has written it and the placeholders are still there, so the paths are the starter's defaults.
interface ServedSettings {
  api: string;
  graphql: string;
  ticks: string;
}

declare global {
  interface Window { velleConsole: ServedSettings }
}

const DEVELOPMENT_SETTINGS: ServedSettings = { api: '/api', graphql: '/graphql', ticks: 'true' };

const served = window.velleConsole;
const settings = served.api.startsWith('__') ? DEVELOPMENT_SETTINGS : served;
const client = httpClient({ apiPath: settings.api, graphqlPath: settings.graphql, headers: storedRequestHeaders });

createRoot(document.getElementById('console')!).render(
  <StrictMode>
    <Console client={client} ticksExposed={settings.ticks === 'true'} />
  </StrictMode>,
);
