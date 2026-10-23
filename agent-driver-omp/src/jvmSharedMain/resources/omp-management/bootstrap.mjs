// Silence native provider diagnostics before loading installed-container plugins.
console.log = console.info = console.warn = console.error = () => {};
// Management modules import platform-specific OMP sources unavailable outside this container.
await import('./main.mjs');
