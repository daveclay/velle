import react from '@vitejs/plugin-react';
import { viteSingleFile } from 'vite-plugin-singlefile';
import { defineConfig } from 'vitest/config';

// The build is one file, dist/index.html, with the script and the styles inside it: the Spring
// starter serves that one file as the console (apis/spring, VelleConsole.kt).
// `npm run dev` serves the console against an application already running on VELLE_APP.
const runningApplication = process.env.VELLE_APP ?? 'http://localhost:8090';

export default defineConfig({
  plugins: [react(), viteSingleFile()],
  server: {
    proxy: {
      '/api': runningApplication,
      '/graphql': runningApplication,
    },
  },
  test: {
    environment: 'jsdom',
  },
});
