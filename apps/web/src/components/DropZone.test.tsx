import { describe, it, expect, vi } from 'vitest';
import { render, fireEvent, waitFor } from '@testing-library/preact';
import { DropZone } from './DropZone';

vi.mock('../lib/api', () => ({ uploadFile: vi.fn().mockResolvedValue({ id: 'abc' }) }));

describe('DropZone', () => {
  it('renders a drop target', () => {
    const { getByText } = render(<DropZone />);
    expect(getByText(/drop files/i)).toBeTruthy();
  });
  it('uploads a file on drop', async () => {
    const { container } = render(<DropZone />);
    const file = new File(['x'], 'a.txt', { type: 'text/plain' });
    const dt = { files: [file] } as unknown as DataTransfer;
    fireEvent.drop(container.firstElementChild!, { dataTransfer: dt });
    await waitFor(() => expect(container.textContent).toMatch(/a\.txt/));
  });
});
