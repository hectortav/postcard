import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/preact';
import { ErrorBoundary } from './ErrorBoundary';

function Boom(): never {
  throw new Error('something came apart');
}

afterEach(cleanup);

describe('ErrorBoundary', () => {
  it('renders its children when nothing goes wrong', () => {
    const { getByText } = render(
      <ErrorBoundary>
        <p>the dashboard</p>
      </ErrorBoundary>,
    );
    expect(getByText('the dashboard')).toBeTruthy();
  });

  it('shows a way forward instead of a blank page when a child throws', () => {
    // Preact unmounts the whole tree on a render error, so without this the page went
    // completely blank with no hint that the server was still running and reachable.
    const quiet = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { getByRole } = render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );
    const alert = getByRole('alert');
    expect(alert.textContent).toMatch(/hit a problem/i);
    expect(alert.textContent).toMatch(/something came apart/);
    // The address is the thing the user actually needs to carry on from another device.
    expect(alert.textContent).toContain(location.host);
    quiet.mockRestore();
  });
});
