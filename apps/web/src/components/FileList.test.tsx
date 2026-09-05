import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/preact';
import { FileList } from './FileList';

describe('FileList', () => {
  it('renders an empty state when no files', () => {
    const { getByText } = render(<FileList files={[]} />);
    expect(getByText(/no files yet/i)).toBeTruthy();
  });
  it('renders a row per file with a download link', () => {
    const { getAllByRole } = render(
      <FileList files={[{ id: 'a', name: 'a.txt', size: 4, mtime: 0, sha256: 'x' }]} />,
    );
    const links = getAllByRole('link') as HTMLAnchorElement[];
    expect(links[0]!.getAttribute('href')).toBe('/api/download/a');
  });
  it('scales the size unit to the file', () => {
    // formatBytes picks a unit per magnitude; a 3 GiB video showing as "3221225472 B" would be
    // unreadable on a phone.
    const { getByText } = render(
      <FileList
        files={[
          { id: 'a', name: 'tiny.txt', size: 512, mtime: 0, sha256: 'x' },
          { id: 'b', name: 'small.txt', size: 2048, mtime: 0, sha256: 'x' },
          { id: 'c', name: 'medium.bin', size: 5 * 1024 * 1024, mtime: 0, sha256: 'x' },
          { id: 'd', name: 'huge.mov', size: 3 * 1024 * 1024 * 1024, mtime: 0, sha256: 'x' },
        ]}
      />,
    );
    expect(getByText(/512 B/)).toBeTruthy();
    expect(getByText(/2\.0 KiB/)).toBeTruthy();
    expect(getByText(/5\.0 MiB/)).toBeTruthy();
    expect(getByText(/3\.00 GiB/)).toBeTruthy();
  });
});
