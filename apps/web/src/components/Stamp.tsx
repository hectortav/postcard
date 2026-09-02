/**
 * The postcard stamp — the app icon, inline.
 *
 * Drawn rather than loaded so it inherits the surface it sits on: the
 * perforations are punched in the *background* colour, which is what makes
 * them read as holes in the sheet rather than white dots. Callers on a
 * non-paper surface must pass `hole` to match.
 *
 * Geometry mirrors icons/postcard-small.svg (the small-size optical variant:
 * no rotation, coarse perforations), so the mark on screen is the same one
 * in the Dock.
 */
type Props = {
  size?: number;
  /** Background the stamp sits on; the perforations are punched in this colour. */
  hole?: string;
  title?: string;
};

const EDGE = [74, 165, 256, 347, 438];
const INNER = [165, 256, 347];

export function Stamp({ size = 40, hole = '#F4EEE2', title = 'postcard' }: Props) {
  return (
    <svg
      viewBox="0 0 512 512"
      width={size}
      height={size}
      role="img"
      aria-label={title}
      style={{ display: 'block', flex: '0 0 auto' }}
    >
      <rect x="74" y="74" width="364" height="364" fill="#A8332A" />
      <g fill={hole}>
        {EDGE.map((x) => (
          <circle key={`t${x}`} cx={x} cy={74} r={21} />
        ))}
        {EDGE.map((x) => (
          <circle key={`b${x}`} cx={x} cy={438} r={21} />
        ))}
        {INNER.map((y) => (
          <circle key={`l${y}`} cx={74} cy={y} r={21} />
        ))}
        {INNER.map((y) => (
          <circle key={`r${y}`} cx={438} cy={y} r={21} />
        ))}
      </g>
      <text
        x="256"
        y="352"
        textAnchor="middle"
        fontFamily='"Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif'
        fontSize="270"
        fill="#F4EEE2"
      >
        p
      </text>
    </svg>
  );
}
