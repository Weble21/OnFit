import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: Number(process.env.PORT || 5173),
    strictPort: true,
    // The Spring Boot API; same target as the production server in server.mjs.
    proxy: { '/api': process.env.BACKEND_URL || 'http://127.0.0.1:8080' },
  },
});
