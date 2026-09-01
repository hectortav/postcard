import { describe, it, expect } from 'vitest';
import { render, waitFor } from '@testing-library/preact';
import { QRCode } from './QRCode';

describe('QRCode', () => {
  it('renders a hotspot-mode QR for the given SSID/password', async () => {
    const { container } = render(<QRCode mode="hotspot" ssid="postcard-XYZ" password="abc12345" />);
    await waitFor(() => expect(container.querySelector('svg')).toBeTruthy());
  });
  it('renders the connection URL as a QR in lan mode', async () => {
    const { container } = render(<QRCode mode="lan" url="http://192.168.1.10:8080/" />);
    await waitFor(() => expect(container.querySelector('svg')).toBeTruthy());
  });
});
