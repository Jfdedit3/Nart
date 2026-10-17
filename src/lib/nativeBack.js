const handlers = [];

/** Newest overlay of the same priority closes first. */
export function registerNativeBack(handler, priority = 100) {
  const entry = { handler, priority };
  handlers.push(entry);
  return () => { const index = handlers.indexOf(entry); if (index >= 0) handlers.splice(index, 1); };
}

export function handleNativeBack() {
  const ordered = handlers.slice().reverse().sort((a, b) => b.priority - a.priority);
  return ordered.some(({ handler }) => handler() !== false);
}
