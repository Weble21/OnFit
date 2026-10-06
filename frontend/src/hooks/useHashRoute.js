import { startTransition, useEffect, useState } from 'react';

const currentRoute = () => location.hash.slice(1) || '/';

/** Hash routing: "#/profile" → "/profile". Unknown routes go back to the landing page. */
export function useHashRoute(allowed) {
  const [route, setRoute] = useState(currentRoute);
  useEffect(() => {
    const sync = () => {
      const next = currentRoute();
      // As a transition, the current screen stays up until the next screen's lazy chunk is ready, instead of a blank flash.
      if (allowed.includes(next)) startTransition(() => setRoute(next));
      else location.hash = '/';
    };
    sync();
    window.addEventListener('hashchange', sync);
    return () => window.removeEventListener('hashchange', sync);
  }, [allowed]);
  return route;
}

export function navigate(route) {
  if (location.hash !== '#' + route) location.hash = route;
}
