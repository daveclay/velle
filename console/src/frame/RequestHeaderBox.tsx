import { useState } from 'react';
import { STORED_HEADER_NAME, STORED_HEADER_VALUE } from './requestHeader';

function StoredInput({ storageKey, placeholder }: { storageKey: string; placeholder: string }) {
  const [text, setText] = useState(() => localStorage.getItem(storageKey) ?? '');
  function keep(typedText: string) {
    setText(typedText);
    localStorage.setItem(storageKey, typedText.trim());
  }
  return <input placeholder={placeholder} value={text} onChange={event => keep(event.target.value)} />;
}

/** The sidebar's box for the one header sent with every request. */
export function RequestHeaderBox() {
  return (
    <div className="request-header">
      <h2>Request header</h2>
      <StoredInput storageKey={STORED_HEADER_NAME} placeholder="Header name, e.g. X-Velle-Actor" />
      <StoredInput storageKey={STORED_HEADER_VALUE} placeholder="Value, e.g. Staff:1" />
      <p className="note">Sent with every request, for the application's actor resolver.</p>
    </div>
  );
}
