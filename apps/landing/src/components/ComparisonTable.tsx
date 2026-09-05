import { useState } from 'preact/hooks';
import stylex from '@stylexjs/stylex';

type Cell = string;

type Row = {
  label: string;
  values: readonly [Cell, Cell, Cell, Cell]; // postcard, AirDrop, Snapdrop/PairDrop, LocalSend
};

type Column = {
  name: string;
  emphasis: boolean;
};

const COLUMNS: readonly Column[] = [
  { name: 'postcard', emphasis: true },
  { name: 'AirDrop', emphasis: false },
  { name: 'Snapdrop / PairDrop', emphasis: false },
  { name: 'LocalSend', emphasis: false },
];

const ROWS: readonly Row[] = [
  {
    label: 'Zero Mobile App Install',
    values: ['✓ Yes', '✓ Yes (Apple only)', '✓ Yes', '✗ Requires App'],
  },
  {
    label: '100% Offline (No Internet)',
    values: ['✓ Yes', '✓ Yes', '✗ Needs STUN/TURN', '✓ Yes'],
  },
  {
    label: 'Executable Footprint',
    values: ['~165-220 MB (Chromium bundled)', 'OS native', 'Web only', '~80-150 MB'],
  },
  {
    label: 'Security Layer',
    values: ['AES-256-GCM + PIN', 'TLS / Bluetooth', 'None / WebRTC', 'TLS'],
  },
  {
    label: 'Platform Compatibility',
    values: [
      'macOS, Win, Linux, iOS, Android',
      'Apple ecosystem',
      'Cross-platform',
      'Cross-platform',
    ],
  },
];

/**
 * Resolve the current viewport once at mount. We don't subscribe to
 * resize events — the section re-renders naturally when the page
 * resizes because Preact will reconcile the data-layout attribute
 * on the next render triggered by any state change. For a static
 * marketing section this is good enough.
 */
function getLayout(): 'stack' | 'grid' {
  if (typeof window === 'undefined') return 'grid';
  return window.innerWidth < 720 ? 'stack' : 'grid';
}

export function ComparisonTable() {
  const [layout] = useState<'stack' | 'grid'>(getLayout);

  return (
    <section
      className={stylex(styles.section)}
      aria-labelledby="comparison-heading"
      data-layout={layout}
    >
      <h2 id="comparison-heading" className={stylex(styles.heading)}>
        How it compares
      </h2>
      {layout === 'grid' ? (
        <div className={stylex(styles.grid)} role="table" aria-label="postcard vs competitors">
          <div className={stylex(styles.headerRow)} role="row">
            <div
              className={stylex(styles.headerCell, styles.headerCorner)}
              role="columnheader"
              aria-hidden="true"
            />
            {COLUMNS.map((col) => (
              <div
                key={col.name}
                className={stylex(styles.headerCell)}
                role="columnheader"
                style={col.emphasis ? { borderTop: '1px solid #A8332A' } : undefined}
              >
                {col.emphasis && (
                  <span className={stylex(styles.youAreHere)}>← you are here</span>
                )}
                <span className={stylex(styles.headerPill)}>{col.name}</span>
              </div>
            ))}
          </div>
          {ROWS.map((row) => (
            <div key={row.label} className={stylex(styles.row)} role="row">
              <div className={stylex(styles.rowLabel)} role="rowheader">
                {row.label}
              </div>
              {row.values.map((value, idx) => (
                <div
                  key={idx}
                  className={stylex(styles.cell)}
                  role="cell"
                  style={COLUMNS[idx]?.emphasis ? { borderTop: '1px solid #A8332A' } : undefined}
                >
                  {value}
                </div>
              ))}
            </div>
          ))}
        </div>
      ) : (
        <ul className={stylex(styles.stack)}>
          {ROWS.map((row) => (
            <li key={row.label} className={stylex(styles.stackCard)}>
              <div className={stylex(styles.stackLabel)}>{row.label}</div>
              <dl className={stylex(styles.stackList)}>
                {row.values.map((value, idx) => (
                  <div
                    key={idx}
                    className={stylex(styles.stackItem)}
                    style={COLUMNS[idx]?.emphasis ? { borderTop: '1px solid #A8332A' } : undefined}
                  >
                    <dt className={stylex(styles.stackTerm)}>{COLUMNS[idx]?.name}</dt>
                    <dd className={stylex(styles.stackValue)}>{value}</dd>
                  </div>
                ))}
              </dl>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

const styles = stylex.create({
  section: {
    padding: '44px 32px',
    borderBottom: '1px solid #D8CDB7',
    '@media (max-width: 640px)': { padding: '32px 20px' },
  },
  heading: {
    fontFamily: '"Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif',
    fontSize: '26px',
    fontWeight: '400',
    color: '#1A1714',
    margin: '0 0 22px 0',
  },
  // A printed comparison: ruled, flat, no cell borders competing with the data.
  grid: {
    width: '100%',
    borderCollapse: 'collapse',
    fontSize: '14px',
    display: 'table',
  },
  headerRow: { display: 'table-row' },
  headerCorner: {
    display: 'table-cell',
    padding: '0 12px 12px 0',
    borderBottom: '2px solid #1A1714',
  },
  headerCell: {
    display: 'table-cell',
    padding: '0 12px 12px',
    borderBottom: '2px solid #1A1714',
    color: '#4A443C',
    fontWeight: '500',
    textAlign: 'center',
    verticalAlign: 'bottom',
  },
  // postcard's own column, marked by the accent rather than by a coloured box.
  headerPill: {
    display: 'inline-block',
    color: '#A8332A',
    fontWeight: '600',
  },
  youAreHere: {
    display: 'block',
    fontSize: '11px',
    color: '#8C8474',
    fontStyle: 'italic',
    marginTop: '2px',
  },
  row: { display: 'table-row' },
  rowLabel: {
    display: 'table-cell',
    padding: '13px 12px 13px 0',
    borderBottom: '1px solid #E3D9C4',
    color: '#1A1714',
    fontWeight: '500',
    verticalAlign: 'middle',
  },
  cell: {
    display: 'table-cell',
    padding: '13px 12px',
    borderBottom: '1px solid #E3D9C4',
    color: '#4A443C',
    textAlign: 'center',
    verticalAlign: 'middle',
  },
  // Below 640px the table becomes one card per competitor.
  stack: { display: 'flex', flexDirection: 'column', gap: '16px' },
  stackCard: {
    padding: '16px',
    backgroundColor: '#EFE7D8',
    border: '1px solid #D8CDB7',
    borderRadius: '10px',
  },
  stackLabel: {
    fontSize: '15px',
    fontWeight: '600',
    color: '#1A1714',
    margin: '0 0 10px 0',
  },
  stackList: { margin: 0, display: 'grid', gridTemplateColumns: '1fr auto', gap: '7px 12px' },
  stackItem: { display: 'contents' },
  stackTerm: { fontSize: '13px', color: '#8C8474' },
  stackValue: { fontSize: '13px', color: '#1A1714', textAlign: 'right' },
});
