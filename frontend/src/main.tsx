import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import { StaffConsole } from './components/staff/StaffConsole';
import './index.css';

/** The front-desk console lives at /staff (CloudFront maps unknown paths to index.html, so a direct visit works). */
function isStaffRoute(): boolean {
  const path = window.location.pathname.replace(/\/+$/, '');
  return path.endsWith('/staff') || window.location.hash === '#/staff';
}

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>{isStaffRoute() ? <StaffConsole /> : <App />}</React.StrictMode>
);
