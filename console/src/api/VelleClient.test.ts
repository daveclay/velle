import { afterEach, describe, expect, it, vi } from 'vitest';
import { httpClient } from './VelleClient';

function answering(status: number, body: unknown) {
  const fetchSpy = vi.fn(async () => new Response(JSON.stringify(body), { status }));
  vi.stubGlobal('fetch', fetchSpy);
  return fetchSpy;
}

const client = httpClient({ apiPath: '/api', graphqlPath: '/graphql', headers: () => ({ 'X-Velle-Actor': 'Staff:1' }) });

describe('the client over HTTP', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('posts a document with its variables and the caller\'s headers, and answers the data', async () => {
    const fetchSpy = answering(200, { data: { count: 3 } });
    expect(await client.request('query { count }', { where: 'a > 1' })).toEqual({ count: 3 });
    const [path, sent] = fetchSpy.mock.calls[0] as unknown as [string, RequestInit];
    expect(path).toBe('/graphql');
    expect(sent.headers).toMatchObject({ 'X-Velle-Actor': 'Staff:1' });
    expect(JSON.parse(sent.body as string)).toEqual({ query: 'query { count }', variables: { where: 'a > 1' } });
  });

  it('refuses with the API\'s message and its details when GraphQL answers errors', async () => {
    answering(200, { errors: [{ message: 'the query was refused', extensions: { kind: 'BAD_REQUEST', details: ['Invoice.customer is not orderable'] } }] });
    await expect(client.request('query { x }')).rejects.toThrow('the query was refused\nInvoice.customer is not orderable');
  });

  it('refuses with the status when the endpoint does not answer 200', async () => {
    answering(401, { error: 'no actor' });
    await expect(client.describeModel()).rejects.toThrow('The API answered 401');
  });

  it('reads the model description from the REST API', async () => {
    const fetchSpy = answering(200, { schedules: [], acts: [], shapes: [], authorization: false });
    expect((await client.describeModel()).shapes).toEqual([]);
    expect((fetchSpy.mock.calls[0] as unknown as [string])[0]).toBe('/api');
  });
});
