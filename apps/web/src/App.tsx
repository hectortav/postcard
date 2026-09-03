import stylex from '@stylexjs/stylex';

// Placeholder shell. Task 1.2 introduces the real StyleX design-token system
// (stylex.defineVars + tokens.stylex) which the placeholder imports will
// then replace these inline literals with.
export function App() {
  return <div className={stylex(styles.shell)}>sendme loading…</div>;
}

const styles = stylex.create({
  shell: { padding: '16px', fontFamily: 'system-ui, sans-serif' },
});
