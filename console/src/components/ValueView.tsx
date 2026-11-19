import { isReference, type Reference } from '../model/description';

/** Where a record's page is, for a link to it. The application that places a component decides. */
export type RecordHref = (shapeName: string, key: string | number) => string;

/** A link to a record, reading as its shape and key. */
export function ReferenceLink({ reference, recordHref }: { reference: Reference; recordHref: RecordHref }) {
  return <a className="ref" href={recordHref(reference.shape, reference.key)}>{reference.shape}#{reference.key}</a>;
}

function ReferenceLinks({ references, recordHref }: { references: Reference[]; recordHref: RecordHref }) {
  return (
    <>
      {references.map((reference, position) => (
        <span key={`${reference.shape}#${reference.key}`}>
          {position > 0 ? ', ' : ''}
          <ReferenceLink reference={reference} recordHref={recordHref} />
        </span>
      ))}
    </>
  );
}

function ListView({ values, recordHref }: { values: unknown[]; recordHref: RecordHref }) {
  if (values.length === 0) return <span className="muted">none</span>;
  if (isReference(values[0])) return <ReferenceLinks references={values as Reference[]} recordHref={recordHref} />;
  return <>{values.join(', ')}</>;
}

/** One value of a record: a reference as a link, a list joined, a boolean as yes or no, nothing as a dash. */
export function ValueView({ value, recordHref }: { value: unknown; recordHref: RecordHref }) {
  if (value === null || value === undefined) return <span className="muted">—</span>;
  if (isReference(value)) return <ReferenceLink reference={value} recordHref={recordHref} />;
  if (Array.isArray(value)) return <ListView values={value} recordHref={recordHref} />;
  if (typeof value === 'boolean') return <>{value ? 'yes' : 'no'}</>;
  return <>{String(value)}</>;
}

/** A record's flags are named is<Refinement>; a chip shows the refinement's name. */
export function FlagChips({ flagNames }: { flagNames: string[] }) {
  return (
    <>
      {flagNames.map(flagName => <span className="chip on" key={flagName}>{flagName.slice(2)}</span>)}
    </>
  );
}
