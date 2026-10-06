// Build-time only: renders the landing page to HTML so it paints before the JavaScript loads.
import { renderToString } from 'react-dom/server';
import { Landing } from './pages/Landing.jsx';

export function render() {
  return renderToString(<Landing onLogin={() => {}} onSampleDemo={() => {}} />);
}
