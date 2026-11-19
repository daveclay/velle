// The one header the developer typed in the sidebar, kept in the browser between visits and sent
// with every request, for the application's actor resolver.

export const STORED_HEADER_NAME = 'velle.hname';
export const STORED_HEADER_VALUE = 'velle.hvalue';

export function storedRequestHeaders(): Record<string, string> {
  const headerName = localStorage.getItem(STORED_HEADER_NAME);
  const headerValue = localStorage.getItem(STORED_HEADER_VALUE);
  return headerName && headerValue ? { [headerName]: headerValue } : {};
}
