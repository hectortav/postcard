import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, fireEvent, waitFor, cleanup } from '@testing-library/preact';
import { DropZone } from './DropZone';

// Only the network call is faked; UploadError stays the real class, so these tests exercise
// the same error type the component branches on in production.
vi.mock('../lib/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../lib/api')>()),
  uploadFile: vi.fn(),
}));
import { uploadFile, UploadError } from '../lib/api';
const mockUpload = vi.mocked(uploadFile);

beforeEach(() => {
  mockUpload.mockReset();
  mockUpload.mockResolvedValue({ id: 'abc' });
});
afterEach(cleanup);

/** The drop target itself, which now sits inside a wrapper alongside the queue. */
const zoneOf = (container: Element) =>
  container.querySelector('[role="button"]') as HTMLElement;

const drop = (container: Element, files: File[]) =>
  fireEvent.drop(zoneOf(container), { dataTransfer: { files } as unknown as DataTransfer });

describe('DropZone', () => {
  it('renders a drop target', () => {
    const { getByText } = render(<DropZone />);
    expect(getByText(/drop files/i)).toBeTruthy();
  });

  it('uploads a file on drop', async () => {
    const { container } = render(<DropZone />);
    drop(container, [new File(['x'], 'a.txt', { type: 'text/plain' })]);
    await waitFor(() => expect(container.textContent).toMatch(/a\.txt/));
    expect(mockUpload).toHaveBeenCalledTimes(1);
  });

  it('gives every file in a multi-file drop its own row', async () => {
    // One shared progress slot meant a ten-file drop showed a single bar flicking between
    // names, with no way to tell which had finished and which had failed.
    let release: (() => void) | undefined;
    mockUpload.mockImplementation(
      () => new Promise((resolve) => { release = () => resolve({ id: 'x' }); }),
    );
    const { container } = render(<DropZone />);
    drop(container, [new File(['x'], 'one.txt'), new File(['y'], 'two.txt')]);
    await waitFor(() => expect(container.textContent).toMatch(/one\.txt/));
    expect(container.textContent).toMatch(/two\.txt/);
    release?.();
  });

  it('keeps a failure attached to the file it belongs to', async () => {
    mockUpload
      .mockRejectedValueOnce(new UploadError('too_large', 'That file is larger than the 1 MiB limit this postcard accepts.'))
      .mockResolvedValueOnce({ id: 'ok' });
    const { container, findByRole } = render(<DropZone />);
    drop(container, [new File(['x'], 'big.bin'), new File(['y'], 'small.txt')]);
    expect((await findByRole('alert')).textContent).toMatch(/1 MiB limit/);
    await waitFor(() => expect(container.textContent).toMatch(/Sent/));
    // The earlier failure is still shown against its own file, not cleared by the success.
    expect(container.textContent).toMatch(/1 MiB limit/);
  });

  it('offers a cancel while an upload is in flight, and reports it', async () => {
    // There was no way to stop an upload once started: a 2 GB file dropped by mistake ran to
    // completion.
    let signalSeen: AbortSignal | undefined;
    mockUpload.mockImplementation(
      (_f, _p, signal) =>
        new Promise((_resolve, reject) => {
          signalSeen = signal;
          signal?.addEventListener('abort', () =>
            reject(new UploadError('cancelled', 'Upload cancelled.')),
          );
        }),
    );
    const { container, findByText } = render(<DropZone />);
    drop(container, [new File(['x'], 'huge.bin')]);
    const cancel = await findByText('Cancel');
    fireEvent.click(cancel);
    expect(signalSeen?.aborted).toBe(true);
    await waitFor(() => expect(container.textContent).toMatch(/Cancelled/));
  });

  it('lets a finished row be dismissed', async () => {
    const { container, findByText } = render(<DropZone />);
    drop(container, [new File(['x'], 'a.txt')]);
    fireEvent.click(await findByText('Dismiss'));
    await waitFor(() => expect(container.textContent).not.toMatch(/a\.txt/));
  });

  it('exposes progress to assistive technology', async () => {
    mockUpload.mockImplementation(async (file, onProgress) => {
      onProgress?.(file.size / 2);
      return new Promise(() => {}) as Promise<{ id: string }>;
    });
    const { container, findByRole } = render(<DropZone />);
    drop(container, [new File(['abcd'], 'half.txt')]);
    const bar = await findByRole('progressbar');
    expect(bar.getAttribute('aria-valuenow')).toBe('50');
  });

  it('ignores a drop that carries no files', async () => {
    const { container } = render(<DropZone />);
    fireEvent.drop(zoneOf(container), { dataTransfer: undefined });
    expect(mockUpload).not.toHaveBeenCalled();
  });

  it('highlights while a drag hovers and clears on leave', () => {
    const { container } = render(<DropZone />);
    const zone = zoneOf(container);
    const base = zone.className;
    fireEvent.dragOver(zone);
    expect(zone.className).not.toBe(base);
    fireEvent.dragLeave(zone);
    expect(zone.className).toBe(base);
  });

  it('opens the picker from the keyboard but ignores unrelated keys', () => {
    const { container } = render(<DropZone />);
    const zone = zoneOf(container);
    const input = container.querySelector('input[type=file]') as HTMLInputElement;
    const clicked = vi.spyOn(input, 'click');
    fireEvent.keyDown(zone, { key: 'Enter' });
    fireEvent.keyDown(zone, { key: ' ' });
    expect(clicked).toHaveBeenCalledTimes(2);
    fireEvent.keyDown(zone, { key: 'a' });
    expect(clicked).toHaveBeenCalledTimes(2);
  });
});
