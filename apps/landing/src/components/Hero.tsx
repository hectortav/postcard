import stylex from '@stylexjs/stylex';
import { Stamp } from './Stamp';

/**
 * The hero is the product's namesake object: an actual postcard, franked and
 * addressed, lying on the desk. It is the one place in the page that spends
 * any boldness -- everything below it stays quiet and ruled.
 */
export function Hero() {
  return (
    <header className={stylex(styles.header)}>
      <div className={stylex(styles.copy)}>
        <h1 className={stylex(styles.title)}>send a file across the room&mdash;</h1>
        <p className={stylex(styles.subtitle)}>
          A local file-sharing CLI and web dashboard. Drop a file on one device, scan the
          QR on the other. No cloud, no signup, nothing leaves your network.
        </p>
      </div>

      <div className={stylex(styles.cardWrap)} aria-hidden="true">
        <div className={stylex(styles.card)}>
          <div className={stylex(styles.cardTop)}>
            <Postmark />
            <div className={stylex(styles.stampSlot)}>
              <Stamp size={54} hole="#FFFCF5" title="" />
            </div>
          </div>
          <div className={stylex(styles.message)}>
            <span className={stylex(styles.rule, styles.ruleA)} />
            <span className={stylex(styles.rule, styles.ruleB)} />
            <span className={stylex(styles.rule, styles.ruleC)} />
          </div>
          <div className={stylex(styles.address)}>
            <span className={stylex(styles.addrRule, styles.addrA)} />
            <span className={stylex(styles.addrRule, styles.addrB)} />
            <span className={stylex(styles.addrRule, styles.addrC)} />
          </div>
        </div>
      </div>
    </header>
  );
}

/** A cancellation mark: the ring plus the wavy bars that run off its right edge. */
function Postmark() {
  return (
    <svg viewBox="0 0 200 120" className={stylex(styles.postmark)} aria-hidden="true">
      <g
        fill="none"
        stroke="#A8332A"
        strokeWidth="3"
        strokeLinecap="round"
        opacity="0.55"
      >
        <circle cx="56" cy="60" r="40" />
        <circle cx="56" cy="60" r="27" strokeWidth="1.5" />
        <path d="M100 44 Q 126 32 152 44 T 204 44" />
        <path d="M100 60 Q 126 48 152 60 T 204 60" />
        <path d="M100 76 Q 126 64 152 76 T 204 76" />
      </g>
    </svg>
  );
}

const styles = stylex.create({
  header: {
    display: 'grid',
    gridTemplateColumns: '1.05fr 0.95fr',
    alignItems: 'center',
    gap: '48px',
    maxWidth: '960px',
    marginLeft: 'auto',
    marginRight: 'auto',
    padding: '72px 24px 56px',
    boxSizing: 'border-box',
    '@media (max-width: 860px)': {
      gridTemplateColumns: '1fr',
      gap: '40px',
      padding: '48px 20px 36px',
    },
  },
  copy: { minWidth: 0 },
  title: {
    fontFamily: '"Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif',
    fontStyle: 'italic',
    fontWeight: '400',
    fontSize: 'clamp(40px, 6.4vw, 62px)',
    lineHeight: '1.04',
    margin: '0 0 18px 0',
    color: '#1A1714',
    letterSpacing: '-0.015em',
    maxWidth: '13ch',
  },
  subtitle: {
    fontSize: '17px',
    lineHeight: '1.6',
    color: '#4A443C',
    margin: 0,
    maxWidth: '46ch',
  },

  // The card itself: paper stock resting on the desk at a slight angle.
  cardWrap: {
    display: 'flex',
    justifyContent: 'center',
    perspective: '900px',
  },
  card: {
    position: 'relative',
    width: '100%',
    maxWidth: '420px',
    aspectRatio: '3 / 2',
    padding: '20px',
    boxSizing: 'border-box',
    display: 'flex',
    flexDirection: 'column',
    backgroundColor: '#FFFCF5',
    border: '1px solid #D8CDB7',
    borderRadius: '6px',
    transform: 'rotate(-2.4deg)',
    boxShadow: '0 1px 1px rgba(90,74,52,0.12), 0 18px 40px -14px rgba(90,74,52,0.45)',
    '@media (max-width: 860px)': { maxWidth: '360px' },
  },
  cardTop: {
    display: 'flex',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    gap: '12px',
  },
  postmark: { width: '112px', height: '68px', marginTop: '2px' },
  stampSlot: {
    padding: '4px',
    border: '1px dashed #D8CDB7',
    borderRadius: '3px',
    lineHeight: 0,
  },

  // Ruled lines standing in for a handwritten note and an address block. The
  // divider between them is the postcard's own centre rule.
  message: {
    display: 'flex',
    flexDirection: 'column',
    gap: '9px',
    flex: '1 1 auto',
    justifyContent: 'center',
    paddingRight: '46%',
  },
  rule: { height: '5px', borderRadius: '999px', backgroundColor: '#E3D9C4' },
  ruleA: { width: '86%' },
  ruleB: { width: '64%' },
  ruleC: { width: '75%' },
  address: {
    display: 'flex',
    flexDirection: 'column',
    gap: '10px',
    paddingLeft: '52%',
    borderTop: '1px solid #EDE5D5',
    paddingTop: '14px',
  },
  addrRule: { height: '5px', borderRadius: '999px', backgroundColor: '#D8CDB7' },
  addrA: { width: '78%' },
  addrB: { width: '92%' },
  addrC: { width: '60%' },
});
