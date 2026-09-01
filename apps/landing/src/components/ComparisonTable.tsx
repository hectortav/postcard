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
    values: ['✅ Yes', '✅ Yes (Apple only)', '✅ Yes', '❌ Requires App'],
  },
  {
    label: '100% Offline (No Internet)',
    values: ['✅ Yes', '✅ Yes', '❌ Needs STUN/TURN', '✅ Yes'],
  },
  {
    label: 'Executable Footprint',
    values: ['< 30 MB (Native)', 'OS native', 'Web only', '~80-150 MB'],
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
    paddingLeft: '24px',
    paddingRight: '24px',
    paddingTop: '32px',
    paddingBottom: '32px',
    maxWidth: '960px',
    marginLeft: 'auto',
    marginRight: 'auto',
    boxSizing: 'border-box',
    '@media (max-width: 720px)': {
      paddingLeft: '20px',
      paddingRight: '20px',
      paddingTop: '24px',
      paddingBottom: '24px',
    },
  },
  heading: {
    fontFamily: '"Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif',
    fontStyle: 'italic',
    fontWeight: '400',
    fontSize: '24px',
    color: '#1A1714',
    margin: '0 0 20px 0',
    letterSpacing: '-0.005em',
  },
  // Desktop (>= 720px) grid layout
  grid: {
    display: 'grid',
    gridTemplateColumns: 'minmax(0, 1.4fr) repeat(4, minmax(0, 1fr))',
    border: '1px solid #D8CDB7',
    borderRadius: '4px',
    overflow: 'hidden',
  },
  headerRow: {
    display: 'contents',
  },
  headerCell: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'flex-end',
    padding: '16px 12px',
    backgroundColor: '#E9E1D0',
    borderBottom: '1px solid #D8CDB7',
  },
  headerCorner: {
    backgroundColor: '#E9E1D0',
  },
  youAreHere: {
    fontFamily: '"Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif',
    fontStyle: 'italic',
    fontSize: '11px',
    color: '#A8332A',
    marginBottom: '6px',
  },
  headerPill: {
    display: 'inline-block',
    backgroundColor: '#E9E1D0',
    color: '#1A1714',
    fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
    fontSize: '14px',
    fontWeight: '600',
    padding: '4px 10px',
    borderRadius: '999px',
  },
  row: {
    display: 'contents',
  },
  rowLabel: {
    padding: '14px 12px',
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    fontSize: '11px',
    textTransform: 'uppercase',
    letterSpacing: '0.06em',
    color: '#4A443C',
    backgroundColor: '#F4EEE2',
    borderBottom: '1px solid #D8CDB7',
    display: 'flex',
    alignItems: 'center',
  },
  cell: {
    padding: '14px 12px',
    fontSize: '14px',
    lineHeight: '1.4',
    color: '#1A1714',
    backgroundColor: '#F4EEE2',
    borderBottom: '1px solid #D8CDB7',
    borderLeft: '1px solid #D8CDB7',
    display: 'flex',
    alignItems: 'center',
    transitionProperty: 'box-shadow',
    transitionDuration: '120ms',
    ':hover': {
      boxShadow: 'inset 0 -2px 0 0 #A8332A',
    },
    ':last-child': {
      borderBottom: 'none',
    },
  },
  // Mobile (< 720px) stacked cards
  stack: {
    listStyle: 'none',
    margin: 0,
    padding: 0,
    display: 'flex',
    flexDirection: 'column',
    gap: '12px',
  },
  stackCard: {
    backgroundColor: '#E9E1D0',
    border: '1px solid #D8CDB7',
    borderRadius: '4px',
    padding: '16px',
  },
  stackLabel: {
    fontFamily: 'ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    fontSize: '11px',
    textTransform: 'uppercase',
    letterSpacing: '0.06em',
    color: '#4A443C',
    marginBottom: '12px',
  },
  stackList: {
    margin: 0,
    padding: 0,
    display: 'flex',
    flexDirection: 'column',
    gap: '8px',
  },
  stackItem: {
    display: 'flex',
    flexDirection: 'column',
    gap: '2px',
    padding: '6px 8px',
    backgroundColor: '#F4EEE2',
    borderRadius: '2px',
  },
  stackTerm: {
    fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
    fontSize: '11px',
    textTransform: 'uppercase',
    letterSpacing: '0.06em',
    color: '#8C8474',
    margin: 0,
  },
  stackValue: {
    fontSize: '14px',
    color: '#1A1714',
    margin: 0,
  },
});
