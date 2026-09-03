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
});
