import { useEffect, useState, type FormEvent } from 'react';
import { errorText, type VelleClient } from '../api/VelleClient';
import { countDocument, memberValue, pageDocument } from '../graphql/documents';
import { defaultOrderBy, shapeNamed, type ModelDescription, type ShapeDescription, type VelleRecord } from '../model/description';
import { FlagChips, ReferenceLink, ValueView, type RecordHref } from './ValueView';

/** What a table is asked: the predicate in the spec's language and the members to order by. */
export interface TableQuery {
  where: string;
  orderBy: string;
}

export interface ShapeTableProps {
  model: ModelDescription;
  client: VelleClient;
  shapeName: string;
  recordHref: RecordHref;
  /** The query the table opens with; without one, no predicate and the shape's first orderable member. */
  initialQuery?: TableQuery;
  /** Told each query the developer runs, so whoever places the table can keep it. */
  onQuery?: (query: TableQuery) => void;
  pageSize?: number;
}

interface Page {
  items: VelleRecord[];
  next: string | null;
}

function shapeSubtitle(shape: ShapeDescription): string {
  const what = shape.kind === 'refinement' ? 'Refinement of ' + (shape.bases ?? []).join(' or ') : 'Shape';
  return what + (shape.act ? ', committed as an act' : '') + '.';
}

function orderByList(orderBy: string): string[] {
  return orderBy.split(',').map(memberName => memberName.trim()).filter(memberName => memberName !== '');
}

function RecordRow({ shape, record, recordHref }: { shape: ShapeDescription; record: VelleRecord; recordHref: RecordHref }) {
  const columns = shape.members.filter(member => member.kind !== 'inverse');
  const inverseColumns = shape.members.filter(member => member.kind === 'inverse');
  const flags = shape.flags ?? [];
  return (
    <tr>
      <td><ReferenceLink reference={record} recordHref={recordHref} /></td>
      {columns.map(column => {
        const value = memberValue(record, column);
        return <td key={column.name} className={typeof value === 'number' ? 'numeric' : ''}><ValueView value={value} recordHref={recordHref} /></td>;
      })}
      {inverseColumns.map(column => {
        const collection = memberValue(record, column);
        return <td key={column.name} className="numeric">{Array.isArray(collection) ? collection.length : '—'}</td>;
      })}
      {flags.length > 0 && <td><FlagChips flagNames={flags.filter(flagName => record[flagName] === true)} /></td>}
    </tr>
  );
}

/** The table: the key, every member but the inverse collections, those collections as counts, then the flags set. */
function RecordsTable({ shape, records, recordHref }: { shape: ShapeDescription; records: VelleRecord[]; recordHref: RecordHref }) {
  if (records.length === 0) return <div className="empty">No records.</div>;
  const columns = shape.members.filter(member => member.kind !== 'inverse');
  const inverseColumns = shape.members.filter(member => member.kind === 'inverse');
  const hasFlags = (shape.flags ?? []).length > 0;
  return (
    <table>
      <thead>
        <tr>
          <th>key</th>
          {columns.concat(inverseColumns).map(column => <th key={column.name}>{column.name}</th>)}
          {hasFlags && <th>is</th>}
        </tr>
      </thead>
      <tbody>
        {records.map(record => <RecordRow key={`${record.shape}#${record.key}`} shape={shape} record={record} recordHref={recordHref} />)}
      </tbody>
    </table>
  );
}

/**
 * A shape's records as a table, with the query's predicate and order, paged by the API's cursor,
 * and the count of every record the predicate holds for.
 */
export function ShapeTable({ model, client, shapeName, recordHref, initialQuery, onQuery, pageSize = 25 }: ShapeTableProps) {
  const shape = shapeNamed(model, shapeName);
  const openingQuery = initialQuery ?? { where: '', orderBy: shape ? defaultOrderBy(shape) : '' };
  const [typedQuery, setTypedQuery] = useState<TableQuery>(openingQuery);
  const [query, setQuery] = useState<TableQuery>(openingQuery);
  const [records, setRecords] = useState<VelleRecord[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [count, setCount] = useState<number | null>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);

  async function readPage(after: string | null): Promise<Page> {
    const variables = { where: query.where || null, orderBy: orderByList(query.orderBy), first: pageSize, after };
    const answer = await client.request<{ page: Page }>(pageDocument(model, shapeName), variables);
    return answer.page;
  }

  // a new query starts the table over: the first page, and the count beside it
  useEffect(() => {
    let abandoned = false;
    setRecords([]);
    setNextCursor(null);
    setCount(null);
    setRefusal(null);
    setLoaded(false);
    if (!shape) return;
    readPage(null)
      .then(page => {
        if (abandoned) return;
        setRecords(page.items);
        setNextCursor(page.next);
        setLoaded(true);
      })
      .catch(thrown => { if (!abandoned) setRefusal(errorText(thrown)); });
    client.request<{ count: number }>(countDocument(shapeName), { where: query.where || null })
      .then(answer => { if (!abandoned) setCount(answer.count); })
      .catch(() => { /* the page's own refusal says what is wrong */ });
    return () => { abandoned = true; };
  }, [model, client, shapeName, query]);

  if (!shape) return null;

  function runQuery(event: FormEvent) {
    event.preventDefault();
    setQuery({ ...typedQuery });
    if (onQuery) onQuery(typedQuery);
  }

  async function readMore() {
    try {
      const page = await readPage(nextCursor);
      setRecords(records.concat(page.items));
      setNextCursor(page.next);
    } catch (thrown) {
      setRefusal(errorText(thrown));
    }
  }

  return (
    <>
      <h2 className="title">{shape.name}</h2>
      <p className="subtitle">{shapeSubtitle(shape)}</p>
      <form className="toolbar" onSubmit={runQuery}>
        <input className="where" aria-label="where" placeholder="where, in the spec's language: balance > 0 and due < today"
          value={typedQuery.where} onChange={event => setTypedQuery({ ...typedQuery, where: event.target.value })} />
        <label>order by <input className="order-by" value={typedQuery.orderBy}
          onChange={event => setTypedQuery({ ...typedQuery, orderBy: event.target.value })} /></label>
        <button className="primary">Query</button>
        <span className="muted">{count === null ? '' : `${count} in all`}</span>
      </form>
      {refusal !== null && <pre className="outcome bad">{refusal}</pre>}
      {refusal === null && loaded && <RecordsTable shape={shape} records={records} recordHref={recordHref} />}
      {refusal === null && loaded && (
        <div className="toolbar pager">
          <span className="muted">{records.length} shown</span>
          {nextCursor && <button onClick={readMore}>More</button>}
        </div>
      )}
    </>
  );
}
