import { QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { createQueryClient } from './lib/queryClient'
import './index.css'

const queryClient = createQueryClient()

// index.html owns this element. The assertion the Vite template writes here
// (`getElementById('root')!`) would fail as a React internals error if it ever
// stopped being true; naming it costs one line.
const rootElement = document.getElementById('root')
if (rootElement === null) throw new Error('index.html is missing <div id="root">')

createRoot(rootElement).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>,
)
