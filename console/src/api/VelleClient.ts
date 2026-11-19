import type { ModelDescription } from '../model/description';

/**
 * The connection a component needs to a running Velle system. A component is handed one and
 * knows nothing else about where the system is or who is calling.
 */
export interface VelleClient {
  /** The model description, GET <api>. */
  describeModel(): Promise<ModelDescription>;
  /** One GraphQL request; answers its data, or throws a VelleRequestError saying what the API refused. */
  request<Data>(document: string, variables?: Record<string, unknown>): Promise<Data>;
}

/** A request the API refused or could not answer, with the text to show for it. */
export class VelleRequestError extends Error {}

/** The text to show for anything thrown while asking the API. */
export function errorText(thrown: unknown): string {
  return thrown instanceof Error ? thrown.message : String(thrown);
}

export interface HttpClientOptions {
  /** Where the REST API answers the model description. */
  apiPath: string;
  /** Where the GraphQL endpoint is. */
  graphqlPath: string;
  /** Headers added to every request, read afresh for each one. */
  headers?: () => Record<string, string>;
}

interface GraphQLError {
  message: string;
  extensions?: { details?: string[] };
}

function graphQLErrorText(error: GraphQLError): string {
  const details = error.extensions?.details ?? [];
  return [error.message, ...details].join('\n');
}

async function answerOrFail(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!response.ok) throw new VelleRequestError(`The API answered ${response.status}${text ? '\n' + text : ''}`);
  return JSON.parse(text);
}

/** A client over HTTP: the model description from the REST API, everything else from GraphQL. */
export function httpClient(options: HttpClientOptions): VelleClient {
  const callerHeaders = () => (options.headers ? options.headers() : {});

  async function describeModel(): Promise<ModelDescription> {
    const response = await fetch(options.apiPath, { headers: callerHeaders() });
    return await answerOrFail(response) as ModelDescription;
  }

  async function request<Data>(document: string, variables?: Record<string, unknown>): Promise<Data> {
    const response = await fetch(options.graphqlPath, {
      method: 'POST',
      headers: { ...callerHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: document, variables: variables ?? {} }),
    });
    const answer = await answerOrFail(response) as { data?: Data; errors?: GraphQLError[] };
    if (answer.errors && answer.errors.length > 0) {
      throw new VelleRequestError(answer.errors.map(graphQLErrorText).join('\n\n'));
    }
    return answer.data as Data;
  }

  return { describeModel, request };
}
