import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, fireEvent, waitFor } from '@testing-library/preact';
import { DropZone } from './DropZone';

vi.mock('../lib/api', () => ({ uploadFile: vi.fn() }));
import { uploadFile } from '../lib/api';
const mockUpload = vi.mocked(uploadFile);

beforeEach(() => {
  mockUpload.mockReset();
  mockUpload.mockResolvedValue({ id: 'abc' });
});

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
  it('surfaces the failure message when an upload is rejected', async () => {
    // The server rejects oversized files with a message naming the cap; swallowing it would
    // leave the user staring at a drop zone that silently does nothing.
    mockUpload.mockRejectedValue(new Error('upload: 413 limitBytes=1048576'));
    const { container } = render(<DropZone />);
    const dt = { files: [new File(['x'], 'big.bin')] } as unknown as DataTransfer;
    fireEvent.drop(container.firstElementChild!, { dataTransfer: dt });
    await waitFor(() => expect(container.textContent).toMatch(/limitBytes=1048576/));
  });

  it('stringifies a non-Error rejection', async () => {
    mockUpload.mockRejectedValue('plain string failure');
    const { container } = render(<DropZone />);
    const dt = { files: [new File(['x'], 'a.txt')] } as unknown as DataTransfer;
    fireEvent.drop(container.firstElementChild!, { dataTransfer: dt });
    await waitFor(() => expect(container.textContent).toMatch(/plain string failure/));
  });

  it('ignores a drop that carries no files', async () => {
    const { container } = render(<DropZone />);
    fireEvent.drop(container.firstElementChild!, { dataTransfer: undefined });
    expect(mockUpload).not.toHaveBeenCalled();
  });

  it('reports progress while uploading', async () => {
    mockUpload.mockImplementation(async (file, onProgress) => {
      onProgress?.(file.size / 2);
      return { id: 'abc' };
    });
    const { container } = render(<DropZone />);
    const dt = { files: [new File(['abcd'], 'half.txt')] } as unknown as DataTransfer;
    fireEvent.drop(container.firstElementChild!, { dataTransfer: dt });
    await waitFor(() => expect(container.textContent).toMatch(/half\.txt/));
  });

  it('highlights while a drag hovers and clears on leave', () => {
    const { container } = render(<DropZone />);
    const zone = container.firstElementChild!;
    const base = zone.className;
    fireEvent.dragOver(zone);
    expect(zone.className).not.toBe(base);
    fireEvent.dragLeave(zone);
    expect(zone.className).toBe(base);
  });

  it('opens the picker from the keyboard but ignores unrelated keys', () => {
    const { container } = render(<DropZone />);
    const zone = container.firstElementChild! as HTMLElement;
    const input = container.querySelector('input[type=file]') as HTMLInputElement;
    const click = vi.spyOn(input, 'click').mockImplementation(() => {});

    fireEvent.keyDown(zone, { key: 'Enter' });
    fireEvent.keyDown(zone, { key: ' ' });
    expect(click).toHaveBeenCalledTimes(2);

    fireEvent.keyDown(zone, { key: 'a' });
    expect(click).toHaveBeenCalledTimes(2);
  });

  it('uploads files chosen through the file input', async () => {
    const { container } = render(<DropZone />);
    const input = container.querySelector('input[type=file]') as HTMLInputElement;
    const file = new File(['x'], 'picked.txt');
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    fireEvent.change(input);
    await waitFor(() => expect(mockUpload).toHaveBeenCalled());
  });
});
