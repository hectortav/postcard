import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, within } from '@testing-library/preact';
import { ComparisonTable } from './ComparisonTable';

const ORIGINAL_INNER_WIDTH = window.innerWidth;

afterEach(() => {
  // Restore viewport between tests so a test that stubs innerWidth doesn't
  // leak into the next one.
  vi.stubGlobal('innerWidth', ORIGINAL_INNER_WIDTH);
});

function setViewport(width: number): void {
  vi.stubGlobal('innerWidth', width);
}

function getSection(): HTMLElement {
  return document.querySelector(
    'section[aria-labelledby="comparison-heading"]',
  ) as HTMLElement;
}

describe('ComparisonTable', () => {
  describe('column + row coverage', () => {
    it('renders all four column headers: sendme, AirDrop, Snapdrop / PairDrop, LocalSend', () => {
      setViewport(1280);
      render(<ComparisonTable />);
      const section = getSection();
      expect(within(section).getByText('sendme')).toBeTruthy();
      expect(within(section).getByText('AirDrop')).toBeTruthy();
      expect(within(section).getByText('Snapdrop / PairDrop')).toBeTruthy();
      expect(within(section).getByText('LocalSend')).toBeTruthy();
    });

    it('renders all five row labels', () => {
      setViewport(1280);
      render(<ComparisonTable />);
      const section = getSection();
      expect(within(section).getByText('Zero Mobile App Install')).toBeTruthy();
      expect(within(section).getByText('100% Offline (No Internet)')).toBeTruthy();
      expect(within(section).getByText('Executable Footprint')).toBeTruthy();
      expect(within(section).getByText('Security Layer')).toBeTruthy();
      expect(within(section).getByText('Platform Compatibility')).toBeTruthy();
    });
  });

  describe('sendme column content (spec-exact)', () => {
    it('renders the "Zero Mobile App Install" sendme value', () => {
      setViewport(1280);
      render(<ComparisonTable />);
      expect(screen.getAllByText(/^✅ Yes$/).length).toBeGreaterThan(0);
    });

    it('renders the "100% Offline (No Internet)" sendme value', () => {
      setViewport(1280);
      render(<ComparisonTable />);
      const section = getSection();
      const offlineRow = within(section).getByText('100% Offline (No Internet)')
        .parentElement as HTMLElement;
      expect(offlineRow.textContent).toMatch(/✅ Yes/);
    });

    it('renders the "Executable Footprint" sendme value "< 30 MB (Native)"', () => {
      setViewport(1280);
      render(<ComparisonTable />);
      const section = getSection();
      const row = within(section).getByText('Executable Footprint')
        .parentElement as HTMLElement;
      expect(row.textContent).toMatch(/< 30 MB \(Native\)/);
    });

    it('renders the "Security Layer" sendme value "AES-256-GCM + PIN"', () => {
      setViewport(1280);
      render(<ComparisonTable />);
      const section = getSection();
      const row = within(section).getByText('Security Layer')
        .parentElement as HTMLElement;
      expect(row.textContent).toMatch(/AES-256-GCM \+ PIN/);
    });

    it('renders the "Platform Compatibility" sendme value with iOS + Android', () => {
      setViewport(1280);
      render(<ComparisonTable />);
      const section = getSection();
      const row = within(section).getByText('Platform Compatibility')
        .parentElement as HTMLElement;
      expect(row.textContent).toMatch(/macOS, Win, Linux, iOS, Android/);
    });
  });

  describe('competitor cells', () => {
    it('renders "OS native" for AirDrop footprint and "Web only" for Snapdrop', () => {
      setViewport(1280);
      render(<ComparisonTable />);
      const section = getSection();
      const row = within(section).getByText('Executable Footprint')
        .parentElement as HTMLElement;
      expect(row.textContent).toMatch(/OS native/);
      expect(row.textContent).toMatch(/Web only/);
      expect(row.textContent).toMatch(/~80-150 MB/);
    });

    it('renders "❌ Needs STUN/TURN" for Snapdrop offline row', () => {
      setViewport(1280);
      render(<ComparisonTable />);
      expect(screen.getAllByText(/❌ Needs STUN\/TURN/).length).toBeGreaterThan(0);
    });
  });

  describe('sendme column emphasis', () => {
    it('renders the "← you are here" annotation above the sendme header', () => {
      setViewport(1280);
      render(<ComparisonTable />);
      const section = getSection();
      const annotation = within(section).getByText(/← you are here/);
      expect(annotation).toBeTruthy();
    });

    it('applies an airmail-brick top border to the sendme column header', () => {
      setViewport(1280);
      render(<ComparisonTable />);
      const section = getSection();
      const sendmeHeader = within(section)
        .getByText('sendme')
        .closest('[role="columnheader"]') as HTMLElement;
      const cs = getComputedStyle(sendmeHeader);
      // happy-dom normalises inline hex to itself; real browsers would
      // resolve to rgb(168, 51, 42). Accept either.
      const normalised = cs.borderTopColor.toLowerCase();
      expect(
        normalised === 'rgb(168, 51, 42)' || normalised === '#a8332a',
      ).toBe(true);
      expect(cs.borderTopWidth).toBe('1px');
      expect(cs.borderTopStyle).toBe('solid');
    });

    it('the sendme column data cells also carry the brick top border', () => {
      setViewport(1280);
      render(<ComparisonTable />);
      const section = getSection();
      const cells = section.querySelectorAll('[role="cell"]');
      // First column is sendme; every sendme cell in the data rows carries the border.
      const sendmeCells = Array.from(cells).filter((c) =>
        c.getAttribute('style')?.includes('border-top'),
      );
      expect(sendmeCells.length).toBe(5);
    });
  });

  describe('layout switching (mobile vs desktop)', () => {
    it('renders with data-layout="grid" at >= 720px (desktop)', () => {
      setViewport(1280);
      const { container } = render(<ComparisonTable />);
      const section = container.querySelector(
        'section[aria-labelledby="comparison-heading"]',
      ) as HTMLElement;
      expect(section.getAttribute('data-layout')).toBe('grid');
      // Desktop uses a [role="table"] container.
      expect(section.querySelector('[role="table"]')).toBeTruthy();
      // 1 header row + 5 data rows = 6 total role="row" elements.
      expect(section.querySelectorAll('[role="row"]').length).toBe(6);
    });

    it('renders with data-layout="stack" at < 720px (mobile)', () => {
      setViewport(480);
      const { container } = render(<ComparisonTable />);
      const section = container.querySelector(
        'section[aria-labelledby="comparison-heading"]',
      ) as HTMLElement;
      expect(section.getAttribute('data-layout')).toBe('stack');
      // Mobile uses a <ul> of cards (5 rows, one <li> per row).
      const list = section.querySelector('ul');
      expect(list).toBeTruthy();
      expect(list?.querySelectorAll('li').length).toBe(5);
      // No [role="table"] in the mobile layout.
      expect(section.querySelector('[role="table"]')).toBeNull();
    });

    it('mobile stack still surfaces every competitor value per row', () => {
      setViewport(480);
      const { container } = render(<ComparisonTable />);
      const section = container.querySelector(
        'section[aria-labelledby="comparison-heading"]',
      ) as HTMLElement;
      // The stack layout lists each row as a card; verify a representative
      // set of values from every column is present somewhere in the section.
      const text = section.textContent ?? '';
      expect(text).toMatch(/AES-256-GCM \+ PIN/);
      expect(text).toMatch(/OS native/);
      expect(text).toMatch(/Web only/);
      expect(text).toMatch(/~80-150 MB/);
      expect(text).toMatch(/✅ Yes \(Apple only\)/);
      expect(text).toMatch(/❌ Requires App/);
    });
  });
});
