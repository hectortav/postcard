import { describe, it, expect, vi } from 'vitest';
import { render, fireEvent } from '@testing-library/preact';
import { Clipboard } from './Clipboard';

describe('Clipboard', () => {
  it('hydrates the textarea from the value prop', () => {
    const { container } = render(<Clipboard value="hello" onChange={() => {}} />);
    expect((container.querySelector('textarea') as HTMLTextAreaElement).value).toBe('hello');
  });
  it('fires onChange on input', () => {
    const onChange = vi.fn();
    const { container } = render(<Clipboard value="" onChange={onChange} />);
    const ta = container.querySelector('textarea') as HTMLTextAreaElement;
    fireEvent.input(ta, { target: { value: 'x' } });
    expect(onChange).toHaveBeenCalledWith('x');
  });
});
